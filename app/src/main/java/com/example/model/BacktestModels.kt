package com.example.model

/**
 * Тип стратегии для проведения бэктеста.
 */
enum class BacktestBotType {
  SIGNAL_BOT,
  GRID_BOT
}

/**
 * Конфигурация параметров бэктеста.
 */
data class BacktestConfig(
  val symbol: String = "BTCUSDT",
  val interval: String = "15m",
  val botType: BacktestBotType = BacktestBotType.SIGNAL_BOT,
  val candleCount: Int = 300,
  val startTime: Long? = null,
  val endTime: Long? = null,
  val initialBalanceUsdt: Double = 1000.0,
  val strategyRiskConfig: StrategyRiskConfig? = null,
  val gridConfig: GridConfig? = null
)

/**
 * Маркер завершённой сделки сигнального бота для отрисовки на графике.
 */
data class BacktestTradeMarker(
  val entryTime: Long,
  val entryPrice: Double,
  val exitTime: Long,
  val exitPrice: Double,
  val side: String,
  val pnlUsdt: Double,
  val pnlPercent: Double,
  val isProfit: Boolean,
  val reason: String
)

/**
 * Маркер исторического исполнения уровня сеточного бота.
 */
data class BacktestGridFillMarker(
  val time: Long,
  val price: Double,
  val levelIndex: Int,
  val side: String, // "BUY" / "SELL"
  val status: GridLevelStatus = GridLevelStatus.FILLED,
  val profitUsdt: Double = 0.0
)

/**
 * Точка кривой капитала (Equity) во времени.
 */
data class EquityPoint(
  val time: Long,
  val balanceUsdt: Double
)

/**
 * Статистические метрики эффективности бэктеста.
 */
data class BacktestStats(
  val totalTrades: Int,
  val winTrades: Int,
  val lossTrades: Int,
  val winRatePercent: Double,
  val profitFactor: Double,
  val maxDrawdownPercent: Double,
  val totalPnlUsdt: Double,
  val totalPnlPercent: Double,
  val buyAndHoldPnlPercent: Double
)

/**
 * Полный результат прогона бэктеста со свечами, маркерами и эквити.
 */
data class BacktestResult(
  val config: BacktestConfig,
  val candles: List<Candle>,
  val tradeMarkers: List<BacktestTradeMarker> = emptyList(),
  val gridFillMarkers: List<BacktestGridFillMarker> = emptyList(),
  val equityCurve: List<EquityPoint> = emptyList(),
  val stats: BacktestStats
)
