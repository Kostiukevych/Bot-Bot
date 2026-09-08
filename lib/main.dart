import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

import 'screens/dashboard_screen.dart';
import 'services/secure_storage_service.dart';

/// Модель данных критического сбоя
class AppCrashInfo {
  final String error;
  final String stackTrace;
  final DateTime timestamp;
  final String stage;

  AppCrashInfo({
    required this.error,
    required this.stackTrace,
    required this.stage,
  }) : timestamp = DateTime.now();

  String get fullDiagnosticReport => '''
=====================================================
BINANCE TESTNET TRADER // CRASH DIAGNOSTIC REPORT
=====================================================
Время сбоя: ${timestamp.toIso8601String()}
Этап: $stage
Ошибка: $error

--- STACK TRACE ---
$stackTrace
=====================================================
''';
}

/// Глобальный нотификатор аварий для показа CrashScreen без падения ОС
final ValueNotifier<AppCrashInfo?> globalCrashNotifier = ValueNotifier<AppCrashInfo?>(null);

void main() {
  runZonedGuarded<Future<void>>(() async {
    // 1. СТРОГО ПЕРВЫМ ДЕЛОМ: Инициализация биндингов Flutter Engine
    // Любые вызовы платформенных каналов (Keystore, Background Service, Network)
    // до завершения этой строки вызывают фатальный крах на реальном Android!
    WidgetsFlutterBinding.ensureInitialized();

    // 2. Перехват ошибок рендеринга Flutter Framework
    FlutterError.onError = (FlutterErrorDetails details) {
      FlutterError.presentError(details);
      _handleFatalError(
        details.exceptionAsString(),
        details.stack?.toString() ?? 'No stack trace available',
        stage: 'Flutter Framework UI Error',
      );
    };

    // 3. Перехват асинхронных ошибок на уровне движка платформы
    PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
      _handleFatalError(
        error.toString(),
        stack.toString(),
        stage: 'PlatformDispatcher Unhandled Async Error',
      );
      return true; // Предотвращаем аварийное завершение процесса ОС
    };

    // 4. Кастомный строитель виджета ошибки вместо "красного экрана смерти"
    ErrorWidget.builder = (FlutterErrorDetails details) {
      return Material(
        color: const Color(0xFF0A1628),
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Center(
            child: SingleChildScrollView(
              child: Text(
                'КРИТИЧЕСКИЙ СБОЙ ВИДЖЕТА:\n${details.exceptionAsString()}',
                style: const TextStyle(color: Color(0xFFFF5252), fontFamily: 'monospace'),
              ),
            ),
          ),
        ),
      );
    };

    // 5. Безопасная предварительная самодиагностика Keystore / flutter_secure_storage
    // Если на телефоне сброшен ключ Keystore или аппаратный сбой биометрии —
    // не позволяем приложению упасть, а фиксируем статус для пользователя.
    try {
      final storage = SecureStorageService();
      // Легковесное асинхронное чтение для проверки доступности Keystore
      await storage.getActiveProfileName().timeout(
        const Duration(seconds: 3),
        onTimeout: () => 'Default Profile (Timeout)',
      );
    } catch (e, stack) {
      debugPrint('ВНИМАНИЕ: Предупреждение при инициализации Keystore: $e');
      // Не роняем приложение при старте, сохраняем диагностику
    }

    // 6. Запуск корневого приложения
    runApp(const CrashShieldApp());
  }, (Object error, StackTrace stack) {
    // Перехват любых ошибок, ускользнувших из Zone
    _handleFatalError(
      error.toString(),
      stack.toString(),
      stage: 'Zone Root Guard (Top-Level Uncaught Exception)',
    );
  });
}

void _handleFatalError(String error, String stack, {required String stage}) {
  debugPrint('🚨 [CRASH SHIELD] $stage: $error\n$stack');
  if (globalCrashNotifier.value == null) {
    globalCrashNotifier.value = AppCrashInfo(
      error: error,
      stackTrace: stack,
      stage: stage,
    );
  }
}

/// Корневой контейнер с поддержкой самодиагностики и аварийного HUD-экрана
class CrashShieldApp extends StatelessWidget {
  const CrashShieldApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<AppCrashInfo?>(
      valueListenable: globalCrashNotifier,
      builder: (context, crashInfo, child) {
        return MaterialApp(
          title: 'Binance Testnet Trader',
          debugShowCheckedModeBanner: false,
          theme: ThemeData.dark().copyWith(
            scaffoldBackgroundColor: const Color(0xFF0A1628),
            colorScheme: const ColorScheme.dark(
              primary: Color(0xFF00D4FF),
              secondary: Color(0xFFFF8A65),
              surface: Color(0xFF0D2340),
            ),
          ),
          home: crashInfo != null
              ? CrashDiagnosticScreen(crashInfo: crashInfo)
              : const DashboardScreen(),
        );
      },
    );
  }
}

/// Полноэкранный самодиагностирующийся виджет с копированием полного стектрейса
class CrashDiagnosticScreen extends StatefulWidget {
  final AppCrashInfo crashInfo;

  const CrashDiagnosticScreen({
    Key? key,
    required this.crashInfo,
  }) : super(key: key);

  @override
  State<CrashDiagnosticScreen> createState() => _CrashDiagnosticScreenState();
}

class _CrashDiagnosticScreenState extends State<CrashDiagnosticScreen> {
  bool _isCopied = false;

  Future<void> _copyToClipboard() async {
    await Clipboard.setData(
      ClipboardData(text: widget.crashInfo.fullDiagnosticReport),
    );
    setState(() => _isCopied = true);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          backgroundColor: Color(0xFF00D4FF),
          content: Text(
            '✓ ПОЛНЫЙ ДИАГНОСТИЧЕСКИЙ ОТЧЕТ СКОПИРОВАН В БУФЕР ОБМЕНА',
            style: TextStyle(color: Colors.black, fontWeight: FontWeight.bold),
          ),
          duration: Duration(seconds: 4),
        ),
      );
    }
  }

  void _restartApp() {
    globalCrashNotifier.value = null;
  }

  Future<void> _clearKeystoreAndRestart() async {
    try {
      final storage = SecureStorageService();
      await storage.clearAllData();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            backgroundColor: Color(0xFFFF8A65),
            content: Text('Данные Keystore очищены. Перезапуск...'),
          ),
        );
      }
    } catch (_) {}
    _restartApp();
  }

  @override
  Widget build(BuildContext context) {
    final info = widget.crashInfo;

    return Scaffold(
      backgroundColor: const Color(0xFF0A1628),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // HUD Header с неоновым свечением
              Container(
                padding: const EdgeInsets.all(16.0),
                decoration: BoxDecoration(
                  color: const Color(0xFF1E0A14),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFFFF5252), width: 1.5),
                  boxShadow: [
                    BoxShadow(
                      color: const Color(0xFFFF5252).withOpacity(0.3),
                      blurRadius: 16,
                      spreadRadius: 2,
                    ),
                  ],
                ),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFF5252).withOpacity(0.2),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(
                        Icons.warning_amber_rounded,
                        color: Color(0xFFFF5252),
                        size: 32,
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'КРИТИЧЕСКИЙ СБОЙ // CRASH SHIELD',
                            style: GoogleFonts.rajdhani(
                              color: const Color(0xFFFF5252),
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                              letterSpacing: 1.2,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            'Сбой был изолирован без краша системы Android',
                            style: TextStyle(
                              color: Colors.white.withOpacity(0.7),
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 12),

              // Этап и время
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(
                  color: const Color(0xFF0D2340),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: const Color(0xFF00D4FF).withOpacity(0.4)),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'ЭТАП: ${info.stage}',
                      style: GoogleFonts.shareTechMono(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      '${info.timestamp.hour.toString().padLeft(2, '0')}:${info.timestamp.minute.toString().padLeft(2, '0')}:${info.timestamp.second.toString().padLeft(2, '0')}',
                      style: GoogleFonts.shareTechMono(
                        color: Colors.white70,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 12),

              // Текст ошибки
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF141F32),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: const Color(0xFFFF8A65).withOpacity(0.6)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'ИСТОЧНИК И ТЕКСТ ИСКЛЮЧЕНИЯ:',
                      style: TextStyle(
                        color: Color(0xFFFF8A65),
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                    const SizedBox(height: 6),
                    SelectableText(
                      info.error,
                      style: GoogleFonts.shareTechMono(
                        color: Colors.white,
                        fontSize: 13,
                        height: 1.3,
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 12),

              // Контейнер с полным стектрейсом
              Expanded(
                child: Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF07101E),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.white12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'ПОЛНЫЙ STACK TRACE ДЛЯ АНАЛИЗА:',
                            style: GoogleFonts.shareTechMono(
                              color: Colors.white54,
                              fontSize: 11,
                            ),
                          ),
                          Text(
                            '${info.stackTrace.split('\n').length} строк',
                            style: const TextStyle(color: Colors.white30, fontSize: 10),
                          ),
                        ],
                      ),
                      const Divider(color: Colors.white12, height: 12),
                      Expanded(
                        child: SingleChildScrollView(
                          scrollDirection: Axis.vertical,
                          child: SingleChildScrollView(
                            scrollDirection: Axis.horizontal,
                            child: SelectableText(
                              info.stackTrace.isNotEmpty
                                  ? info.stackTrace
                                  : 'Стектрейс не сформирован платформой.',
                              style: GoogleFonts.shareTechMono(
                                color: const Color(0xFF80D8FF),
                                fontSize: 11,
                                height: 1.25,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(height: 14),

              // Кнопки управления и копирования
              Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Кнопка Скопировать ошибку
                  ElevatedButton.icon(
                    onPressed: _copyToClipboard,
                    icon: Icon(
                      _isCopied ? Icons.check : Icons.copy,
                      color: Colors.black,
                      size: 20,
                    ),
                    label: Text(
                      _isCopied
                          ? 'ОТЧЕТ СКОПИРОВАН В БУФЕР'
                          : 'СКОПИРОВАТЬ ПОЛНУЮ ОШИБКУ',
                      style: const TextStyle(
                        color: Colors.black,
                        fontWeight: FontWeight.bold,
                        fontSize: 14,
                        letterSpacing: 1.0,
                      ),
                    ),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF00D4FF),
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      elevation: 6,
                    ),
                  ),

                  const SizedBox(height: 8),

                  Row(
                    children: [
                      // Кнопка Перезапустить
                      Expanded(
                        flex: 2,
                        child: OutlinedButton.icon(
                          onPressed: _restartApp,
                          icon: const Icon(Icons.refresh, color: Color(0xFF00D4FF), size: 18),
                          label: const Text(
                            'ПЕРЕЗАПУСТИТЬ',
                            style: TextStyle(
                              color: Color(0xFF00D4FF),
                              fontWeight: FontWeight.bold,
                              fontSize: 12,
                            ),
                          ),
                          style: OutlinedButton.styleFrom(
                            side: const BorderSide(color: Color(0xFF00D4FF)),
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      // Кнопка Сбросить Keystore
                      Expanded(
                        flex: 2,
                        child: OutlinedButton.icon(
                          onPressed: _clearKeystoreAndRestart,
                          icon: const Icon(Icons.delete_sweep, color: Color(0xFFFF8A65), size: 18),
                          label: const Text(
                            'СБРОСИТЬ KEYSTORE',
                            style: TextStyle(
                              color: Color(0xFFFF8A65),
                              fontWeight: FontWeight.bold,
                              fontSize: 12,
                            ),
                          ),
                          style: OutlinedButton.styleFrom(
                            side: const BorderSide(color: Color(0xFFFF8A65)),
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
