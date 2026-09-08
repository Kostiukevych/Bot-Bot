import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import '../models/api_credentials.dart';
import 'secure_storage_service.dart';

/// Сервис для взаимодействия с Binance REST API (Testnet и Live с защитой).
class BinanceAuthService {
  static const String testnetBaseUrl = 'https://testnet.binance.vision';
  static const String mainnetBaseUrl = 'https://api.binance.com';

  final http.Client _client;
  final SecureStorageService _storageService;

  BinanceAuthService({
    http.Client? client,
    SecureStorageService? storageService,
  })  : _client = client ?? http.Client(),
        _storageService = storageService ?? SecureStorageService();

  /// Генерация HMAC SHA256 подписи в Hex формате
  String _generateSignature(String queryString, String secretKey) {
    final keyBytes = utf8.encode(secretKey);
    final queryBytes = utf8.encode(queryString);
    final hmacSha256 = Hmac(sha256, keyBytes);
    final digest = hmacSha256.convert(queryBytes);
    return digest.toString();
  }

  /// Проверка доступности сервера: GET /api/v3/ping
  Future<bool> ping({bool isTestnet = true}) async {
    final baseUrl = isTestnet ? testnetBaseUrl : mainnetBaseUrl;
    final uri = Uri.parse('$baseUrl/api/v3/ping');

    final response = await _client.get(uri).timeout(
      const Duration(seconds: 8),
    );
    return response.statusCode == 200;
  }

  /// Запрос информации об аккаунте: GET /api/v3/account (Signed endpoint)
  /// Физически блокирует обращение к mainnet, если не подтверждено!
  Future<BinanceAccountInfo> getAccountInfo({
    required String apiKey,
    required String secretKey,
    bool isTestnet = true,
    bool allowMainnetBypass = false,
  }) async {
    // Жесткая физическая блокировка Mainnet без явного разрешения
    if (!isTestnet && !allowMainnetBypass) {
      throw Exception(
        'Mainnet физически заблокирован для безопасности средств. '
        'Используйте Binance Spot Testnet.',
      );
    }

    if (apiKey.trim().isEmpty || secretKey.trim().isEmpty) {
      throw Exception('API Key и Secret Key не могут быть пустыми.');
    }

    final baseUrl = isTestnet ? testnetBaseUrl : mainnetBaseUrl;
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final queryString = 'timestamp=$timestamp&recvWindow=5000';
    final signature = _generateSignature(queryString, secretKey.trim());
    final fullUrl = '$baseUrl/api/v3/account?$queryString&signature=$signature';

    final uri = Uri.parse(fullUrl);

    final response = await _client.get(
      uri,
      headers: {
        'X-MBX-APIKEY': apiKey.trim(),
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      final Map<String, dynamic> jsonMap = jsonDecode(response.body);
      return BinanceAccountInfo.fromJson(jsonMap);
    } else {
      // Парсим ответ об ошибке от Binance API
      String errorDescription = 'HTTP ${response.statusCode}';
      try {
        final Map<String, dynamic> errJson = jsonDecode(response.body);
        final code = errJson['code'];
        final msg = errJson['msg'];
        if (code != null && msg != null) {
          errorDescription = '[$code] $msg';
        } else {
          errorDescription = response.body;
        }
      } catch (_) {
        errorDescription = response.body.isNotEmpty
            ? response.body
            : 'Ошибка соединения с сервером Binance (${response.statusCode})';
      }
      throw Exception(errorDescription);
    }
  }

  /// Комплексная проверка подключения: Ping + Signed Account Request
  Future<ConnectionCheckResult> checkConnection({
    required String apiKey,
    required String secretKey,
    bool isTestnet = true,
  }) async {
    // 1. Проверяем ping
    try {
      final pingOk = await ping(isTestnet: isTestnet);
      if (!pingOk) {
        return ConnectionCheckResult.error(
          message: 'Сервер Binance ${isTestnet ? "Testnet" : "Live"} вернул статус ошибки при Ping.',
          isPingOk: false,
        );
      }
    } catch (e) {
      final cleanMessage = e.toString().replaceFirst('Exception: ', '');
      return ConnectionCheckResult.error(
        message: 'Ошибка соединения при Ping: $cleanMessage',
        isPingOk: false,
      );
    }

    // 2. Проверяем валидность ключей и права доступа через /api/v3/account
    try {
      final accountInfo = await getAccountInfo(
        apiKey: apiKey,
        secretKey: secretKey,
        isTestnet: isTestnet,
      );
      return ConnectionCheckResult.success(accountInfo: accountInfo);
    } catch (e) {
      final cleanMessage = e.toString().replaceFirst('Exception: ', '');
      return ConnectionCheckResult.error(
        message: cleanMessage,
        isPingOk: true,
      );
    }
  }

  /// Валидация через тестовый запрос и безопасное сохранение в Keystore
  Future<BinanceAccountInfo> validateAndSave(ApiCredentials credentials) async {
    // 1. Делаем тестовый подписанный запрос к /api/v3/account
    final accountInfo = await getAccountInfo(
      apiKey: credentials.apiKey,
      secretKey: credentials.secretKey,
      isTestnet: credentials.isTestnet,
    );

    // 2. Если запрос успешен — сохраняем в Keystore
    await _storageService.saveCredentials(credentials);

    return accountInfo;
  }
}
