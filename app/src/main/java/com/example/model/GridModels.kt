package com.example.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GridDirection(val title: String) {
  LONG("LONG"),
  SHORT("SHORT")
}

enum class GridLevelStatus {
  PENDING_BUY,
  PENDING_SELL,
  FILLED,
  CANCELED
}

data class GridLevel(
  val index: Int,
  val price: Double,
  var side: String, // "BUY" or "SELL"
  var quantity: Double,
  var orderId: Long? = null,
  var status: GridLevelStatus = GridLevelStatus.PENDING_BUY,
  var entryPrice: Double? = null,
  var profitUsdt: Double = 0.0
)

data class GridConfig(
  val symbol: String = "BTCUSDT",
  val direction: GridDirection = GridDirection.LONG,
  val rangePercent: Double = 4.0, // ±4% от текущей цены
  val lowerBound: Double = 0.0,
  val upperBound: Double = 0.0,
  val levelCount: Int = 8, // от 3 до 50
  val buySellRatio: Float = 0.5f, // 0.1f..0.9f, 0.5f = 50% BUY / 50% SELL
  val isAutoCapitalPerLevel: Boolean = true,
  val capitalPercentPerLevel: Double = 12.5, // 100 / 8
  val totalInvestmentUsdt: Double = 100.0,
  val trailingTriggerPercent: Double = 3.0, // по умолчанию 3%
  val trailingOffsetPercent: Double = 6.0,  // по умолчанию 6%
  val tradingInterval: String = "15m" // таймфрейм, зафиксированный при старте
) {
  val stepSizePrice: Double
    get() = if (levelCount > 1 && upperBound > lowerBound) (upperBound - lowerBound) / (levelCount - 1) else 0.0
}

data class GridLogEvent(
  val timestamp: Long = System.currentTimeMillis(),
  val message: String,
  val isHighlight: Boolean = false,
  val isError: Boolean = false
) {
  val formattedTime: String
    get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}

data class GridBotState(
  val isActive: Boolean = false,
  val config: GridConfig = GridConfig(),
  val currentPrice: Double = 0.0,
  val peakPrice: Double = 0.0,
  val levels: List<GridLevel> = emptyList(),
  val totalProfitUsdt: Double = 0.0,
  val completedGrids: Int = 0,
  val activeOrderCount: Int = 0,
  val logs: List<GridLogEvent> = emptyList(),
  val recalculationCount: Int = 0,
  val lastRecalculationTimestamp: Long = 0L,
  val lastFlashTrigger: Long = 0L,
  val errorMessage: String? = null
) {
  val reservedInOrdersUsdt: Double
    get() = levels.filter { it.status == GridLevelStatus.PENDING_BUY && it.orderId != null }.sumOf { it.price * it.quantity }
}
