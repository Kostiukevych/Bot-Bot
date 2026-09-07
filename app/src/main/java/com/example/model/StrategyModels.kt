package com.example.model

enum class SignalAction {
  BUY_LONG,
  SELL_SPOT,
  HOLD
}

data class IndicatorValues(
  val rsi: Double,
  val emaFast: Double,
  val emaSlow: Double,
  val macdLine: Double,
  val macdSignal: Double,
  val macdHist: Double,
  val currentVolume: Double,
  val avgVolume: Double,
  val bbUpper: Double,
  val bbMiddle: Double,
  val bbLower: Double,
) {
  val isVolumeAboveAvg: Boolean get() = currentVolume > avgVolume
  val isEmaBullish: Boolean get() = emaFast > emaSlow
  val isMacdRising: Boolean get() = macdHist >= 0
}

data class SignalData(
  val symbol: String,
  val currentPrice: Double,
  val indicators: IndicatorValues,
  val action: SignalAction,
  val score: Int,
  val matchedConditions: List<String>,
  val recommendedStopLoss: Double,
  val recommendedTakeProfit: Double,
  val timestamp: Long = System.currentTimeMillis(),
)

data class StrategyRiskConfig(
  val interval: String = "5m",
  val stopLossPercent: Double = 2.0,
  val takeProfitPercent: Double = 4.0,
  val minScoreThreshold: Int = 70,
  val maxDepositRiskPercent: Double = 25.0,
)

data class TradeRecord(
  val id: String,
  val symbol: String,
  val side: String, // "BUY" / "SELL"
  val entryPrice: Double,
  val quantity: Double,
  val usdtAmount: Double,
  val stopLossPrice: Double,
  val takeProfitPrice: Double,
  val score: Int,
  val entryTime: Long,
  var exitPrice: Double? = null,
  var exitTime: Long? = null,
  var realizedPnlUsdt: Double? = null,
  var realizedPnlPercent: Double? = null,
  var status: String = "OPEN",
  var exitReason: String? = null,
)
