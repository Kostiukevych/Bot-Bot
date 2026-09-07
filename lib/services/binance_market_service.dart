import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import '../models/market_models.dart';

/// Сервис для работы с рыночными данными и ордерами Binance Testnet REST API
class BinanceMarketService {
  static const String testnetBaseUrl = 'https://testnet.binance.vision';

  final http.Client _client;

  BinanceMarketService({http.Client? client})
      : _client = client ?? http.Client();

  String _generateSignature(String queryString, String secretKey) {
    final keyBytes = utf8.encode(secretKey);
    final queryBytes = utf8.encode(queryString);
    final hmacSha256 = Hmac(sha256, keyBytes);
    final digest = hmacSha256.convert(queryBytes);
    return digest.toString();
  }

  /// Загрузка торговых пар через GET /api/v3/exchangeInfo
  /// Фильтрация: status == 'TRADING' && quoteAsset == 'USDT'
  Future<List<TradingPair>> getTradingPairs() async {
    final uri = Uri.parse('$testnetBaseUrl/api/v3/exchangeInfo');
    final response = await _client.get(uri).timeout(const Duration(seconds: 12));

    if (response.statusCode == 200) {
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final symbols = data['symbols'] as List<dynamic>? ?? [];

      final List<TradingPair> pairs = [];
      for (final s in symbols) {
        final status = s['status'] as String? ?? '';
        final quoteAsset = s['quoteAsset'] as String? ?? '';
        if (status == 'TRADING' && quoteAsset.toUpperCase() == 'USDT') {
          pairs.add(TradingPair.fromJson(s as Map<String, dynamic>));
        }
      }

      // Приоритет популярным парам: BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT
      pairs.sort((a, b) {
        final popular = ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT', 'ADAUSDT'];
        final idxA = popular.indexOf(a.symbol);
        final idxB = popular.indexOf(b.symbol);
        if (idxA != -1 && idxB != -1) return idxA.compareTo(idxB);
        if (idxA != -1) return -1;
        if (idxB != -1) return 1;
        return a.symbol.compareTo(b.symbol);
      });

      return pairs;
    } else {
      throw Exception('Ошибка загрузки exchangeInfo: ${response.statusCode}');
    }
  }

  /// Получение открытых ордеров: GET /api/v3/openOrders
  Future<List<OpenOrder>> getOpenOrders({
    required String apiKey,
    required String secretKey,
    String? symbol,
  }) async {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    var queryString = 'timestamp=$timestamp&recvWindow=5000';
    if (symbol != null && symbol.isNotEmpty) {
      queryString = 'symbol=$symbol&$queryString';
    }
    final signature = _generateSignature(queryString, secretKey.trim());
    final fullUrl = '$testnetBaseUrl/api/v3/openOrders?$queryString&signature=$signature';

    final response = await _client.get(
      Uri.parse(fullUrl),
      headers: {
        'X-MBX-APIKEY': apiKey.trim(),
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      final List<dynamic> list = jsonDecode(response.body);
      return list.map((item) => OpenOrder.fromJson(item as Map<String, dynamic>)).toList();
    } else {
      throw Exception('Ошибка получения открытых ордеров: ${response.body}');
    }
  }

  /// Отмена ордера: DELETE /api/v3/order
  Future<bool> cancelOrder({
    required String apiKey,
    required String secretKey,
    required String symbol,
    required int orderId,
  }) async {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final queryString = 'symbol=$symbol&orderId=$orderId&timestamp=$timestamp&recvWindow=5000';
    final signature = _generateSignature(queryString, secretKey.trim());
    final fullUrl = '$testnetBaseUrl/api/v3/order?$queryString&signature=$signature';

    final response = await _client.delete(
      Uri.parse(fullUrl),
      headers: {
        'X-MBX-APIKEY': apiKey.trim(),
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 10));

    return response.statusCode == 200;
  }

  /// Экстренная отмена всех ордеров по символу или всем парам
  Future<int> cancelAllOpenOrders({
    required String apiKey,
    required String secretKey,
    List<OpenOrder>? orders,
  }) async {
    final activeOrders = orders ?? await getOpenOrders(apiKey: apiKey, secretKey: secretKey);
    int cancelledCount = 0;
    for (final o in activeOrders) {
      final ok = await cancelOrder(
        apiKey: apiKey,
        secretKey: secretKey,
        symbol: o.symbol,
        orderId: o.orderId,
      );
      if (ok) cancelledCount++;
    }
    return cancelledCount;
  }

  /// Получение истории исполненных сделок по символу через GET /api/v3/myTrades
  Future<List<Map<String, dynamic>>> getMyTrades({
    required String apiKey,
    required String secretKey,
    required String symbol,
    int limit = 100,
  }) async {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final queryString = 'symbol=${symbol.toUpperCase().trim()}&limit=$limit&timestamp=$timestamp&recvWindow=5000';
    final signature = _generateSignature(queryString, secretKey.trim());
    final fullUrl = '$testnetBaseUrl/api/v3/myTrades?$queryString&signature=$signature';

    final response = await _client.get(
      Uri.parse(fullUrl),
      headers: {
        'X-MBX-APIKEY': apiKey.trim(),
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      final List<dynamic> list = jsonDecode(response.body);
      return list.cast<Map<String, dynamic>>();
    } else {
      throw Exception('Ошибка загрузки myTrades: ${response.body}');
    }
  }

  /// Получение всех ордеров (включая FILLED и CANCELED) через GET /api/v3/allOrders
  Future<List<Map<String, dynamic>>> getAllOrders({
    required String apiKey,
    required String secretKey,
    required String symbol,
    int limit = 100,
  }) async {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final queryString = 'symbol=${symbol.toUpperCase().trim()}&limit=$limit&timestamp=$timestamp&recvWindow=5000';
    final signature = _generateSignature(queryString, secretKey.trim());
    final fullUrl = '$testnetBaseUrl/api/v3/allOrders?$queryString&signature=$signature';

    final response = await _client.get(
      Uri.parse(fullUrl),
      headers: {
        'X-MBX-APIKEY': apiKey.trim(),
        'Accept': 'application/json',
      },
    ).timeout(const Duration(seconds: 10));

    if (response.statusCode == 200) {
      final List<dynamic> list = jsonDecode(response.body);
      return list.cast<Map<String, dynamic>>();
    } else {
      throw Exception('Ошибка загрузки allOrders: ${response.body}');
    }
  }
}
