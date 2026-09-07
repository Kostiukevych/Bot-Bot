import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../models/api_credentials.dart';

/// Сервис для безопасного хранения API-ключей в Android Keystore через flutter_secure_storage.
/// Никаких открытых данных в SharedPreferences.
class SecureStorageService {
  static const String _keyActiveProfile = 'active_profile_name';
  static const String _keyProfilesList = 'saved_profiles_list';
  static const String _prefixProfile = 'profile_cred_';

  // Префиксы для настроек стратегии и истории (для общего сброса)
  static const String _prefixStrategy = 'strategy_conf_';
  static const String _prefixHistory = 'trade_history_';

  final FlutterSecureStorage _storage;

  SecureStorageService({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(
                encryptedSharedPreferences: true,
                resetOnError: true,
              ),
              iOptions: IOSOptions(
                accessibility: KeychainAccessibility.first_unlock,
              ),
            );

  /// Сохранение учетных данных профиля
  Future<void> saveCredentials(ApiCredentials credentials) async {
    final profileKey = '$_prefixProfile${credentials.profileName}';
    final jsonStr = jsonEncode(credentials.toJson());
    await _storage.write(key: profileKey, value: jsonStr);

    // Добавляем имя профиля в список всех сохраненных профилей
    final profiles = await getAllProfileNames();
    if (!profiles.contains(credentials.profileName)) {
      profiles.add(credentials.profileName);
      await _storage.write(
        key: _keyProfilesList,
        value: jsonEncode(profiles),
      );
    }

    // Делаем этот профиль активным
    await setActiveProfileName(credentials.profileName);
  }

  /// Получение активного имени профиля
  Future<String> getActiveProfileName() async {
    final active = await _storage.read(key: _keyActiveProfile);
    return active ?? 'Default Profile';
  }

  /// Установка активного профиля
  Future<void> setActiveProfileName(String profileName) async {
    await _storage.write(key: _keyActiveProfile, value: profileName);
  }

  /// Получение списка всех сохраненных профилей
  Future<List<String>> getAllProfileNames() async {
    final jsonStr = await _storage.read(key: _keyProfilesList);
    if (jsonStr == null || jsonStr.isEmpty) {
      return ['Default Profile'];
    }
    try {
      final List<dynamic> list = jsonDecode(jsonStr);
      final names = list.map((e) => e.toString()).toList();
      if (!names.contains('Default Profile')) {
        names.insert(0, 'Default Profile');
      }
      return names;
    } catch (_) {
      return ['Default Profile'];
    }
  }

  /// Загрузка учетных данных для указанного профиля (или активного)
  Future<ApiCredentials?> getCredentials({String? profileName}) async {
    final targetProfile = profileName ?? await getActiveProfileName();
    final profileKey = '$_prefixProfile$targetProfile';
    final jsonStr = await _storage.read(key: profileKey);

    if (jsonStr == null || jsonStr.isEmpty) {
      return null;
    }

    try {
      final Map<String, dynamic> map = jsonDecode(jsonStr);
      return ApiCredentials.fromJson(map);
    } catch (e) {
      return null;
    }
  }

  /// Удаление только учетных данных текущего профиля
  Future<void> deleteCredentials({String? profileName}) async {
    final targetProfile = profileName ?? await getActiveProfileName();
    final profileKey = '$_prefixProfile$targetProfile';
    await _storage.delete(key: profileKey);

    // Обновляем список профилей
    final profiles = await getAllProfileNames();
    profiles.remove(targetProfile);
    if (profiles.isEmpty) {
      profiles.add('Default Profile');
    }
    await _storage.write(key: _keyProfilesList, value: jsonEncode(profiles));
    await setActiveProfileName(profiles.first);
  }

  /// Общий сброс приложения: удаляет все ключи, настройки стратегии и историю
  Future<void> clearAllData() async {
    await _storage.deleteAll();
  }

  /// Сохранение дополнительных параметров стратегии (для демонстрации полного сброса)
  Future<void> saveStrategySetting(String key, String value) async {
    await _storage.write(key: '$_prefixStrategy$key', value: value);
  }

  /// Сохранение записи истории (для демонстрации полного сброса)
  Future<void> logTradeHistory(String record) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await _storage.write(key: '$_prefixHistory$now', value: record);
  }
}
