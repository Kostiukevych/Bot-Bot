/// Модель торговой пары Binance из exchangeInfo
class TradingPair {
  final String symbol;
  final String baseAsset;
  final String quoteAsset;
  final String status;
  final double minQty;
  final double maxQty;
  final double stepSize;
  final double minNotional;

  const TradingPair({
    required this.symbol,
    required this.baseAsset,
    required this.quoteAsset,
    required this.status,
    required this.minQty,
    required this.maxQty,
    required this.stepSize,
    required this.minNotional,
  });

  factory TradingPair.fromJson(Map<String, dynamic> json) {
    double minQty = 0.00001;
    double maxQty = 9000000.0;
    double stepSize = 0.00001;
    double minNotional = 10.0;

    final filters = json['filters'] as List<dynamic>? ?? [];
    for (final f in filters) {
      final filterType = f['filterType'] as String? ?? '';
      if (filterType == 'LOT_SIZE') {
        minQty = double.tryParse(f['minQty']?.toString() ?? '') ?? minQty;
        maxQty = double.tryParse(f['maxQty']?.toString() ?? '') ?? maxQty;
        stepSize = double.tryParse(f['stepSize']?.toString() ?? '') ?? stepSize;
      } else if (filterType == 'MIN_NOTIONAL' || filterType == 'NOTIONAL') {
        minNotional = double.tryParse(f['minNotional']?.toString() ?? f['notional']?.toString() ?? '') ?? minNotional;
      }
    }

    return TradingPair(
      symbol: json['symbol'] as String? ?? '',
      baseAsset: json['baseAsset'] as String? ?? '',
      quoteAsset: json['quoteAsset'] as String? ?? '',
      status: json['status'] as String? ?? '',
      minQty: minQty,
      maxQty: maxQty,
      stepSize: stepSize,
      minNotional: minNotional,
    );
  }
}

/// Текущие данные тикера (цена, 24ч изменение)
class TickerData {
  final String symbol;
  final double lastPrice;
  final double priceChangePercent;
  final double highPrice;
  final double lowPrice;
  final double volume;

  const TickerData({
    required this.symbol,
    required this.lastPrice,
    required this.priceChangePercent,
    this.highPrice = 0.0,
    this.lowPrice = 0.0,
    this.volume = 0.0,
  });

  bool get isPositive => priceChangePercent >= 0;

  factory TickerData.fromWs(Map<String, dynamic> json) {
    return TickerData(
      symbol: json['s'] as String? ?? '',
      lastPrice: double.tryParse(json['c']?.toString() ?? '0') ?? 0.0,
      priceChangePercent: double.tryParse(json['P']?.toString() ?? '0') ?? 0.0,
      highPrice: double.tryParse(json['h']?.toString() ?? '0') ?? 0.0,
      lowPrice: double.tryParse(json['l']?.toString() ?? '0') ?? 0.0,
      volume: double.tryParse(json['v']?.toString() ?? '0') ?? 0.0,
    );
  }
}

/// Модель открытого ордера
class OpenOrder {
  final int orderId;
  final String symbol;
  final String side; // BUY or SELL
  final String type; // LIMIT, MARKET
  final double price;
  final double origQty;
  final double executedQty;
  final String status;
  final int time;

  const OpenOrder({
    required this.orderId,
    required this.symbol,
    required this.side,
    required this.type,
    required this.price,
    required this.origQty,
    required this.executedQty,
    required this.status,
    required this.time,
  });

  bool get isBuy => side.toUpperCase() == 'BUY';

  factory OpenOrder.fromJson(Map<String, dynamic> json) {
    return OpenOrder(
      orderId: json['orderId'] as int? ?? 0,
      symbol: json['symbol'] as String? ?? '',
      side: json['side'] as String? ?? 'BUY',
      type: json['type'] as String? ?? 'LIMIT',
      price: double.tryParse(json['price']?.toString() ?? '0') ?? 0.0,
      origQty: double.tryParse(json['origQty']?.toString() ?? '0') ?? 0.0,
      executedQty: double.tryParse(json['executedQty']?.toString() ?? '0') ?? 0.0,
      status: json['status'] as String? ?? 'NEW',
      time: json['time'] as int? ?? DateTime.now().millisecondsSinceEpoch,
    );
  }
}

/// Состояние торгового бота
enum BotStatus {
  stopped('БОТ ОСТАНОВЛЕН', 0xFFFF5252),
  marketAnalysis('Анализ рынка...', 0xFF00D4FF),
  waitingSignal('Ожидание сигнала', 0xFFFFB300),
  positionLong('Позиция открыта LONG', 0xFF00E676),
  positionShort('Позиция открыта SHORT', 0xFFFF8A65);

  final String title;
  final int colorValue;
  const BotStatus(this.title, this.colorValue);
}
