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
  val atr: Double = 0.0,
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

data class PairScanResult(
  val pair: TradingPair,
  val ticker: TickerData,
  val signal: SignalData
)

data class StrategyRiskConfig(
  val interval: String = "5m",
  val stopLossPercent: Double = 2.0,
  val takeProfitPercent: Double = 4.0,
  val minScoreThreshold: Int = 70,
  val maxDepositRiskPercent: Double = 25.0,
  val useAtrSlTp: Boolean = true,
  val atrPeriod: Int = 14,
  val atrSlMultiplier: Double = 1.5,
  val atrTpMultiplier: Double = 3.0,
  val trailingEnabled: Boolean = false,
  val trailingActivationPercent: Double = 1.0,
  val trailingStepPercent: Double = 0.5,
  val maxConsecutiveLosses: Int = 3,
  val dailyLossLimitPercent: Double = 5.0,
  val maxSpreadPercent: Double = 0.15,
  val scannerEnabled: Boolean = false,
  val scannerTopN: Int = 20,
  val scannerIntervalSeconds: Int = 60,
)

data class TradeRecord(
  val id: String,
  val symbol: String,
  val side: String, // "BUY" / "SELL"
  val entryPrice: Double,
  val quantity: Double,
  val usdtAmount: Double,
  var stopLossPrice: Double,
  val takeProfitPrice: Double,
  val score: Int,
  val entryTime: Long,
  var exitPrice: Double? = null,
  var exitTime: Long? = null,
  var realizedPnlUsdt: Double? = null,
  var realizedPnlPercent: Double? = null,
  var status: String = STATUS_OPEN,
  var exitReason: String? = null,
  var entryFeeUsdt: Double = 0.0,
  var exitFeeUsdt: Double = 0.0,
  var peakPrice: Double = 0.0,
  var trailingActive: Boolean = false,
) {
  companion object {
    const val STATUS_OPEN = "OPEN"
    const val STATUS_CLOSE_PENDING_RETRY = "CLOSE_PENDING_RETRY"
    const val STATUS_ERROR = "ERROR"
    const val STATUS_CLOSED = "CLOSED"
  }
}
