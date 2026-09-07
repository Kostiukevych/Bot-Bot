import 'dart:convert';

/// Тип ордера
enum OrderSide { buy, sell }

/// Статус сделки
enum TradeStatus { open, closedTakeProfit, closedStopLoss, closedManual, failed }

/// Модель свечи Kline / Candlestick с биржи
class KlineCandle {
  final int openTime;
  final double open;
  final double high;
  final double low;
  final double close;
  final double volume;
  final int closeTime;

  const KlineCandle({
    required this.openTime,
    required this.open,
    required this.high,
    required this.low,
    required this.close,
    required this.volume,
    required this.closeTime,
  });

  factory KlineCandle.fromJson(List<dynamic> list) {
    return KlineCandle(
      openTime: list[0] as int,
      open: double.tryParse(list[1].toString()) ?? 0.0,
      high: double.tryParse(list[2].toString()) ?? 0.0,
      low: double.tryParse(list[3].toString()) ?? 0.0,
      close: double.tryParse(list[4].toString()) ?? 0.0,
      volume: double.tryParse(list[5].toString()) ?? 0.0,
      closeTime: list[6] as int,
    );
  }
}

/// Рассчитанные технические индикаторы
class IndicatorValues {
  final double rsi;
  final double emaFast; // EMA(9)
  final double emaSlow; // EMA(21)
  final double macdLine;
  final double macdSignal;
  final double macdHist;
  final double prevMacdHist;
  final double currentVolume;
  final double avgVolume;
  final double bbUpper;
  final double bbMiddle;
  final double bbLower;

  const IndicatorValues({
    required this.rsi,
    required this.emaFast,
    required this.emaSlow,
    required this.macdLine,
    required this.macdSignal,
    required this.macdHist,
    required this.prevMacdHist,
    required this.currentVolume,
    required this.avgVolume,
    required this.bbUpper,
    required this.bbMiddle,
    required this.bbLower,
  });

  bool get isVolumeAboveAvg => currentVolume > avgVolume;
  bool get isEmaBullish => emaFast > emaSlow;
  bool get isEmaBearish => emaFast < emaSlow;
  bool get isRsiOversold => rsi < 30.0;
  bool get isRsiOverbought => rsi > 70.0;
  bool get isMacdRising => macdHist > prevMacdHist;
  bool get isMacdFalling => macdHist < prevMacdHist;
}

/// Направление торгового сигнала
enum SignalAction { buyLong, sellSpot, hold }

/// Детальный сигнал торговой стратегии
class SignalData {
  final String symbol;
  final double currentPrice;
  final IndicatorValues indicators;
  final SignalAction action;
  final int score; // 0 - 100
  final List<String> matchedConditions;
  final double recommendedStopLoss;
  final double recommendedTakeProfit;
  final DateTime timestamp;

  const SignalData({
    required this.symbol,
    required this.currentPrice,
    required this.indicators,
    required this.action,
    required this.score,
    required this.matchedConditions,
    required this.recommendedStopLoss,
    required this.recommendedTakeProfit,
    required this.timestamp,
  });
}

/// Параметры риск-менеджмента стратегии
class StrategyRiskConfig {
  final String interval; // "1m", "5m", "15m", "1h"
  final double stopLossPercent; // по умолчанию 2.0%
  final double takeProfitPercent; // по умолчанию 4.0%
  final int minScoreThreshold; // по умолчанию 70
  final double maxDepositRiskPercent; // макс. процент депозита в сделке (напр. 25%)

  const StrategyRiskConfig({
    this.interval = "5m",
    this.stopLossPercent = 2.0,
    this.takeProfitPercent = 4.0,
    this.minScoreThreshold = 70,
    this.maxDepositRiskPercent = 25.0,
  });

  StrategyRiskConfig copyWith({
    String? interval,
    double? stopLossPercent,
    double? takeProfitPercent,
    int? minScoreThreshold,
    double? maxDepositRiskPercent,
  }) {
    return StrategyRiskConfig(
      interval: interval ?? this.interval,
      stopLossPercent: stopLossPercent ?? this.stopLossPercent,
      takeProfitPercent: takeProfitPercent ?? this.takeProfitPercent,
      minScoreThreshold: minScoreThreshold ?? this.minScoreThreshold,
      maxDepositRiskPercent: maxDepositRiskPercent ?? this.maxDepositRiskPercent,
    );
  }
}

/// Запись сделки в историю торговли
class TradeRecord {
  final String id;
  final String symbol;
  final OrderSide side;
  final double entryPrice;
  final double quantity;
  final double usdtAmount;
  final double stopLossPrice;
  final double takeProfitPrice;
  final int score;
  final int entryTime;
  double? exitPrice;
  int? exitTime;
  double? realizedPnlUsdt;
  double? realizedPnlPercent;
  TradeStatus status;
  String? exitReason;

  TradeRecord({
    required this.id,
    required this.symbol,
    required this.side,
    required this.entryPrice,
    required this.quantity,
    required this.usdtAmount,
    required this.stopLossPrice,
    required this.takeProfitPrice,
    required this.score,
    required this.entryTime,
    this.exitPrice,
    this.exitTime,
    this.realizedPnlUsdt,
    this.realizedPnlPercent,
    this.status = TradeStatus.open,
    this.exitReason,
  });

  Map<String, dynamic> toJson() => {
    'id': id,
    'symbol': symbol,
    'side': side == OrderSide.buy ? 'BUY' : 'SELL',
    'entryPrice': entryPrice,
    'quantity': quantity,
    'usdtAmount': usdtAmount,
    'stopLossPrice': stopLossPrice,
    'takeProfitPrice': takeProfitPrice,
    'score': score,
    'entryTime': entryTime,
    'exitPrice': exitPrice,
    'exitTime': exitTime,
    'realizedPnlUsdt': realizedPnlUsdt,
    'realizedPnlPercent': realizedPnlPercent,
    'status': status.name,
    'exitReason': exitReason,
  };

  factory TradeRecord.fromJson(Map<String, dynamic> map) => TradeRecord(
    id: map['id'] as String,
    symbol: map['symbol'] as String,
    side: map['side'] == 'BUY' ? OrderSide.buy : OrderSide.sell,
    entryPrice: (map['entryPrice'] as num).toDouble(),
    quantity: (map['quantity'] as num).toDouble(),
    usdtAmount: (map['usdtAmount'] as num).toDouble(),
    stopLossPrice: (map['stopLossPrice'] as num).toDouble(),
    takeProfitPrice: (map['takeProfitPrice'] as num).toDouble(),
    score: (map['score'] as num).toInt(),
    entryTime: (map['entryTime'] as num).toInt(),
    exitPrice: (map['exitPrice'] as num?)?.toDouble(),
    exitTime: (map['exitTime'] as num?)?.toInt(),
    realizedPnlUsdt: (map['realizedPnlUsdt'] as num?)?.toDouble(),
    realizedPnlPercent: (map['realizedPnlPercent'] as num?)?.toDouble(),
    status: TradeStatus.values.firstWhere(
      (e) => e.name == map['status'],
      orElse: () => TradeStatus.open,
    ),
    exitReason: map['exitReason'] as String?,
  );
}
