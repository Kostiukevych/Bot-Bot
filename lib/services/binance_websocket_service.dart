import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../models/market_models.dart';

/// Сервис WebSocket подключения к Binance Spot Testnet (wss://testnet.binance.vision/ws)
class BinanceWebSocketService {
  static const String wsBaseUrl = 'wss://testnet.binance.vision/ws';

  WebSocketChannel? _channel;
  StreamSubscription? _subscription;
  String? _currentSymbol;

  final _tickerController = StreamController<TickerData>.broadcast();
  Stream<TickerData> get tickerStream => _tickerController.stream;

  final _errorController = StreamController<String>.broadcast();
  Stream<String> get errorStream => _errorController.stream;

  String? _lastError;
  String? get lastError => _lastError;

  bool _isConnected = false;
  bool get isConnected => _isConnected;

  /// Подписка на поток тикера: <symbol>@ticker
  void subscribeToTicker(String symbol) {
    final lowerSymbol = symbol.toLowerCase().trim();
    if (_currentSymbol == lowerSymbol && _isConnected) return;

    disconnect();

    _currentSymbol = lowerSymbol;
    _lastError = null;
    final wsUrl = '$wsBaseUrl/${lowerSymbol}@ticker';

    try {
      _channel = WebSocketChannel.connect(Uri.parse(wsUrl));
      _isConnected = true;

      _subscription = _channel?.stream.listen(
        (message) {
          try {
            final Map<String, dynamic> data = jsonDecode(message.toString());
            // Проверяем наличие полей 'c' (current price) и 's' (symbol)
            if (data.containsKey('c') && data.containsKey('s')) {
              final ticker = TickerData.fromWs(data);
              _tickerController.add(ticker);
            }
          } catch (parseError) {
            _lastError = 'Ошибка парсинга WS тикера: $parseError';
            _errorController.add(_lastError!);
          }
        },
        onError: (err) {
          _isConnected = false;
          _lastError = 'Сбой WebSocket ($lowerSymbol@ticker): $err';
          _errorController.add(_lastError!);
          _scheduleReconnect();
        },
        onDone: () {
          _isConnected = false;
        },
        cancelOnError: false,
      );
    } catch (e) {
      _isConnected = false;
      _lastError = 'Ошибка подключения к WebSocket: $e';
      _errorController.add(_lastError!);
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    Timer(const Duration(seconds: 3), () {
      if (_currentSymbol != null && !_isConnected) {
        subscribeToTicker(_currentSymbol!);
      }
    });
  }

  /// Отключение от WebSocket
  void disconnect() {
    _subscription?.cancel();
    _subscription = null;
    _channel?.sink.close();
    _channel = null;
    _isConnected = false;
  }

  void dispose() {
    disconnect();
    _tickerController.close();
  }
}
