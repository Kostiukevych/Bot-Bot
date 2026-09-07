import 'dart:async';
import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:google_fonts/google_fonts.dart';

import '../models/api_credentials.dart';
import '../models/market_models.dart';
import '../models/trade_history.dart';
import '../services/binance_auth_service.dart';
import '../services/binance_market_service.dart';
import '../services/binance_websocket_service.dart';
import '../services/order_execution_service.dart';
import '../services/secure_storage_service.dart';
import '../services/trading_strategy_service.dart';
import '../widgets/balance_card.dart';
import '../widgets/bot_control_card.dart';
import '../widgets/market_analysis_card.dart';
import '../widgets/open_orders_card.dart';
import '../widgets/pair_selector_card.dart';
import '../widgets/position_size_card.dart';
import 'api_settings_screen.dart';
import 'history_screen.dart';

/// ГЛАВНЫЙ ЭКРАН ТЕРМИНАЛА (HUD DASHBOARD)
/// 
/// ПОЛНОСТЬЮ БЕЗ ЗАХАРДКОЖЕННЫХ ДЕМО-ДАННЫХ:
/// - Баланс Spot USDT загружается строго из GET /api/v3/account
/// - Торговые пары загружаются из GET /api/v3/exchangeInfo
/// - Котировки приходят в реальном времени через Binance WebSocket
/// - Индикаторы (RSI, EMA, MACD, BB, Vol) рассчитываются из реальных свечей klines
/// - Открытые ордера загружаются из GET /api/v3/openOrders
/// - Все карточки имеют явные состояния загрузки, ошибок Binance API и отсутствия ключей
/// - Глобальный HUD статус-индикатор подключения к Binance Testnet (🟢/🔴/🟡)
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({Key? key}) : super(key: key);

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final SecureStorageService _storageService = SecureStorageService();
  final BinanceAuthService _authService = BinanceAuthService();
  final BinanceMarketService _marketService = BinanceMarketService();
  final BinanceWebSocketService _wsService = BinanceWebSocketService();
  final TradingStrategyService _strategyService = TradingStrategyService();
  final OrderExecutionService _orderService = OrderExecutionService();

  ApiCredentials? _credentials;

  // Статус общего подключения к бирже:
  // 'connected' (зелёный), 'connecting' (жёлтый), 'error' (красный), 'no_keys' (серый/жёлтый)
  String _connectionStatus = 'connecting';
  String _connectionStatusText = 'ПОДКЛЮЧЕНИЕ К TESTNET...';

  // 1. Состояние баланса (null = нет данных / не загружено)
  double? _totalUsdt;
  double? _freeUsdt;
  double? _lockedUsdt;
  final List<FlSpot> _balanceHistory = [];
  bool _isLoadingBalance = false;
  String? _balanceError;

  // 2. Состояние торговых пар и тикера
  List<TradingPair> _pairs = [];
  TradingPair? _selectedPair;
  TickerData? _tickerData;
  StreamSubscription? _tickerSub;
  bool _isLoadingPairs = false;
  String? _pairsError;

  // 3. Состояние технического анализа и стратегии
  StrategyRiskConfig _strategyConfig = const StrategyRiskConfig();
  SignalData? _currentSignal;
  StreamSubscription? _signalSub;
  bool _isAnalyzingMarket = false;
  String? _marketAnalysisError;

  // 4. Состояние размера позиции
  double _usdtPositionAmount = 0.0;
  double _baseAssetQty = 0.0;

  // 5. Открытые ордера
  List<OpenOrder> _openOrders = [];
  bool _isLoadingOrders = false;
  String? _ordersError;

  @override
  void initState() {
    super.initState();
    _initTerminal();
  }

  @override
  void dispose() {
    _tickerSub?.cancel();
    _signalSub?.cancel();
    _strategyService.dispose();
    _wsService.dispose();
    super.dispose();
  }

  /// Полная инициализация терминала
  Future<void> _initTerminal() async {
    await _loadCredentials();
    await Future.wait([
      _loadPairs(),
      _refreshBalanceAndOrders(),
    ]);
  }

  Future<void> _loadCredentials() async {
    final creds = await _storageService.getCredentials();
    setState(() {
      _credentials = creds;
      if (creds == null || !creds.isValid) {
        _connectionStatus = 'no_keys';
        _connectionStatusText = 'API-КЛЮЧИ НЕ НАСТРОЕНЫ';
      }
    });
  }

  /// Загрузка торговых пар из GET /api/v3/exchangeInfo
  Future<void> _loadPairs() async {
    setState(() {
      _isLoadingPairs = true;
      _pairsError = null;
    });

    try {
      final list = await _marketService.getTradingPairs();
      setState(() {
        _pairs = list;
        _isLoadingPairs = false;
        if (list.isNotEmpty && _selectedPair == null) {
          // Ищем BTCUSDT или берём первую пару из exchangeInfo
          _selectedPair = list.firstWhere(
            (p) => p.symbol == 'BTCUSDT',
            orElse: () => list.first,
          );
        }
      });

      if (_selectedPair != null) {
        _onPairSelected(_selectedPair!);
      }
    } catch (e) {
      setState(() {
        _isLoadingPairs = false;
        _pairsError = e.toString().replaceAll('Exception: ', '');
      });
    }
  }

  /// Обработка выбора пары
  void _onPairSelected(TradingPair pair) {
    setState(() {
      _selectedPair = pair;
      _tickerData = null;
      _currentSignal = null;
      _marketAnalysisError = null;
    });

    _subscribeToPairTicker(pair.symbol);
    _runMarketAnalysis(pair.symbol);
  }

  /// Подписка на тикер через WebSocket
  void _subscribeToPairTicker(String symbol) {
    _tickerSub?.cancel();
    _wsService.subscribeToTicker(symbol);
    _tickerSub = _wsService.tickerStream.listen(
      (data) {
        if (data.symbol.toUpperCase() == symbol.toUpperCase()) {
          setState(() {
            _tickerData = data;
            if (_credentials != null && _credentials!.isValid) {
              _connectionStatus = 'connected';
              _connectionStatusText = 'TESTNET ОНЛАЙН (LIVE WS)';
            }
          });
        }
      },
      onError: (err) {
        setState(() {
          _connectionStatus = 'error';
          _connectionStatusText = 'СБОЙ WS СОЕДИНЕНИЯ';
        });
      },
    );
  }

  /// Запуск расчёта индикаторов из реальных свечей klines
  Future<void> _runMarketAnalysis(String symbol) async {
    setState(() {
      _isAnalyzingMarket = true;
      _marketAnalysisError = null;
    });

    try {
      final signal = await _strategyService.fetchAndAnalyze(
        symbol: symbol,
        interval: _strategyConfig.interval,
      );
      setState(() {
        _currentSignal = signal;
        _isAnalyzingMarket = false;
      });

      // Запускаем периодический опрос klines
      _strategyService.start(
        symbol: symbol,
        interval: const Duration(seconds: 10),
        callback: (sig) {
          if (mounted && _selectedPair?.symbol == sig.symbol) {
            setState(() {
              _currentSignal = sig;
            });
          }
        },
      );
    } catch (e) {
      setState(() {
        _isAnalyzingMarket = false;
        _marketAnalysisError = e.toString().replaceAll('Exception: ', '');
      });
    }
  }

  /// Загрузка баланса аккаунта и открытых ордеров
  Future<void> _refreshBalanceAndOrders() async {
    if (_credentials == null || !_credentials!.isValid) {
      setState(() {
        _totalUsdt = null;
        _freeUsdt = null;
        _lockedUsdt = null;
        _openOrders = [];
        _connectionStatus = 'no_keys';
        _connectionStatusText = 'API-КЛЮЧИ НЕ НАСТРОЕНЫ';
      });
      return;
    }

    setState(() {
      _isLoadingBalance = true;
      _isLoadingOrders = true;
      _balanceError = null;
      _ordersError = null;
      _connectionStatus = 'connecting';
      _connectionStatusText = 'СИНХРОНИЗАЦИЯ С BINANCE...';
    });

    // 1. Запрос баланса: GET /api/v3/account
    try {
      final accInfo = await _authService.getAccountInfo(
        apiKey: _credentials!.apiKey,
        secretKey: _credentials!.secretKey,
        isTestnet: _credentials!.isTestnet,
      );

      final usdt = accInfo.balances.firstWhere(
        (b) => b.asset.toUpperCase() == 'USDT',
        orElse: () => const AssetBalance(asset: 'USDT', free: 0.0, locked: 0.0),
      );

      setState(() {
        _freeUsdt = usdt.free;
        _lockedUsdt = usdt.locked;
        _totalUsdt = usdt.total;
        _isLoadingBalance = false;
        _connectionStatus = 'connected';
        _connectionStatusText = 'TESTNET ПОДКЛЮЧЕН';

        _balanceHistory.add(FlSpot(_balanceHistory.length.toDouble(), usdt.total));
        if (_balanceHistory.length > 20) _balanceHistory.removeAt(0);
      });
    } catch (e) {
      setState(() {
        _isLoadingBalance = false;
        _balanceError = e.toString().replaceAll('Exception: ', '');
        _connectionStatus = 'error';
        _connectionStatusText = 'ОШИБКА АВТОРИЗАЦИИ BINANCE';
      });
    }

    // 2. Запрос открытых ордеров: GET /api/v3/openOrders
    try {
      final orders = await _marketService.getOpenOrders(
        apiKey: _credentials!.apiKey,
        secretKey: _credentials!.secretKey,
      );
      setState(() {
        _openOrders = orders;
        _isLoadingOrders = false;
      });
    } catch (e) {
      setState(() {
        _isLoadingOrders = false;
        _ordersError = e.toString().replaceAll('Exception: ', '');
      });
    }
  }

  Future<void> _cancelSingleOrder(OpenOrder order) async {
    if (_credentials != null && _credentials!.isValid) {
      try {
        final ok = await _marketService.cancelOrder(
          apiKey: _credentials!.apiKey,
          secretKey: _credentials!.secretKey,
          symbol: order.symbol,
          orderId: order.orderId,
        );
        if (ok) {
          _showSnack('Ордер #${order.orderId} успешно отменен');
          await _refreshBalanceAndOrders();
        }
      } catch (e) {
        _showSnack('Ошибка отмены ордера: $e', isError: true);
      }
    }
  }

  void _showSnack(String text, {bool isError = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: isError ? const Color(0xFF381015) : const Color(0xFF072733),
        content: Text(
          text,
          style: GoogleFonts.rajdhani(color: Colors.white, fontWeight: FontWeight.w600),
        ),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A1628),
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF0A1628), Color(0xFF0D2340), Color(0xFF081220)],
          ),
        ),
        child: SafeArea(
          child: Stack(
            children: [
              const Positioned.fill(child: CustomPaint(painter: HudGridBackgroundPainter())),

              Column(
                children: [
                  _buildTopBar(),
                  _buildConnectionStatusBar(),

                  Expanded(
                    child: RefreshIndicator(
                      color: const Color(0xFF00D4FF),
                      backgroundColor: const Color(0xFF0A182C),
                      onRefresh: () async {
                        await _loadCredentials();
                        await Future.wait([
                          _loadPairs(),
                          _refreshBalanceAndOrders(),
                          if (_selectedPair != null) _runMarketAnalysis(_selectedPair!.symbol),
                        ]);
                      },
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            // 1. КАРТОЧКА БАЛАНСА (БЕЗ ХАРДКОДА)
                            BalanceCard(
                              totalUsdt: _totalUsdt,
                              freeUsdt: _freeUsdt,
                              lockedUsdt: _lockedUsdt,
                              balanceHistory: _balanceHistory,
                              hasCredentials: _credentials != null && _credentials!.isValid,
                              isLoading: _isLoadingBalance,
                              errorMessage: _balanceError,
                              onRefresh: _refreshBalanceAndOrders,
                              onOpenSettings: () {
                                Navigator.push(
                                  context,
                                  MaterialPageRoute(builder: (_) => const ApiSettingsScreen()),
                                ).then((_) => _initTerminal());
                              },
                            ),
                            const SizedBox(height: 14),

                            // 2. КАРТОЧКА ВЫБОРА ПАРЫ (БЕЗ ХАРДКОДА)
                            PairSelectorCard(
                              pairs: _pairs,
                              selectedPair: _selectedPair,
                              tickerData: _tickerData,
                              isLoading: _isLoadingPairs,
                              errorMessage: _pairsError,
                              onRefresh: _loadPairs,
                              onPairSelected: _onPairSelected,
                            ),
                            const SizedBox(height: 14),

                            // 3. КАРТОЧКА АНАЛИЗА РЫНКА // ИНДИКАТОРЫ И СТРАТЕГИЯ (БЕЗ ХАРДКОДА)
                            MarketAnalysisCard(
                              signal: _currentSignal,
                              isAnalyzing: _isAnalyzingMarket,
                              errorMessage: _marketAnalysisError,
                              config: _strategyConfig,
                              onRetry: () {
                                if (_selectedPair != null) {
                                  _runMarketAnalysis(_selectedPair!.symbol);
                                }
                              },
                              onConfigChanged: (newCfg) {
                                setState(() {
                                  _strategyConfig = newCfg;
                                  _strategyService.updateConfig(newCfg);
                                });
                                if (_selectedPair != null) {
                                  _runMarketAnalysis(_selectedPair!.symbol);
                                }
                              },
                            ),
                            const SizedBox(height: 14),

                            // 4. КАРТОЧКА РАЗМЕРА ПОЗИЦИИ (БЕЗ ХАРДКОДА)
                            PositionSizeCard(
                              freeUsdt: _freeUsdt,
                              currentPrice: _tickerData?.lastPrice,
                              selectedPair: _selectedPair,
                              onPositionChanged: (amount, qty) {
                                setState(() {
                                  _usdtPositionAmount = amount;
                                  _baseAssetQty = qty;
                                });
                              },
                            ),
                            const SizedBox(height: 14),

                            // 5. КАРТОЧКА УПРАВЛЕНИЯ АВТО-БОТОМ
                            BotControlCard(
                              selectedPair: _selectedPair,
                              usdtPositionAmount: _usdtPositionAmount,
                              strategyConfig: _strategyConfig,
                              onRefreshNeeded: _refreshBalanceAndOrders,
                            ),
                            const SizedBox(height: 14),

                            // 6. КАРТОЧКА ОТКРЫТЫХ ОРДЕРОВ (БЕЗ ХАРДКОДА)
                            OpenOrdersCard(
                              orders: _openOrders,
                              isLoading: _isLoadingOrders,
                              onRefresh: _refreshBalanceAndOrders,
                              onCancelOrder: _cancelSingleOrder,
                            ),
                            const SizedBox(height: 32),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Верхняя панель приложения
  Widget _buildTopBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF0A1628).withOpacity(0.85),
        border: const Border(bottom: BorderSide(color: Color(0x3300D4FF), width: 1.5)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: const Color(0x1A00D4FF),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0xFF00D4FF)),
                ),
                child: const Icon(Icons.speed_rounded, color: Color(0xFF00D4FF), size: 20),
              ),
              const SizedBox(width: 10),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'HUD DASHBOARD',
                    style: GoogleFonts.orbitron(
                      color: Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.2,
                    ),
                  ),
                  Text(
                    'BINANCE SPOT TESTNET TERMINAL',
                    style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 11),
                  ),
                ],
              ),
            ],
          ),
          Row(
            children: [
              IconButton(
                icon: const Icon(Icons.history_rounded, color: Color(0xFF00D4FF)),
                tooltip: 'История сделок // Лог',
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const HistoryScreen()),
                  ).then((_) => _initTerminal());
                },
              ),
              IconButton(
                icon: const Icon(Icons.settings_outlined, color: Color(0xFF00D4FF)),
                tooltip: 'Настройки API',
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const ApiSettingsScreen()),
                  ).then((_) => _initTerminal());
                },
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Глобальный статус-бар подключения к Testnet/API
  Widget _buildConnectionStatusBar() {
    Color statusColor;
    IconData statusIcon;

    switch (_connectionStatus) {
      case 'connected':
        statusColor = const Color(0xFF00E676);
        statusIcon = Icons.check_circle;
        break;
      case 'connecting':
        statusColor = const Color(0xFFFFB300);
        statusIcon = Icons.sync;
        break;
      case 'error':
        statusColor = const Color(0xFFFF5252);
        statusIcon = Icons.error_outline;
        break;
      case 'no_keys':
      default:
        statusColor = const Color(0xFFFFB300);
        statusIcon = Icons.warning_amber_rounded;
        break;
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: statusColor.withOpacity(0.1),
        border: Border(bottom: BorderSide(color: statusColor.withOpacity(0.3))),
      ),
      child: Row(
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: statusColor,
              boxShadow: [
                BoxShadow(
                  color: statusColor.withOpacity(0.6),
                  blurRadius: 6,
                  spreadRadius: 1,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Icon(statusIcon, color: statusColor, size: 14),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              _connectionStatusText,
              style: GoogleFonts.orbitron(
                color: statusColor,
                fontSize: 10,
                fontWeight: FontWeight.bold,
                letterSpacing: 0.8,
              ),
            ),
          ),
          if (_connectionStatus == 'no_keys')
            GestureDetector(
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const ApiSettingsScreen()),
                ).then((_) => _initTerminal());
              },
              child: Text(
                'НАСТРОИТЬ КЛЮЧИ →',
                style: GoogleFonts.orbitron(
                  color: const Color(0xFF00D4FF),
                  fontSize: 10,
                  fontWeight: FontWeight.bold,
                  decoration: TextDecoration.underline,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
