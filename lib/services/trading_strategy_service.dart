import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'package:http/http.dart' as http;
import '../models/trade_history.dart';

/// Модуль технического анализа и генерации торговых сигналов Binance Spot Testnet
class TradingStrategyService {
  static const String testnetBaseUrl = 'https://testnet.binance.vision';

  final http.Client _client;
  Timer? _analysisTimer;
  bool _isRunning = false;

  StrategyRiskConfig _config;
  StrategyRiskConfig get config => _config;

  final _signalStreamController = StreamController<SignalData>.broadcast();
  Stream<SignalData> get signalStream => _signalStreamController.stream;

  final _errorStreamController = StreamController<String>.broadcast();
  Stream<String> get errorStream => _errorStreamController.stream;

  Function(SignalData)? onSignal;
  Function(String)? onError;

  TradingStrategyService({
    http.Client? client,
    StrategyRiskConfig? initialConfig,
  })  : _client = client ?? http.Client(),
        _config = initialConfig ?? const StrategyRiskConfig();

  bool get isRunning => _isRunning;

  void updateConfig(StrategyRiskConfig newConfig) {
    _config = newConfig;
  }

  /// Запуск периодического анализа для выбранной пары (по умолчанию каждые 5 сек)
  void start({
    required String symbol,
    Duration interval = const Duration(seconds: 5),
    Function(SignalData)? callback,
  }) {
    stop();
    _isRunning = true;
    onSignal = callback;

    // Первичный запуск без ожидания
    _analyzeSymbol(symbol);

    _analysisTimer = Timer.periodic(interval, (_) {
      if (_isRunning) {
        _analyzeSymbol(symbol);
      }
    });
  }

  void stop() {
    _isRunning = false;
    _analysisTimer?.cancel();
    _analysisTimer = null;
  }

  /// Запрос klines свечей с биржи Binance: GET /api/v3/klines
  Future<List<KlineCandle>> fetchKlines({
    required String symbol,
    String? interval,
    int limit = 100,
  }) async {
    final useInterval = interval ?? _config.interval;
    final uri = Uri.parse(
      '$testnetBaseUrl/api/v3/klines?symbol=${symbol.toUpperCase().trim()}&interval=$useInterval&limit=$limit',
    );

    final res = await _client.get(uri).timeout(const Duration(seconds: 8));
    if (res.statusCode == 200) {
      final List<dynamic> jsonList = jsonDecode(res.body);
      return jsonList.map((e) => KlineCandle.fromJson(e as List<dynamic>)).toList();
    } else {
      String errorMsg = 'HTTP ${res.statusCode}';
      try {
        final err = jsonDecode(res.body);
        if (err is Map && err.containsKey('msg')) {
          errorMsg = '[${err['code']}] ${err['msg']}';
        }
      } catch (_) {}
      throw Exception('Ошибка загрузки klines: $errorMsg');
    }
  }

  /// Запрос klines свечей с биржи и мгновенный расчёт индикаторов/сигнала
  Future<SignalData> fetchAndAnalyze({
    required String symbol,
    String? interval,
    int limit = 60,
  }) async {
    final candles = await fetchKlines(symbol: symbol, interval: interval, limit: limit);
    if (candles.length < 20) {
      throw Exception('Недостаточно свечей klines от Binance для анализа (${candles.length}/20)');
    }

    final indicators = calculateIndicators(candles);
    final currentPrice = candles.last.close;

    final signal = evaluateSignal(
      symbol: symbol,
      currentPrice: currentPrice,
      indicators: indicators,
      candles: candles,
    );

    _signalStreamController.add(signal);
    onSignal?.call(signal);
    return signal;
  }

  /// Расчет индикаторов и проверка условий входа/выхода
  Future<void> _analyzeSymbol(String symbol) async {
    try {
      await fetchAndAnalyze(symbol: symbol);
    } catch (e) {
      final msg = 'Ошибка анализа klines ($symbol): $e';
      _errorStreamController.add(msg);
      onError?.call(msg);
    }
  }

  /// Расчет технических индикаторов: RSI(14), EMA(9), EMA(21), MACD(12,26,9), Volume Avg(20), BB(20,2)
  IndicatorValues calculateIndicators(List<KlineCandle> candles) {
    final closes = candles.map((c) => c.close).toList();
    final volumes = candles.map((c) => c.volume).toList();

    // 1. EMA(9) и EMA(21)
    final ema9List = _calculateEmaSeries(closes, 9);
    final ema21List = _calculateEmaSeries(closes, 21);
    final currentEma9 = ema9List.isNotEmpty ? ema9List.last : closes.last;
    final currentEma21 = ema21List.isNotEmpty ? ema21List.last : closes.last;

    // 2. RSI(14)
    final rsi = _calculateRsi(closes, 14);

    // 3. MACD(12, 26, 9)
    final ema12 = _calculateEmaSeries(closes, 12);
    final ema26 = _calculateEmaSeries(closes, 26);
    final macdSeries = <double>[];
    final minLen = math.min(ema12.length, ema26.length);
    for (int i = 0; i < minLen; i++) {
      macdSeries.add(ema12[i] - ema26[i]);
    }
    final signalSeries = _calculateEmaSeries(macdSeries, 9);
    final currentMacd = macdSeries.isNotEmpty ? macdSeries.last : 0.0;
    final currentSignal = signalSeries.isNotEmpty ? signalSeries.last : 0.0;
    final currentHist = currentMacd - currentSignal;
    final prevHist = (macdSeries.length > 1 && signalSeries.length > 1)
        ? (macdSeries[macdSeries.length - 2] - signalSeries[signalSeries.length - 2])
        : currentHist;

    // 4. Volume: средний за 20 периодов
    final currentVol = volumes.isNotEmpty ? volumes.last : 0.0;
    double avgVol = 0.0;
    final volWindow = volumes.length >= 20 ? volumes.sublist(volumes.length - 20) : volumes;
    if (volWindow.isNotEmpty) {
      avgVol = volWindow.reduce((a, b) => a + b) / volWindow.length;
    }

    // 5. Bollinger Bands (20, 2)
    final bbWindow = closes.length >= 20 ? closes.sublist(closes.length - 20) : closes;
    double bbMiddle = 0.0;
    double bbStd = 0.0;
    if (bbWindow.isNotEmpty) {
      bbMiddle = bbWindow.reduce((a, b) => a + b) / bbWindow.length;
      final sumSquares = bbWindow.map((x) => math.pow(x - bbMiddle, 2)).reduce((a, b) => a + b);
      bbStd = math.sqrt(sumSquares / bbWindow.length);
    }
    final bbUpper = bbMiddle + (bbStd * 2.0);
    final bbLower = bbMiddle - (bbStd * 2.0);

    return IndicatorValues(
      rsi: rsi,
      emaFast: currentEma9,
      emaSlow: currentEma21,
      macdLine: currentMacd,
      macdSignal: currentSignal,
      macdHist: currentHist,
      prevMacdHist: prevHist,
      currentVolume: currentVol,
      avgVolume: avgVol,
      bbUpper: bbUpper,
      bbMiddle: bbMiddle,
      bbLower: bbLower,
    );
  }

  /// Оценка условий и расчет "веса" (score 0-100)
  SignalData evaluateSignal({
    required String symbol,
    required double currentPrice,
    required IndicatorValues indicators,
    required List<KlineCandle> candles,
  }) {
    int longScore = 0;
    int shortScore = 0;
    final List<String> matchedLong = [];
    final List<String> matchedShort = [];

    // Правило 1: RSI (вес 25)
    if (indicators.rsi <= _config.rsiOversold) {
      longScore += 25;
      matchedLong.add('RSI перепродан (${indicators.rsi.toStringAsFixed(1)} <= ${_config.rsiOversold})');
    } else if (indicators.rsi >= _config.rsiOverbought) {
      shortScore += 25;
      matchedShort.add('RSI перекуплен (${indicators.rsi.toStringAsFixed(1)} >= ${_config.rsiOverbought})');
    }

    // Правило 2: EMA тренд (вес 25)
    if (indicators.isEmaBullish) {
      longScore += 25;
      matchedLong.add('EMA Бычий кросс (EMA9: ${indicators.emaFast.toStringAsFixed(1)} > EMA21: ${indicators.emaSlow.toStringAsFixed(1)})');
    } else {
      shortScore += 25;
      matchedShort.add('EMA Медвежий тренд (EMA9 < EMA21)');
    }

    // Правило 3: MACD гистограмма (вес 20)
    if (indicators.isMacdRising) {
      longScore += 20;
      matchedLong.add('MACD гистограмма растёт (${indicators.macdHist.toStringAsFixed(2)})');
    } else {
      shortScore += 20;
      matchedShort.add('MACD гистограмма снижается');
    }

    // Правило 4: Объем выше среднего (вес 15)
    if (indicators.isVolumeAboveAvg) {
      longScore += 15;
      matchedLong.add('Объем свечи выше среднего на 20 периодов');
    }

    // Правило 5: Отскок от нижней границы Боллинджера (вес 15)
    if (indicators.isNearLowerBand) {
      longScore += 15;
      matchedLong.add('Цена коснулась нижней полосы Боллинджера (${indicators.bbLower.toStringAsFixed(1)})');
    } else if (indicators.isNearUpperBand) {
      shortScore += 15;
      matchedShort.add('Цена у верхней полосы Боллинджера (${indicators.bbUpper.toStringAsFixed(1)})');
    }

    // Расчет уровней TP и SL
    final slPrice = currentPrice * (1.0 - (_config.stopLossPercent / 100.0));
    final tpPrice = currentPrice * (1.0 + (_config.takeProfitPercent / 100.0));

    SignalAction action = SignalAction.hold;
    int finalScore = 0;
    List<String> conditions = [];

    if (longScore >= _config.minScoreThreshold) {
      action = SignalAction.buyLong;
      finalScore = longScore;
      conditions = matchedLong;
    } else if (shortScore >= _config.minScoreThreshold) {
      action = SignalAction.sellSpot;
      finalScore = shortScore;
      conditions = matchedShort;
    } else {
      action = SignalAction.hold;
      finalScore = math.max(longScore, shortScore);
      conditions = longScore >= shortScore ? matchedLong : matchedShort;
    }

    return SignalData(
      symbol: symbol,
      action: action,
      score: finalScore,
      currentPrice: currentPrice,
      recommendedStopLoss: slPrice,
      recommendedTakeProfit: tpPrice,
      matchedConditions: conditions,
      indicators: indicators,
      timestamp: DateTime.now(),
    );
  }

  List<double> _calculateEmaSeries(List<double> prices, int period) {
    if (prices.length < period) return [];
    final List<double> result = [];
    final double k = 2.0 / (period + 1.0);

    double sum = 0.0;
    for (int i = 0; i < period; i++) {
      sum += prices[i];
    }
    double ema = sum / period;

    for (int i = 0; i < period - 1; i++) {
      result.add(prices[i]);
    }
    result.add(ema);

    for (int i = period; i < prices.length; i++) {
      ema = (prices[i] * k) + (ema * (1.0 - k));
      result.add(ema);
    }

    return result;
  }

  double _calculateRsi(List<double> prices, int period) {
    if (prices.length <= period) return 50.0;

    double gainSum = 0.0;
    double lossSum = 0.0;

    for (int i = 1; i <= period; i++) {
      final diff = prices[i] - prices[i - 1];
      if (diff >= 0) {
        gainSum += diff;
      } else {
        lossSum += diff.abs();
      }
    }

    double avgGain = gainSum / period;
    double avgLoss = lossSum / period;

    for (int i = period + 1; i < prices.length; i++) {
      final diff = prices[i] - prices[i - 1];
      if (diff >= 0) {
        avgGain = (avgGain * (period - 1) + diff) / period;
        avgLoss = (avgLoss * (period - 1)) / period;
      } else {
        avgGain = (avgGain * (period - 1)) / period;
        avgLoss = (avgLoss * (period - 1) + diff.abs()) / period;
      }
    }

    if (avgLoss == 0) return 100.0;
    final rs = avgGain / avgLoss;
    return 100.0 - (100.0 / (1.0 + rs));
  }

  void dispose() {
    stop();
    _signalStreamController.close();
    _errorStreamController.close();
  }
}
