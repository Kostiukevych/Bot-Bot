import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import '../models/api_credentials.dart';
import '../models/trade_history.dart';
import '../services/binance_market_service.dart';
import '../services/secure_storage_service.dart';
import '../services/trade_history_storage_service.dart';
import 'api_settings_screen.dart';

/// ЭКРАН ИСТОРИИ СДЕЛОК И СВОДНОЙ СТАТИСТИКИ (HUD СТИЛЬ)
class HistoryScreen extends StatefulWidget {
  const HistoryScreen({Key? key}) : super(key: key);

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  final TradeHistoryStorageService _storageService = TradeHistoryStorageService();
  final SecureStorageService _credentialsStorage = SecureStorageService();
  final BinanceMarketService _marketService = BinanceMarketService();

  List<TradeRecord> _allTrades = [];
  List<TradeRecord> _filteredTrades = [];
  TradeSummaryStats _summaryStats = TradeSummaryStats.empty();

  bool _isLoading = true;
  bool _isSyncing = false;

  // Фильтры
  String _selectedSymbolFilter = 'ALL';
  DateTime? _startDateFilter;
  DateTime? _endDateFilter;

  // Список доступных пар для фильтра
  final List<String> _availableSymbols = ['ALL', 'BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT', 'ADAUSDT'];

  @override
  void initState() {
    super.initState();
    _loadHistory();
  }

  Future<void> _loadHistory() async {
    setState(() => _isLoading = true);
    final trades = await _storageService.getAllTrades();
    setState(() {
      _allTrades = trades;
      _applyFilters();
      _isLoading = false;
    });
  }

  void _applyFilters() {
    final filtered = _allTrades.where((t) {
      if (_selectedSymbolFilter != 'ALL' && t.symbol.toUpperCase() != _selectedSymbolFilter.toUpperCase()) {
        return false;
      }
      if (_startDateFilter != null) {
        final d = DateTime.fromMillisecondsSinceEpoch(t.entryTime);
        if (d.isBefore(DateTime(_startDateFilter!.year, _startDateFilter!.month, _startDateFilter!.day))) {
          return false;
        }
      }
      if (_endDateFilter != null) {
        final d = DateTime.fromMillisecondsSinceEpoch(t.entryTime);
        if (d.isAfter(DateTime(_endDateFilter!.year, _endDateFilter!.month, _endDateFilter!.day, 23, 59, 59))) {
          return false;
        }
      }
      return true;
    }).toList();

    setState(() {
      _filteredTrades = filtered;
      _summaryStats = TradeSummaryStats.fromTrades(filtered);
    });
  }

  /// Синхронизация реальной истории исполнений с Binance Spot Testnet (GET /api/v3/myTrades)
  Future<void> _syncWithBinanceApi() async {
    setState(() => _isSyncing = true);
    try {
      final creds = await _credentialsStorage.getCredentials();
      if (creds == null || !creds.isValid) {
        _showSnack('Для синхронизации с биржей укажите API-ключи в настройках', isError: true);
        setState(() => _isSyncing = false);
        return;
      }

      int importedCount = 0;
      final symbolsToQuery = _selectedSymbolFilter == 'ALL'
          ? ['BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT']
          : [_selectedSymbolFilter];

      for (final sym in symbolsToQuery) {
        try {
          final rawTrades = await _marketService.getMyTrades(
            apiKey: creds.apiKey,
            secretKey: creds.secretKey,
            symbol: sym,
            limit: 50,
          );

          // Группировка и формирование реальных записей
          for (final item in rawTrades) {
            final tradeId = 'BINANCE_${item['id']}';
            final exists = _allTrades.any((t) => t.id == tradeId);
            if (exists) continue;

            final price = double.tryParse(item['price']?.toString() ?? '') ?? 0.0;
            final qty = double.tryParse(item['qty']?.toString() ?? '') ?? 0.0;
            final isBuyer = item['isBuyer'] == true;
            final time = item['time'] as int? ?? DateTime.now().millisecondsSinceEpoch;
            final quoteQty = double.tryParse(item['quoteQty']?.toString() ?? '') ?? (price * qty);

            final rec = TradeRecord(
              id: tradeId,
              symbol: sym,
              side: isBuyer ? OrderSide.buy : OrderSide.sell,
              entryPrice: price,
              quantity: qty,
              usdtAmount: quoteQty,
              stopLossPrice: isBuyer ? price * 0.98 : price * 1.02,
              takeProfitPrice: isBuyer ? price * 1.04 : price * 0.96,
              score: 80,
              entryTime: time,
              exitPrice: !isBuyer ? price : null,
              exitTime: !isBuyer ? time : null,
              status: isBuyer ? TradeStatus.open : TradeStatus.closedManual,
              exitReason: isBuyer ? null : 'Биржевое исполнение (Binance Order #${item['orderId']})',
              realizedPnlUsdt: null,
              realizedPnlPercent: null,
            );

            await _storageService.saveTrade(rec);
            importedCount++;
          }
        } catch (_) {
          // Игнорируем ошибки отсутствия сделок по отдельной паре
        }
      }

      await _loadHistory();
      if (importedCount > 0) {
        _showSnack('Успешно синхронизировано новых сделок с биржи: $importedCount');
      } else {
        _showSnack('Новых сделок на бирже Testnet не обнаружено');
      }
    } catch (e) {
      _showSnack('Ошибка синхронизации: $e', isError: true);
    } finally {
      setState(() => _isSyncing = false);
    }
  }

  /// Экспорт отфильтрованной истории в CSV
  void _exportCsv() {
    if (_filteredTrades.isEmpty) {
      _showSnack('Нет сделок для экспорта', isError: true);
      return;
    }

    final csvContent = _storageService.exportToCsv(_filteredTrades);

    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: HudCard(
          borderColor: const Color(0xFF00D4FF),
          glowColor: const Color(0x3300D4FF),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.file_download_outlined, color: Color(0xFF00D4FF), size: 22),
                    const SizedBox(width: 8),
                    Text(
                      'ЭКСПОРТ ИСТОРИИ (CSV)',
                      style: GoogleFonts.orbitron(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  'Сформирован файл отчета (${_filteredTrades.length} записей). Нажмите "Скопировать CSV" для сохранения или вставки в Excel/Google Sheets.',
                  style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 13),
                ),
                const SizedBox(height: 12),
                Container(
                  height: 120,
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF06101E),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: const Color(0x2200D4FF)),
                  ),
                  child: SingleChildScrollView(
                    child: Text(
                      csvContent,
                      style: GoogleFonts.sourceCodePro(color: const Color(0xFF7E9BB8), fontSize: 10),
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx),
                      child: Text('ЗАКРЫТЬ', style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8))),
                    ),
                    const SizedBox(width: 8),
                    HudButton(
                      text: 'СКОПИРОВАТЬ CSV',
                      color: const Color(0xFF00D4FF),
                      isSmall: true,
                      onPressed: () {
                        Clipboard.setData(ClipboardData(text: csvContent));
                        Navigator.pop(ctx);
                        _showSnack('CSV данные скопированы в буфер обмена!');
                      },
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _selectDateRange() async {
    final now = DateTime.now();
    final picked = await showDateRangePicker(
      context: context,
      firstDate: DateTime(now.year - 1),
      lastDate: DateTime(now.year + 1),
      initialDateRange: _startDateFilter != null && _endDateFilter != null
          ? DateTimeRange(start: _startDateFilter!, end: _endDateFilter!)
          : null,
      builder: (context, child) {
        return Theme(
          data: ThemeData.dark().copyWith(
            colorScheme: const ColorScheme.dark(
              primary: Color(0xFF00D4FF),
              onPrimary: Color(0xFF0A1628),
              surface: Color(0xFF0D2340),
              onSurface: Colors.white,
            ),
            dialogBackgroundColor: const Color(0xFF0A1628),
          ),
          child: child!,
        );
      },
    );

    if (picked != null) {
      setState(() {
        _startDateFilter = picked.start;
        _endDateFilter = picked.end;
        _applyFilters();
      });
    }
  }

  void _clearFilters() {
    setState(() {
      _selectedSymbolFilter = 'ALL';
      _startDateFilter = null;
      _endDateFilter = null;
      _applyFilters();
    });
  }

  void _showSnack(String text, {bool isError = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: isError ? const Color(0xFF381015) : const Color(0xFF072733),
        content: Text(
          text,
          style: GoogleFonts.rajdhani(color: Colors.white, fontWeight: FontWeight.w600),
        ),
        duration: const Duration(seconds: 3),
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
                  Expanded(
                    child: _isLoading
                        ? const Center(
                            child: CircularProgressIndicator(color: Color(0xFF00D4FF)),
                          )
                        : RefreshIndicator(
                            color: const Color(0xFF00D4FF),
                            backgroundColor: const Color(0xFF0A182C),
                            onRefresh: _loadHistory,
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  // 1. СВОДНАЯ СТАТИСТИКА
                                  _buildSummaryStatsCard(),
                                  const SizedBox(height: 14),

                                  // 2. ГРАФИК КРИВОЙ КАПИТАЛА (EQUITY CURVE)
                                  _buildEquityCurveCard(),
                                  const SizedBox(height: 14),

                                  // 3. ПАНЕЛЬ ФИЛЬТРОВ
                                  _buildFilterBar(),
                                  const SizedBox(height: 14),

                                  // 4. ТАБЛИЦА / СПИСОК СДЕЛОК
                                  _buildTradesList(),
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

  Widget _buildTopBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF0A1628).withOpacity(0.9),
        border: const Border(bottom: BorderSide(color: Color(0x3300D4FF), width: 1.5)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              IconButton(
                icon: const Icon(Icons.arrow_back_ios_new, color: Color(0xFF00D4FF), size: 18),
                onPressed: () => Navigator.pop(context),
              ),
              const SizedBox(width: 4),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'ИСТОРИЯ СДЕЛОК // ЛОГ',
                    style: GoogleFonts.orbitron(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.1,
                    ),
                  ),
                  Text(
                    'РЕАЛЬНЫЕ ИСПОЛНЕНИЯ SPOT TESTNET',
                    style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 10),
                  ),
                ],
              ),
            ],
          ),
          Row(
            children: [
              // Кнопка синхронизации с биржей по API
              IconButton(
                icon: _isSyncing
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF00D4FF)),
                      )
                    : const Icon(Icons.sync_rounded, color: Color(0xFF00D4FF)),
                tooltip: 'Синхронизировать с Binance API',
                onPressed: _isSyncing ? null : _syncWithBinanceApi,
              ),
              // Кнопка экспорта в CSV
              IconButton(
                icon: const Icon(Icons.file_download_outlined, color: Color(0xFF00D4FF)),
                tooltip: 'Экспорт в CSV',
                onPressed: _exportCsv,
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Карточка сводной статистики: винрейт, суммарный PnL, количество сделок, средняя прибыль/убыток
  Widget _buildSummaryStatsCard() {
    final isPnlPositive = _summaryStats.totalPnlUsdt >= 0;
    final pnlColor = isPnlPositive ? const Color(0xFF00E676) : const Color(0xFFFF5252);

    return HudCard(
      borderColor: const Color(0xFF00D4FF),
      glowColor: const Color(0x1F00D4FF),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.insights, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'СВОДНАЯ СТАТИСТИКА // KPI',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0x1A00D4FF),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: const Color(0x3300D4FF)),
                  ),
                  child: Text(
                    'ВСЕГО: ${_summaryStats.totalTrades}',
                    style: GoogleFonts.orbitron(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Верхний ряд: Винрейт и Суммарный PnL
            Row(
              children: [
                // ВИНРЕЙТ
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: const Color(0xFF071220),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: const Color(0x3300D4FF)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'ВИНРЕЙТ',
                          style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.baseline,
                          textBaseline: TextBaseline.alphabetic,
                          children: [
                            Text(
                              _summaryStats.winRatePercent.toStringAsFixed(1),
                              style: GoogleFonts.orbitron(
                                color: _summaryStats.winRatePercent >= 50
                                    ? const Color(0xFF00E676)
                                    : const Color(0xFFFFB300),
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            Text(
                              '%',
                              style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 12),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'W: ${_summaryStats.winningTrades} / L: ${_summaryStats.losingTrades}',
                          style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 11),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 10),

                // СУММАРНЫЙ PnL
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: const Color(0xFF071220),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: pnlColor.withOpacity(0.4)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'СУММАРНЫЙ PnL',
                          style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.baseline,
                          textBaseline: TextBaseline.alphabetic,
                          children: [
                            Text(
                              '${isPnlPositive ? '+' : ''}${_summaryStats.totalPnlUsdt.toStringAsFixed(2)}',
                              style: GoogleFonts.orbitron(
                                color: pnlColor,
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(width: 4),
                            Text(
                              'USDT',
                              style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '${isPnlPositive ? '+' : ''}${_summaryStats.totalPnlPercent.toStringAsFixed(2)}%',
                          style: GoogleFonts.rajdhani(color: pnlColor, fontSize: 11, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),

            // Нижний ряд: Средняя прибыль, средний убыток, Profit Factor
            Row(
              children: [
                Expanded(
                  child: _buildMetricTile(
                    label: 'СРЕД. ПРИБЫЛЬ',
                    value: '+${_summaryStats.avgProfitUsdt.toStringAsFixed(2)} \$',
                    color: const Color(0xFF00E676),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _buildMetricTile(
                    label: 'СРЕД. УБЫТОК',
                    value: '-${_summaryStats.avgLossUsdt.toStringAsFixed(2)} \$',
                    color: const Color(0xFFFF5252),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _buildMetricTile(
                    label: 'PROFIT FACTOR',
                    value: _summaryStats.profitFactor > 90 ? '∞' : _summaryStats.profitFactor.toStringAsFixed(2),
                    color: const Color(0xFFFF8A65),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetricTile({required String label, required String value, required Color color}) {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: const Color(0xFF071220),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: const Color(0x2200D4FF)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 8)),
          const SizedBox(height: 2),
          Text(value, style: GoogleFonts.rajdhani(color: color, fontSize: 13, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }

  /// График Equity Curve (кривая роста/просадки капитала)
  Widget _buildEquityCurveCard() {
    final closedTrades = _filteredTrades.where((t) => t.status != TradeStatus.open && t.realizedPnlUsdt != null).toList();
    closedTrades.sort((a, b) => (a.exitTime ?? a.entryTime).compareTo(b.exitTime ?? b.entryTime));

    final List<FlSpot> spots = [];
    double cumulative = 0.0;
    spots.add(const FlSpot(0, 0));

    for (int i = 0; i < closedTrades.length; i++) {
      cumulative += closedTrades[i].realizedPnlUsdt!;
      spots.add(FlSpot((i + 1).toDouble(), cumulative));
    }

    final isProfit = cumulative >= 0;
    final curveColor = isProfit ? const Color(0xFF00E676) : const Color(0xFFFF5252);

    return HudCard(
      borderColor: const Color(0xFF00D4FF),
      glowColor: const Color(0x1F00D4FF),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.show_chart_rounded, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'EQUITY CURVE // КУМУЛЯТИВНЫЙ PnL',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                Text(
                  '${isProfit ? "+" : ""}${cumulative.toStringAsFixed(2)} USDT',
                  style: GoogleFonts.rajdhani(color: curveColor, fontWeight: FontWeight.bold, fontSize: 13),
                ),
              ],
            ),
            const SizedBox(height: 14),

            if (closedTrades.isEmpty)
              Container(
                height: 120,
                alignment: Alignment.Center,
                decoration: BoxDecoration(
                  color: const Color(0xFF071220),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0x2200D4FF)),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.timeline_rounded, color: Color(0xFF7E9BB8), size: 28),
                    const SizedBox(height: 6),
                    Text(
                      'НЕТ ЗАВЕРШЕННЫХ СДЕЛОК ДЛЯ EQUITY CURVE',
                      style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'График построится автоматически при закрытии позиций ботом',
                      style: GoogleFonts.rajdhani(color: const Color(0xFF536D88), fontSize: 11),
                    ),
                  ],
                ),
              )
            else
              SizedBox(
                height: 150,
                child: LineChart(
                  LineChartData(
                    gridData: FlGridData(
                      show: true,
                      drawVerticalLine: false,
                      horizontalInterval: 10,
                      getDrawingHorizontalLine: (value) => FlLine(
                        color: const Color(0x1A00D4FF),
                        strokeWidth: 1,
                      ),
                    ),
                    titlesData: FlTitlesData(
                      leftTitles: AxisTitles(
                        sideTitles: SideTitles(
                          showTitles: true,
                          reservedSize: 42,
                          getTitlesWidget: (val, meta) => Text(
                            '${val.toInt()}\$',
                            style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 9),
                          ),
                        ),
                      ),
                      bottomTitles: AxisTitles(
                        sideTitles: SideTitles(
                          showTitles: true,
                          reservedSize: 22,
                          getTitlesWidget: (val, meta) => Text(
                            '#${val.toInt()}',
                            style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 9),
                          ),
                        ),
                      ),
                      rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                      topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                    ),
                    borderData: FlBorderData(show: false),
                    lineBarsData: [
                      LineChartBarData(
                        spots: spots,
                        isCurved: true,
                        curveSmoothness: 0.25,
                        color: curveColor,
                        barWidth: 2.2,
                        isStrokeCapRound: true,
                        dotData: FlDotData(
                          show: spots.length <= 15,
                          getDotPainter: (spot, percent, barData, index) => FlDotCirclePainter(
                            radius: 3,
                            color: curveColor,
                            strokeWidth: 1,
                            strokeColor: Colors.white,
                          ),
                        ),
                        belowBarData: BarAreaData(
                          show: true,
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [
                              curveColor.withOpacity(0.28),
                              curveColor.withOpacity(0.0),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// Фильтры по паре и датам
  Widget _buildFilterBar() {
    final dateFormat = DateFormat('dd.MM.yyyy');
    final hasDateFilter = _startDateFilter != null || _endDateFilter != null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              'ФИЛЬТРЫ // СОРТИРОВКА',
              style: GoogleFonts.orbitron(
                color: const Color(0xFF7E9BB8),
                fontSize: 10,
                fontWeight: FontWeight.bold,
                letterSpacing: 1.0,
              ),
            ),
            if (_selectedSymbolFilter != 'ALL' || hasDateFilter)
              InkWell(
                onTap: _clearFilters,
                child: Text(
                  'СБРОСИТЬ ВСЕ',
                  style: GoogleFonts.orbitron(
                    color: const Color(0xFFFF8A65),
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),

        // Горизонтальный список выбора пар
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: _availableSymbols.map((sym) {
              final isSel = _selectedSymbolFilter == sym;
              return Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(
                  label: Text(
                    sym,
                    style: GoogleFonts.orbitron(
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                      color: isSel ? const Color(0xFF0A1628) : Colors.white,
                    ),
                  ),
                  selected: isSel,
                  selectedColor: const Color(0xFF00D4FF),
                  backgroundColor: const Color(0xFF0D2340),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(6),
                    side: BorderSide(
                      color: isSel ? const Color(0xFF00D4FF) : const Color(0x3300D4FF),
                    ),
                  ),
                  onSelected: (val) {
                    setState(() {
                      _selectedSymbolFilter = sym;
                      _applyFilters();
                    });
                  },
                ),
              );
            }).toList(),
          ),
        ),
        const SizedBox(height: 8),

        // Фильтр по диапазону дат
        InkWell(
          onTap: _selectDateRange,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: const Color(0xFF071220),
              borderRadius: BorderRadius.circular(6),
              border: Border.all(
                color: hasDateFilter ? const Color(0xFF00D4FF) : const Color(0x3300D4FF),
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.date_range_rounded, color: Color(0xFF00D4FF), size: 16),
                    const SizedBox(width: 8),
                    Text(
                      hasDateFilter
                          ? '${dateFormat.format(_startDateFilter!)} — ${dateFormat.format(_endDateFilter!)}'
                          : 'ВЫБРАТЬ ДИАПАЗОН ДАТ (ВСЁ ВРЕМЯ)',
                      style: GoogleFonts.rajdhani(
                        color: hasDateFilter ? Colors.white : const Color(0xFF7E9BB8),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                if (hasDateFilter)
                  GestureDetector(
                    onTap: () {
                      setState(() {
                        _startDateFilter = null;
                        _endDateFilter = null;
                        _applyFilters();
                      });
                    },
                    child: const Icon(Icons.close, color: Color(0xFFFF8A65), size: 16),
                  )
                else
                  const Icon(Icons.arrow_drop_down, color: Color(0xFF7E9BB8)),
              ],
            ),
          ),
        ),
      ],
    );
  }

  /// Список / Таблица сделок
  Widget _buildTradesList() {
    if (_filteredTrades.isEmpty) {
      return Container(
        margin: const EdgeInsets.only(top: 10),
        padding: const EdgeInsets.symmetric(vertical: 36, horizontal: 16),
        decoration: BoxDecoration(
          color: const Color(0xFF071220),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x2200D4FF)),
        ),
        child: Column(
          children: [
            const Icon(Icons.receipt_long_outlined, color: Color(0xFF7E9BB8), size: 36),
            const SizedBox(height: 12),
            Text(
              'НЕТ СДЕЛОК НА БИРЖЕ // ОЖИДАНИЕ ИСПОЛНЕНИЙ',
              textAlign: TextAlign.center,
              style: GoogleFonts.orbitron(
                color: Colors.white,
                fontSize: 12,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'Нажмите "Синхронизировать с Binance API" вверху или запустите бота на дашборде для совершения сделок.',
              textAlign: TextAlign.center,
              style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 12),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              'СПИСОК ОПЕРАЦИЙ (${_filteredTrades.length})',
              style: GoogleFonts.orbitron(
                color: const Color(0xFF7E9BB8),
                fontSize: 10,
                fontWeight: FontWeight.bold,
                letterSpacing: 1.0,
              ),
            ),
            Text(
              'СОРТИРОВКА: ПО ВРЕМЕНИ (DESC)',
              style: GoogleFonts.rajdhani(color: const Color(0xFF536D88), fontSize: 10),
            ),
          ],
        ),
        const SizedBox(height: 8),

        ListView.separated(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: _filteredTrades.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (context, index) {
            return _buildTradeItemTile(_filteredTrades[index]);
          },
        ),
      ],
    );
  }

  Widget _buildTradeItemTile(TradeRecord trade) {
    final isBuy = trade.side == OrderSide.buy;
    final sideColor = isBuy ? const Color(0xFF00E676) : const Color(0xFFFF5252);
    final isClosed = trade.status != TradeStatus.open;
    final pnl = trade.realizedPnlUsdt;
    final pnlPct = trade.realizedPnlPercent;
    final isProfit = (pnl ?? 0.0) >= 0;
    final pnlColor = isProfit ? const Color(0xFF00E676) : const Color(0xFFFF5252);

    final dateFormat = DateFormat('dd.MM.yy HH:mm');
    final entryTimeStr = dateFormat.format(DateTime.fromMillisecondsSinceEpoch(trade.entryTime));
    final exitTimeStr = trade.exitTime != null
        ? dateFormat.format(DateTime.fromMillisecondsSinceEpoch(trade.exitTime!))
        : 'АКТИВНО';

    // Бейдж причины закрытия
    String reasonBadge = 'АКТИВНА';
    Color reasonBadgeColor = const Color(0xFF00D4FF);
    if (trade.status == TradeStatus.closedTakeProfit) {
      reasonBadge = 'TAKE-PROFIT';
      reasonBadgeColor = const Color(0xFF00E676);
    } else if (trade.status == TradeStatus.closedStopLoss) {
      reasonBadge = 'STOP-LOSS';
      reasonBadgeColor = const Color(0xFFFF5252);
    } else if (trade.status == TradeStatus.closedManual) {
      reasonBadge = 'РУЧНОЕ / API';
      reasonBadgeColor = const Color(0xFFFF8A65);
    }

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF071220),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: isClosed ? const Color(0x2200D4FF) : const Color(0x5500D4FF),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Строка 1: Пара, Сторона, Статус/Причина, PnL
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: sideColor.withOpacity(0.15),
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: sideColor),
                    ),
                    child: Text(
                      isBuy ? 'BUY' : 'SELL',
                      style: GoogleFonts.orbitron(
                        color: sideColor,
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    trade.symbol,
                    style: GoogleFonts.orbitron(
                      color: Colors.white,
                      fontSize: 13,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                    decoration: BoxDecoration(
                      color: reasonBadgeColor.withOpacity(0.12),
                      borderRadius: BorderRadius.circular(3),
                      border: Border.all(color: reasonBadgeColor.withOpacity(0.5)),
                    ),
                    child: Text(
                      reasonBadge,
                      style: GoogleFonts.orbitron(
                        color: reasonBadgeColor,
                        fontSize: 8,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ],
              ),
              // PnL
              if (isClosed && pnl != null)
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      '${isProfit ? "+" : ""}${pnl.toStringAsFixed(2)} USDT',
                      style: GoogleFonts.orbitron(
                        color: pnlColor,
                        fontSize: 13,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    if (pnlPct != null)
                      Text(
                        '${isProfit ? "+" : ""}${pnlPct.toStringAsFixed(2)}%',
                        style: GoogleFonts.rajdhani(
                          color: pnlColor,
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                  ],
                )
              else
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0x1A00D4FF),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: const Color(0x3300D4FF)),
                  ),
                  child: Text(
                    'В РАБОТЕ',
                    style: GoogleFonts.orbitron(color: const Color(0xFF00D4FF), fontSize: 9),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),

          // Строка 2: Вход, Выход, Объем
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'ВХОД: \$${trade.entryPrice.toStringAsFixed(2)}',
                style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 12),
              ),
              Text(
                trade.exitPrice != null ? 'ВЫХОД: \$${trade.exitPrice!.toStringAsFixed(2)}' : 'ТЕКУЩАЯ: В РЫНКЕ',
                style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 12),
              ),
              Text(
                'ОБЪЕМ: ${trade.quantity.toStringAsFixed(4)}',
                style: GoogleFonts.rajdhani(color: const Color(0xFFFF8A65), fontSize: 12, fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 4),

          // Строка 3: Время входа и выхода, описание причины
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'ВРЕМЯ: $entryTimeStr → $exitTimeStr',
                style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 10),
              ),
              if (trade.exitReason != null && trade.exitReason!.isNotEmpty)
                Flexible(
                  child: Text(
                    trade.exitReason!,
                    overflow: TextOverflow.ellipsis,
                    style: GoogleFonts.rajdhani(color: const Color(0xFF536D88), fontSize: 10),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
