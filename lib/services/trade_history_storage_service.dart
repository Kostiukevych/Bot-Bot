import 'dart:convert';
import 'package:intl/intl.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';
import '../models/trade_history.dart';

/// Сводная статистика торговли
class TradeSummaryStats {
  final int totalTrades;
  final int winningTrades;
  final int losingTrades;
  final int openTrades;
  final double winRatePercent; // 0.0 - 100.0%
  final double totalPnlUsdt;
  final double totalPnlPercent;
  final double avgProfitUsdt;
  final double avgLossUsdt;
  final double profitFactor;
  final double maxDrawdownUsdt;

  const TradeSummaryStats({
    required this.totalTrades,
    required this.winningTrades,
    required this.losingTrades,
    required this.openTrades,
    required this.winRatePercent,
    required this.totalPnlUsdt,
    required this.totalPnlPercent,
    required this.avgProfitUsdt,
    required this.avgLossUsdt,
    required this.profitFactor,
    required this.maxDrawdownUsdt,
  });

  factory TradeSummaryStats.empty() {
    return const TradeSummaryStats(
      totalTrades: 0,
      winningTrades: 0,
      losingTrades: 0,
      openTrades: 0,
      winRatePercent: 0.0,
      totalPnlUsdt: 0.0,
      totalPnlPercent: 0.0,
      avgProfitUsdt: 0.0,
      avgLossUsdt: 0.0,
      profitFactor: 0.0,
      maxDrawdownUsdt: 0.0,
    );
  }

  factory TradeSummaryStats.fromTrades(List<TradeRecord> trades) {
    if (trades.isEmpty) return TradeSummaryStats.empty();

    int winning = 0;
    int losing = 0;
    int open = 0;
    double totalPnl = 0.0;
    double totalPnlPct = 0.0;
    double sumGains = 0.0;
    double sumLosses = 0.0;

    for (final t in trades) {
      if (t.status == TradeStatus.open) {
        open++;
        continue;
      }
      final pnl = t.realizedPnlUsdt ?? 0.0;
      final pnlPct = t.realizedPnlPercent ?? 0.0;
      totalPnl += pnl;
      totalPnlPct += pnlPct;

      if (pnl > 0) {
        winning++;
        sumGains += pnl;
      } else if (pnl < 0) {
        losing++;
        sumLosses += pnl.abs();
      }
    }

    final closedCount = winning + losing;
    final winrate = closedCount > 0 ? (winning / closedCount) * 100.0 : 0.0;
    final avgProfit = winning > 0 ? (sumGains / winning) : 0.0;
    final avgLoss = losing > 0 ? (sumLosses / losing) : 0.0;
    final profitFactor = sumLosses > 0 ? (sumGains / sumLosses) : (sumGains > 0 ? 999.0 : 0.0);

    return TradeSummaryStats(
      totalTrades: trades.length,
      winningTrades: winning,
      losingTrades: losing,
      openTrades: open,
      winRatePercent: winrate,
      totalPnlUsdt: totalPnl,
      totalPnlPercent: totalPnlPct,
      avgProfitUsdt: avgProfit,
      avgLossUsdt: avgLoss,
      profitFactor: profitFactor,
      maxDrawdownUsdt: 0.0,
    );
  }
}

/// Сервис локального хранения истории сделок в SQLite (sqflite)
/// и генерации отчетов в формате CSV
class TradeHistoryStorageService {
  static const String _tableName = 'bot_trades';
  static Database? _db;

  // In-memory резерв на случай работы в средах без нативного sqflite
  final List<TradeRecord> _memoryFallback = [];

  Future<Database?> get database async {
    if (_db != null) return _db;
    try {
      final dbPath = await getDatabasesPath();
      final path = p.join(dbPath, 'binance_bot_trades.db');
      _db = await openDatabase(
        path,
        version: 1,
        onCreate: (db, version) async {
          await db.execute('''
            CREATE TABLE $_tableName (
              id TEXT PRIMARY KEY,
              symbol TEXT NOT NULL,
              side TEXT NOT NULL,
              entryPrice REAL NOT NULL,
              exitPrice REAL,
              quantity REAL NOT NULL,
              usdtAmount REAL NOT NULL,
              stopLossPrice REAL NOT NULL,
              takeProfitPrice REAL NOT NULL,
              score INTEGER NOT NULL,
              entryTime INTEGER NOT NULL,
              exitTime INTEGER,
              realizedPnlUsdt REAL,
              realizedPnlPercent REAL,
              status TEXT NOT NULL,
              exitReason TEXT
            )
          ''');
        },
      );
      return _db;
    } catch (_) {
      // Fallback на in-memory хранилище
      return null;
    }
  }

  /// Сохранение или обновление сделки
  Future<void> saveTrade(TradeRecord trade) async {
    try {
      final db = await database;
      if (db != null) {
        await db.insert(
          _tableName,
          {
            'id': trade.id,
            'symbol': trade.symbol,
            'side': trade.side == OrderSide.buy ? 'BUY' : 'SELL',
            'entryPrice': trade.entryPrice,
            'exitPrice': trade.exitPrice,
            'quantity': trade.quantity,
            'usdtAmount': trade.usdtAmount,
            'stopLossPrice': trade.stopLossPrice,
            'takeProfitPrice': trade.takeProfitPrice,
            'score': trade.score,
            'entryTime': trade.entryTime,
            'exitTime': trade.exitTime,
            'realizedPnlUsdt': trade.realizedPnlUsdt,
            'realizedPnlPercent': trade.realizedPnlPercent,
            'status': trade.status.name,
            'exitReason': trade.exitReason,
          },
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
        return;
      }
    } catch (_) {}

    // In-memory fallback
    final idx = _memoryFallback.indexWhere((t) => t.id == trade.id);
    if (idx != -1) {
      _memoryFallback[idx] = trade;
    } else {
      _memoryFallback.insert(0, trade);
    }
  }

  /// Получение всех сделок
  Future<List<TradeRecord>> getAllTrades() async {
    try {
      final db = await database;
      if (db != null) {
        final rows = await db.query(_tableName, orderBy: 'entryTime DESC');
        return rows.map((r) => _fromMap(r)).toList();
      }
    } catch (_) {}

    return List.unmodifiable(_memoryFallback);
  }

  /// Получение отфильтрованных сделок по символу и диапазону дат
  Future<List<TradeRecord>> getFilteredTrades({
    String? symbol,
    DateTime? fromDate,
    DateTime? toDate,
  }) async {
    final all = await getAllTrades();
    return all.where((t) {
      if (symbol != null && symbol.isNotEmpty && symbol != 'ALL') {
        if (t.symbol.toUpperCase() != symbol.toUpperCase()) return false;
      }
      if (fromDate != null) {
        final entryDt = DateTime.fromMillisecondsSinceEpoch(t.entryTime);
        if (entryDt.isBefore(DateTime(fromDate.year, fromDate.month, fromDate.day))) {
          return false;
        }
      }
      if (toDate != null) {
        final entryDt = DateTime.fromMillisecondsSinceEpoch(t.entryTime);
        if (entryDt.isAfter(DateTime(toDate.year, toDate.month, toDate.day, 23, 59, 59))) {
          return false;
        }
      }
      return true;
    }).toList();
  }

  /// Удаление всех записей
  Future<void> clearAll() async {
    try {
      final db = await database;
      if (db != null) {
        await db.delete(_tableName);
      }
    } catch (_) {}
    _memoryFallback.clear();
  }

  /// Экспорт списка сделок в CSV формат (RFC 4180)
  String exportToCsv(List<TradeRecord> trades) {
    final dateFormat = DateFormat('yyyy-MM-dd HH:mm:ss');
    final buffer = StringBuffer();

    // Заголовки таблицы CSV
    buffer.writeln(
      'Trade ID,Symbol,Side,Entry Price (USDT),Exit Price (USDT),Quantity,'
      'USDT Amount,Stop-Loss,Take-Profit,Strategy Score,Entry Time,Exit Time,'
      'PnL (USDT),PnL (%),Status,Exit Reason',
    );

    for (final t in trades) {
      final entryTimeStr = dateFormat.format(DateTime.fromMillisecondsSinceEpoch(t.entryTime));
      final exitTimeStr = t.exitTime != null
          ? dateFormat.format(DateTime.fromMillisecondsSinceEpoch(t.exitTime!))
          : '-';
      final exitPriceStr = t.exitPrice != null ? t.exitPrice!.toStringAsFixed(4) : '-';
      final pnlUsdtStr = t.realizedPnlUsdt != null ? t.realizedPnlUsdt!.toStringAsFixed(4) : '-';
      final pnlPctStr = t.realizedPnlPercent != null ? '${t.realizedPnlPercent!.toStringAsFixed(2)}%' : '-';
      final reasonEscaped = t.exitReason != null ? '"${t.exitReason!.replaceAll('"', '""')}"' : '-';

      buffer.writeln([
        t.id,
        t.symbol,
        t.side == OrderSide.buy ? 'BUY' : 'SELL',
        t.entryPrice.toStringAsFixed(4),
        exitPriceStr,
        t.quantity.toStringAsFixed(6),
        t.usdtAmount.toStringAsFixed(2),
        t.stopLossPrice.toStringAsFixed(4),
        t.takeProfitPrice.toStringAsFixed(4),
        '${t.score}%',
        entryTimeStr,
        exitTimeStr,
        pnlUsdtStr,
        pnlPctStr,
        t.status.name,
        reasonEscaped,
      ].join(','));
    }

    return buffer.toString();
  }

  TradeRecord _fromMap(Map<String, dynamic> map) {
    return TradeRecord(
      id: map['id'] as String,
      symbol: map['symbol'] as String,
      side: map['side'] == 'BUY' ? OrderSide.buy : OrderSide.sell,
      entryPrice: (map['entryPrice'] as num).toDouble(),
      exitPrice: (map['exitPrice'] as num?)?.toDouble(),
      quantity: (map['quantity'] as num).toDouble(),
      usdtAmount: (map['usdtAmount'] as num).toDouble(),
      stopLossPrice: (map['stopLossPrice'] as num).toDouble(),
      takeProfitPrice: (map['takeProfitPrice'] as num).toDouble(),
      score: (map['score'] as num).toInt(),
      entryTime: (map['entryTime'] as num).toInt(),
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
}
