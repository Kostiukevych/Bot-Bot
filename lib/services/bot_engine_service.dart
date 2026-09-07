import 'dart:async';
import '../models/api_credentials.dart';
import '../models/market_models.dart';
import '../models/trade_history.dart';
import 'binance_auth_service.dart';
import 'binance_market_service.dart';
import 'order_execution_service.dart';
import 'secure_storage_service.dart';
import 'trading_strategy_service.dart';

/// Статусы жизненного цикла движка алгоритмической торговли
enum EngineStatus {
  stopped,
  starting,
  running,
  stopping,
  error;

  String get title {
    switch (this) {
      case EngineStatus.stopped:
        return 'БОТ ОСТАНОВЛЕН';
      case EngineStatus.starting:
        return 'ЗАПУСК И ИНИЦИАЛИЗАЦИЯ...';
      case EngineStatus.running:
        return 'БОТ АКТИВЕН // АВТО-ТОРГОВЛЯ';
      case EngineStatus.stopping:
        return 'ОСТАНОВКА ЦИКЛА...';
      case EngineStatus.error:
        return 'ОШИБКА АВТОРИЗАЦИИ / КРИТИЧЕСКИЙ СБОЙ';
    }
  }

  int get colorValue {
    switch (this) {
      case EngineStatus.stopped:
        return 0xFFFF5252;
      case EngineStatus.starting:
        return 0xFFFFB300;
      case EngineStatus.running:
        return 0xFF00E676;
      case EngineStatus.stopping:
        return 0xFFFF8A65;
      case EngineStatus.error:
        return 0xFFFF1744;
    }
  }
}

/// ЯДРО АВТОМАТИЧЕСКОЙ ТОРГОВЛИ (BotEngineService)
/// 
/// Обеспечивает непрерывный цикл технического анализа, автоматического
/// открытия сделок по сигналам стратегии без участия человека и
/// постоянного контроля открытых позиций с авто-выходом по Take-Profit / Stop-Loss.
class BotEngineService {
  // Синглтон для единой точки управления ботом в приложении
  static final BotEngineService _instance = BotEngineService._internal();
  factory BotEngineService() => _instance;
  BotEngineService._internal();

  final SecureStorageService _storageService = SecureStorageService();
  final BinanceAuthService _authService = BinanceAuthService();
  final BinanceMarketService _marketService = BinanceMarketService();
  final TradingStrategyService _strategyService = TradingStrategyService();
  final OrderExecutionService _orderService = OrderExecutionService();

  EngineStatus _status = EngineStatus.stopped;
  EngineStatus get status => _status;

  DateTime? _startedAt;
  DateTime? get startedAt => _startedAt;

  Timer? _loopTimer;
  bool _isIterationBusy = false;

  // Конфигурация запущенного бота
  String? _symbol;
  double _positionUsdtAmount = 100.0;
  TradingPair? _pairInfo;
  ApiCredentials? _credentials;
  StrategyRiskConfig _strategyConfig = const StrategyRiskConfig();

  // Стримы статуса и логов действий
  final _statusController = StreamController<EngineStatus>.broadcast();
  Stream<EngineStatus> get statusStream => _statusController.stream;

  final _logController = StreamController<String>.broadcast();
  Stream<String> get logStream => _logController.stream;

  final List<String> _recentLogs = [];
  List<String> get recentLogs => List.unmodifiable(_recentLogs);

  // Геттеры для UI
  String? get currentSymbol => _symbol;
  double get currentPositionAmount => _positionUsdtAmount;
  Duration get uptime => _startedAt != null && _status == EngineStatus.running
      ? DateTime.now().difference(_startedAt!)
      : Duration.zero;

  void _setStatus(EngineStatus newStatus) {
    _status = newStatus;
    _statusController.add(newStatus);
  }

  void _addLog(String message) {
    final now = DateTime.now();
    final timeStr =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    final fullLog = '[$timeStr] $message';
    _recentLogs.add(fullLog);
    if (_recentLogs.length > 200) {
      _recentLogs.removeAt(0);
    }
    _logController.add(fullLog);
  }

  /// ЗАПУСК АВТОТОРГОВОГО ДВИЖКА
  /// 
  /// 1. Проверяет API-ключи через ping/accountInfo.
  /// 2. Проверяет настройки торговой пары и размер позиции.
  /// 3. Запускает периодический цикл анализа рынка и авто-ордеров.
  Future<bool> start({
    required String symbol,
    required double positionUsdtAmount,
    TradingPair? pairInfo,
    StrategyRiskConfig? strategyConfig,
    Duration loopInterval = const Duration(seconds: 15),
  }) async {
    if (_status == EngineStatus.running || _status == EngineStatus.starting) {
      _addLog('Предупреждение: бот уже находится в процессе работы.');
      return false;
    }

    _setStatus(EngineStatus.starting);
    _addLog('Инициализация авто-бота для пары $symbol...');

    // 1. Проверка API-ключей
    final creds = await _storageService.getCredentials();
    if (creds == null || !creds.isValid) {
      _setStatus(EngineStatus.error);
      _addLog('ОШИБКА: API Key или Secret Key не настроены! Перейдите в настройки API.');
      return false;
    }

    _credentials = creds;
    _symbol = symbol.toUpperCase().trim();
    _positionUsdtAmount = positionUsdtAmount;
    _pairInfo = pairInfo;
    if (strategyConfig != null) {
      _strategyConfig = strategyConfig;
      _strategyService.updateConfig(strategyConfig);
    }

    _addLog('Проверка подключения к Binance Spot Testnet...');
    try {
      final accInfo = await _authService.getAccountInfo(
        apiKey: creds.apiKey,
        secretKey: creds.secretKey,
        isTestnet: creds.isTestnet,
      );

      // Проверка баланса USDT
      final usdtBal = accInfo.balances.firstWhere(
        (b) => b.asset.toUpperCase() == 'USDT',
        orElse: () => const AssetBalance(asset: 'USDT', free: 0.0, locked: 0.0),
      );

      _addLog('Авторизация успешна. Доступно: ${usdtBal.free.toStringAsFixed(2)} USDT');

      if (positionUsdtAmount <= 0) {
        _setStatus(EngineStatus.error);
        _addLog('ОШИБКА: Размер позиции ($positionUsdtAmount USDT) некорректен.');
        return false;
      }

      if (usdtBal.free < positionUsdtAmount && !_orderService.hasOpenPosition(_symbol!)) {
        _addLog('Внимание: свободный баланс (${usdtBal.free} USDT) меньше размера позиции ($positionUsdtAmount USDT). Бот продолжит мониторинг.');
      }
    } catch (e) {
      final errStr = e.toString().toLowerCase();
      if (errStr.contains('api-key') || errStr.contains('-2014') || errStr.contains('-2015') || errStr.contains('signature')) {
        _setStatus(EngineStatus.error);
        _addLog('КРИТИЧЕСКАЯ ОШИБКА АВТОРИЗАЦИИ: Неверные API-ключи: $e');
        return false;
      }
      _addLog('Внимание при проверке сети: $e. Продолжаем запуск цикла с повторными попытками.');
    }

    _startedAt = DateTime.now();
    _setStatus(EngineStatus.running);
    _addLog('✅ БОТ УСПЕШНО ЗАПУЩЕН // Интервал цикла: ${loopInterval.inSeconds}с');
    _addLog('Мониторинг пары $_symbol // Сумма ордера: $_positionUsdtAmount USDT');

    // Немедленная первая итерация анализа
    _executeBotCycle();

    // Запуск бесконечного периодического цикла
    _loopTimer?.cancel();
    _loopTimer = Timer.periodic(loopInterval, (_) {
      if (_status == EngineStatus.running) {
        _executeBotCycle();
      }
    });

    return true;
  }

  /// ОСТАНОВКА БОТА
  /// 
  /// Немедленно отменяет цикл опроса, переводит статус в stopped,
  /// сохраняет уже открытые позиции как есть (НЕ закрывает их автоматически).
  Future<void> stop() async {
    if (_status == EngineStatus.stopped) return;

    _setStatus(EngineStatus.stopping);
    _addLog('Остановка авто-бота...');

    _loopTimer?.cancel();
    _loopTimer = null;
    _startedAt = null;

    final openCount = _orderService.openPositions.length;
    _setStatus(EngineStatus.stopped);
    _addLog('⏹ БОТ ОСТАНОВЛЕН // Цикл анализа прерван');
    if (openCount > 0) {
      _addLog('Внимание: Сохранено $openCount открытых позиций без закрытия (по требованию безопасности).');
    }
  }

  /// Экстренно закрыть все позиции (отдельное действие по запросу пользователя)
  Future<void> emergencyCloseAll() async {
    if (_credentials == null || !_credentials!.isValid) return;

    _addLog('🚨 ЭКСТРЕННОЕ ЗАКРЫТИЕ ВСЕХ ПОЗИЦИЙ...');
    final positions = Map<String, TradeRecord>.from(_orderService.openPositions);

    for (final entry in positions.entries) {
      final sym = entry.key;
      _addLog('Экстренный сброс позиции $sym...');
      try {
        final ticker = await _marketService.getTicker24h(sym);
        final price = ticker.lastPrice > 0 ? ticker.lastPrice : entry.value.entryPrice;
        await _orderService.closePosition(
          apiKey: _credentials!.apiKey,
          secretKey: _credentials!.secretKey,
          symbol: sym,
          reason: 'Экстренное ручное закрытие всех позиций',
          currentPrice: price,
          pairInfo: _pairInfo,
        );
        _addLog('Позиция $sym успешно закрыта.');
      } catch (e) {
        _addLog('Ошибка при экстренном закрытии $sym: $e');
      }
    }
  }

  /// ИТЕРАЦИЯ ЦИКЛА АВТО-ТОРГОВЛИ
  Future<void> _executeBotCycle() async {
    if (_isIterationBusy || _status != EngineStatus.running) return;
    _isIterationBusy = true;

    final sym = _symbol;
    final creds = _credentials;

    if (sym == null || creds == null || !creds.isValid) {
      _isIterationBusy = false;
      return;
    }

    try {
      _addLog('Анализ рынка для $sym...');

      // 1. Получение свежих Klines свечей
      final candles = await _strategyService.fetchKlines(
        symbol: sym,
        interval: _strategyConfig.interval,
        limit: 60,
      );

      if (candles.isEmpty || candles.length < 25) {
        _addLog('Предупреждение: недостаточно данных klines от биржи. Ожидание...');
        _isIterationBusy = false;
        return;
      }

      final currentPrice = candles.last.close;
      final indicators = _strategyService.calculateIndicators(candles);

      // 2. Оценка сигнала стратегии
      final signal = _strategyService.evaluateSignal(
        symbol: sym,
        currentPrice: currentPrice,
        indicators: indicators,
        candles: candles,
      );

      _addLog('Цена: $currentPrice USDT | RSI: ${indicators.rsi.toStringAsFixed(1)} | Score: ${signal.score}/100 [${signal.action.name.toUpperCase()}]');

      // 3. ПРОВЕРКА ОТКРЫТОЙ ПОЗИЦИИ (Take-Profit / Stop-Loss)
      final existingPos = _orderService.getOpenPosition(sym);
      if (existingPos != null) {
        final entryP = existingPos.entryPrice;
        final currentPnlPercent = ((currentPrice - entryP) / entryP) * 100.0;
        final pnlSign = currentPnlPercent >= 0 ? '+' : '';

        _addLog('Открыта позиция $sym [Вход: $entryP USDT | PnL: $pnlSign${currentPnlPercent.toStringAsFixed(2)}%]');

        // Проверка Take-Profit
        if (currentPrice >= existingPos.takeProfitPrice) {
          _addLog('🎯 TAKE-PROFIT ДОСТИГНУТ ($currentPrice >= ${existingPos.takeProfitPrice})! Автоматическое закрытие...');
          final success = await _orderService.closePosition(
            apiKey: creds.apiKey,
            secretKey: creds.secretKey,
            symbol: sym,
            reason: 'Автоматический Take-Profit (+${currentPnlPercent.toStringAsFixed(2)}%)',
            currentPrice: currentPrice,
            pairInfo: _pairInfo,
          );
          if (success) {
            _addLog('✅ Позиция $sym успешно зафиксирована с прибылью!');
          }
        }
        // Проверка Stop-Loss
        else if (currentPrice <= existingPos.stopLossPrice) {
          _addLog('🛑 STOP-LOSS СРАБОТАЛ ($currentPrice <= ${existingPos.stopLossPrice})! Защитное закрытие...');
          final success = await _orderService.closePosition(
            apiKey: creds.apiKey,
            secretKey: creds.secretKey,
            symbol: sym,
            reason: 'Автоматический Stop-Loss (${currentPnlPercent.toStringAsFixed(2)}%)',
            currentPrice: currentPrice,
            pairInfo: _pairInfo,
          );
          if (success) {
            _addLog('Защитный выход из позиции $sym завершен.');
          }
        }
        // Проверка сигнала на выход по стратегии
        else if (signal.action == SignalAction.sellSpot && signal.score >= _strategyConfig.minScoreThreshold) {
          _addLog('Сигнал стратегии на закрытие (SELL/SHORT score: ${signal.score}). Фиксация...');
          await _orderService.closePosition(
            apiKey: creds.apiKey,
            secretKey: creds.secretKey,
            symbol: sym,
            reason: 'Сигнал стратегии SELL SPOT (Score: ${signal.score})',
            currentPrice: currentPrice,
            pairInfo: _pairInfo,
          );
        }
      }
      // 4. ЕСЛИ ПОЗИЦИИ НЕТ — ПРОВЕРКА УСЛОВИЙ ВХОДА
      else {
        if (signal.action == SignalAction.buyLong && signal.score >= _strategyConfig.minScoreThreshold) {
          _addLog('🔥 ОБНАРУЖЕН СИГНАЛ LONG (Score: ${signal.score} >= ${_strategyConfig.minScoreThreshold})!');
          _addLog('Условия: ${signal.matchedConditions.join(', ')}');
          _addLog('🚀 АВТО-ВХОД БЕЗ ПОДТВЕРЖДЕНИЯ: Покупка $sym на $_positionUsdtAmount USDT...');

          final trade = await _orderService.executeSignalTrade(
            apiKey: creds.apiKey,
            secretKey: creds.secretKey,
            signal: signal,
            usdtAmount: _positionUsdtAmount,
            pairInfo: _pairInfo,
          );

          if (trade != null) {
            _addLog('✅ СДЕЛКА ИСПОЛНЕНА: $sym x ${trade.quantity} по цене ${trade.entryPrice} USDT');
            _addLog('Установлен SL: ${trade.stopLossPrice.toStringAsFixed(2)} | TP: ${trade.takeProfitPrice.toStringAsFixed(2)}');
          } else {
            _addLog('Не удалось открыть сделку (проверьте LOT_SIZE или баланс биржи).');
          }
        } else {
          _addLog('Ожидание паттерна входа (Score ${signal.score}/${_strategyConfig.minScoreThreshold})');
        }
      }
    } catch (e) {
      final errStr = e.toString().toLowerCase();
      if (errStr.contains('api-key') || errStr.contains('-2014') || errStr.contains('-2015') || errStr.contains('signature')) {
        _setStatus(EngineStatus.error);
        _addLog('КРИТИЧЕСКАЯ ОШИБКА АВТОРИЗАЦИИ: $e. Остановка бота.');
        _loopTimer?.cancel();
        _loopTimer = null;
      } else {
        // Ошибка сети или временная заминка биржи — логируем и продолжаем на следующей итерации
        _addLog('Сетевая заминка: $e. Повтор на следующей итерации...');
      }
    } finally {
      _isIterationBusy = false;
    }
  }
}
