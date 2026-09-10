package com.example.model

data class TradingPair(
  val symbol: String,
  val baseAsset: String,
  val quoteAsset: String,
  val status: String,
  val minQty: Double,
  val maxQty: Double,
  val stepSize: Double,
  val minNotional: Double,
)

data class TickerData(
  val symbol: String,
  val lastPrice: Double,
  val priceChangePercent: Double,
  val highPrice: Double = 0.0,
  val lowPrice: Double = 0.0,
  val volume: Double = 0.0,
  val quoteVolume: Double = 0.0,
) {
  val isPositive: Boolean get() = priceChangePercent >= 0
}

data class BookTicker(
  val symbol: String,
  val bidPrice: Double,
  val askPrice: Double
) {
  val spreadPercent: Double get() = if (askPrice > 0) (askPrice - bidPrice) / askPrice * 100.0 else 0.0
}

data class OpenOrder(
  val orderId: Long,
  val symbol: String,
  val side: String,
  val type: String,
  val price: Double,
  val origQty: Double,
  val executedQty: Double,
  val status: String,
  val time: Long,
) {
  val isBuy: Boolean get() = side.equals("BUY", ignoreCase = true)
}

enum class BotStatus(val title: String, val colorHex: Long) {
  STOPPED("БОТ ОСТАНОВЛЕН", 0xFFFF5252),
  ANALYSIS("Анализ рынка...", 0xFF00D4FF),
  WAITING("Ожидание сигнала", 0xFFFFB300),
  LONG("Позиция открыта LONG", 0xFF00E676),
  SHORT("Позиция открыта SHORT", 0xFFFF8A65)
}

data class Candle(
  val openTime: Long,
  val open: Double,
  val high: Double,
  val low: Double,
  val close: Double,
  val volume: Double,
  val closeTime: Long = 0L,
  val isClosed: Boolean = false
) {
  val isBullish: Boolean get() = close >= open
}

data class KlineUpdate(
  val symbol: String,
  val interval: String,
  val candle: Candle,
  val isClosed: Boolean
)
