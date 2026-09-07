import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import '../models/market_models.dart';
import '../models/trade_history.dart';
import 'secure_storage_service.dart';
import 'trade_history_storage_service.dart';

/// Результат исполнения ордера
class OrderExecutionResult {
  final bool isSuccess;
  final String? orderId;
  final String? symbol;
  final String? clientOrderId;
  final double executedQty;
  final double price;
  final String? errorMessage;
  final int? binanceErrorCode;

  const OrderExecutionResult({
    required this.isSuccess,
    this.orderId,
    this.symbol,
    this.clientOrderId,
    this.executedQty = 0.0,
    this.price = 0.0,
    this.errorMessage,
    this.binanceErrorCode,
  });
}

/// Сервис создания и исполнения ордеров Binance Spot Testnet (POST /api/v3/order)
class OrderExecutionService {
  static const String testnetBaseUrl = 'https://testnet.binance.vision';

  final http.Client _client;
  final SecureStorageService _storageService;
  final TradeHistoryStorageService _historyStorage;

  // Локальная история всех совершенных сделок
  final List<TradeRecord> _history = [];
  List<TradeRecord> get history => List.unmodifiable(_history);

  // Текущие открытые позиции в памяти терминала (максимум одна на пару)
  final Map<String, TradeRecord> _openPositions = {};
  Map<String, TradeRecord> get openPositions => Map.unmodifiable(_openPositions);

  OrderExecutionService({
    http.Client? client,
    SecureStorageService? storageService,
    TradeHistoryStorageService? historyStorage,
  })  : _client = client ?? http.Client(),
        _storageService = storageService ?? SecureStorageService(),
        _historyStorage = historyStorage ?? TradeHistoryStorageService();

  bool hasOpenPosition(String symbol) {
    return _openPositions.containsKey(symbol.toUpperCase().trim());
  }

  TradeRecord? getOpenPosition(String symbol) {
    return _openPositions[symbol.toUpperCase().trim()];
  }

  /// Подпись HMAC SHA256
  String _sign(String queryString, String secretKey) {
    final key = utf8.encode(secretKey.trim());
    final bytes = utf8.encode(queryString);
    final hmac = Hmac(sha256, key);
    return hmac.convert(bytes).toString();
  }

  /// Размещение MARKET или LIMIT ордера на споте Binance Testnet
  Future<OrderExecutionResult> placeOrder({
    required String apiKey,
    required String secretKey,
    required String symbol,
    required OrderSide side,
    required String type, // "MARKET" или "LIMIT"
    required double quantity,
    double? price,
    TradingPair? pairInfo,
  }) async {
    // 1. Валидация фильтров биржи перед отправкой
    if (pairInfo != null) {
      if (quantity < pairInfo.minQty) {
        return OrderExecutionResult(
          isSuccess: false,
          errorMessage: 'Ошибка LOT_SIZE: Объем ($quantity) меньше минимального (${pairInfo.minQty})',
        );
      }
      final estNotional = (price ?? 0.0) > 0 ? (quantity * price!) : 0.0;
      if (price != null && estNotional < pairInfo.minNotional) {
        return OrderExecutionResult(
          isSuccess: false,
          errorMessage: 'Ошибка MIN_NOTIONAL: Сумма ($estNotional USDT) меньше ${pairInfo.minNotional} USDT',
        );
      }
    }

    // 2. Формирование параметров запроса
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final Map<String, String> queryParams = {
      'symbol': symbol.toUpperCase().trim(),
      'side': side == OrderSide.buy ? 'BUY' : 'SELL',
      'type': type.toUpperCase().trim(),
      'quantity': quantity.toStringAsFixed(pairInfo != null ? _getDecimals(pairInfo.stepSize) : 5),
      'timestamp': timestamp.toString(),
      'recvWindow': '5000',
    };

    if (type.toUpperCase() == 'LIMIT') {
      if (price == null || price <= 0) {
        return const OrderExecutionResult(
          isSuccess: false,
          errorMessage: 'Для LIMIT ордера необходимо указать цену выше нуля',
        );
      }
      queryParams['price'] = price.toStringAsFixed(2);
      queryParams['timeInForce'] = 'GTC';
    }

    final queryString = queryParams.entries.map((e) => '${e.key}=${e.value}').join('&');
    final signature = _sign(queryString, secretKey);
    final fullUrl = '$testnetBaseUrl/api/v3/order?$queryString&signature=$signature';

    try {
      final response = await _client.post(
        Uri.parse(fullUrl),
        headers: {
          'X-MBX-APIKEY': apiKey.trim(),
          'Accept': 'application/json',
        },
      ).timeout(const Duration(seconds: 10));

      final Map<String, dynamic> body = jsonDecode(response.body);

      if (response.statusCode == 200) {
        final orderId = body['orderId'].toString();
        final executedQty = double.tryParse(body['executedQty']?.toString() ?? '') ?? quantity;
        final fills = body['fills'] as List<dynamic>?;
        double fillPrice = price ?? 0.0;
        if (fills != null && fills.isNotEmpty) {
          fillPrice = double.tryParse(fills.first['price']?.toString() ?? '') ?? fillPrice;
        }

        return OrderExecutionResult(
          isSuccess: true,
          orderId: orderId,
          symbol: symbol,
          clientOrderId: body['clientOrderId']?.toString(),
          executedQty: executedQty,
          price: fillPrice,
        );
      } else {
        final int code = body['code'] as int? ?? response.statusCode;
        final String msg = body['msg'] as String? ?? 'Неизвестная ошибка Binance API';
        final humanMsg = _parseBinanceError(code, msg);

        return OrderExecutionResult(
          isSuccess: false,
          binanceErrorCode: code,
          errorMessage: humanMsg,
        );
      }
    } catch (e) {
      return OrderExecutionResult(
        isSuccess: false,
        errorMessage: 'Сетевая ошибка при исполнении ордера: $e',
      );
    }
  }

  /// Открытие позиции по сигналу с сохранением Stop-Loss и Take-Profit
  Future<TradeRecord?> executeSignalTrade({
    required String apiKey,
    required String secretKey,
    required SignalData signal,
    required double usdtAmount,
    TradingPair? pairInfo,
  }) async {
    final sym = signal.symbol.toUpperCase().trim();

    // Защита риск-менеджмента: максимум одна открытая позиция на пару
    if (signal.action == SignalAction.buyLong && hasOpenPosition(sym)) {
      return null;
    }

    if (signal.action == SignalAction.buyLong) {
      final targetQty = usdtAmount / signal.currentPrice;
      final result = await placeOrder(
        apiKey: apiKey,
        secretKey: secretKey,
        symbol: sym,
        side: OrderSide.buy,
        type: 'MARKET',
        quantity: targetQty,
        price: signal.currentPrice,
        pairInfo: pairInfo,
      );

      if (result.isSuccess) {
        final entryPrice = result.price > 0 ? result.price : signal.currentPrice;
        final executedQty = result.executedQty > 0 ? result.executedQty : targetQty;

        final trade = TradeRecord(
          id: result.orderId ?? DateTime.now().millisecondsSinceEpoch.toString(),
          symbol: sym,
          side: OrderSide.buy,
          entryPrice: entryPrice,
          quantity: executedQty,
          usdtAmount: entryPrice * executedQty,
          stopLossPrice: signal.recommendedStopLoss,
          takeProfitPrice: signal.recommendedTakeProfit,
          score: signal.score,
          entryTime: DateTime.now().millisecondsSinceEpoch,
          status: TradeStatus.open,
        );

        _openPositions[sym] = trade;
        _history.insert(0, trade);
        await _historyStorage.saveTrade(trade);
        return trade;
      }
    } else if (signal.action == SignalAction.sellSpot) {
      // Для спота SELL = продажа актива/закрытие открытой ранее позиции
      final existingPos = _openPositions[sym];
      if (existingPos != null) {
        await closePosition(
          apiKey: apiKey,
          secretKey: secretKey,
          symbol: sym,
          reason: 'Сигнал на закрытие (SELL/SHORT score: ${signal.score})',
          currentPrice: signal.currentPrice,
          pairInfo: pairInfo,
        );
      }
    }

    return null;
  }

  /// Закрытие позиции по рыночной цене (Take-Profit, Stop-Loss или ручной выход)
  Future<bool> closePosition({
    required String apiKey,
    required String secretKey,
    required String symbol,
    required String reason,
    required double currentPrice,
    TradingPair? pairInfo,
  }) async {
    final sym = symbol.toUpperCase().trim();
    final pos = _openPositions[sym];
    if (pos == null) return false;

    final result = await placeOrder(
      apiKey: apiKey,
      secretKey: secretKey,
      symbol: sym,
      side: OrderSide.sell,
      type: 'MARKET',
      quantity: pos.quantity,
      price: currentPrice,
      pairInfo: pairInfo,
    );

    if (result.isSuccess) {
      final exitP = result.price > 0 ? result.price : currentPrice;
      pos.exitPrice = exitP;
      pos.exitTime = DateTime.now().millisecondsSinceEpoch;
      pos.realizedPnlUsdt = (exitP - pos.entryPrice) * pos.quantity;
      pos.realizedPnlPercent = ((exitP - pos.entryPrice) / pos.entryPrice) * 100.0;
      pos.exitReason = reason;

      if (reason.contains('Take-Profit')) {
        pos.status = TradeStatus.closedTakeProfit;
      } else if (reason.contains('Stop-Loss')) {
        pos.status = TradeStatus.closedStopLoss;
      } else {
        pos.status = TradeStatus.closedManual;
      }

      _openPositions.remove(sym);
      await _historyStorage.saveTrade(pos);
      return true;
    }

    return false;
  }

  /// Проверка условий Take-Profit и Stop-Loss по текущей цене
  Future<void> checkRiskExits({
    required String apiKey,
    required String secretKey,
    required String symbol,
    required double currentPrice,
    TradingPair? pairInfo,
  }) async {
    final sym = symbol.toUpperCase().trim();
    final pos = _openPositions[sym];
    if (pos == null) return;

    // 1. Проверка Take-Profit
    if (currentPrice >= pos.takeProfitPrice) {
      await closePosition(
        apiKey: apiKey,
        secretKey: secretKey,
        symbol: sym,
        reason: 'Take-Profit сработал (+${((currentPrice - pos.entryPrice) / pos.entryPrice * 100).toStringAsFixed(2)}%)',
        currentPrice: currentPrice,
        pairInfo: pairInfo,
      );
    }
    // 2. Проверка Stop-Loss
    else if (currentPrice <= pos.stopLossPrice) {
      await closePosition(
        apiKey: apiKey,
        secretKey: secretKey,
        symbol: sym,
        reason: 'Stop-Loss сработал (${((currentPrice - pos.entryPrice) / pos.entryPrice * 100).toStringAsFixed(2)}%)',
        currentPrice: currentPrice,
        pairInfo: pairInfo,
      );
    }
  }

  String _parseBinanceError(int code, String originalMsg) {
    switch (code) {
      case -2010:
        return 'Недостаточно средств на балансе Spot Testnet для этого ордера (-2010)';
      case -1013:
        return 'Ошибка параметров ордера (LOT_SIZE или MIN_NOTIONAL не пройдены): $originalMsg';
      case -1121:
        return 'Неверная или неподдерживаемая торговая пара ($originalMsg)';
      case -1021:
        return 'Рассинхронизация времени с сервером Binance (Timestamp out of recvWindow)';
      case -2015:
        return 'Неверный API-ключ или отсутствуют права на торговлю на Testnet';
      default:
        return 'Binance [$code]: $originalMsg';
    }
  }

  int _getDecimals(double stepSize) {
    if (stepSize <= 0) return 4;
    final str = stepSize.toString();
    if (str.contains('.')) {
      final parts = str.split('.');
      return parts[1].length;
    }
    return 0;
  }
}
