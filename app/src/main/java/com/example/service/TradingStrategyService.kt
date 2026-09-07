package com.example.service

import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class TradingStrategyService(
  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()
) {
  companion object {
    const val TESTNET_BASE_URL = "https://testnet.binance.vision"
  }

  suspend fun fetchKlines(symbol: String, interval: String = "5m", limit: Int = 60): List<List<Double>> = withContext(Dispatchers.IO) {
    val url = "$TESTNET_BASE_URL/api/v3/klines?symbol=${symbol.uppercase().trim()}&interval=$interval&limit=$limit"
    val req = Request.Builder().url(url).get().build()
    try {
      client.newCall(req).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (res.isSuccessful) {
          val array = JSONArray(body)
          val list = mutableListOf<List<Double>>()
          for (i in 0 until array.length()) {
            val candle = array.getJSONArray(i)
            list.add(
              listOf(
                candle.getDouble(0), // openTime
                candle.getString(1).toDoubleOrNull() ?: 0.0, // open
                candle.getString(2).toDoubleOrNull() ?: 0.0, // high
                candle.getString(3).toDoubleOrNull() ?: 0.0, // low
                candle.getString(4).toDoubleOrNull() ?: 0.0, // close
                candle.getString(5).toDoubleOrNull() ?: 0.0, // volume
              )
            )
          }
          list
        } else {
          emptyList()
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun evaluate(
    symbol: String,
    currentPrice: Double,
    klines: List<List<Double>>,
    config: StrategyRiskConfig
  ): SignalData {
    val closes = klines.map { it[4] }
    val volumes = klines.map { it[5] }

    val rsi = calculateRsi(closes, 14)
    val emaFast = calculateEma(closes, 9)
    val emaSlow = calculateEma(closes, 21)
    val macd = emaFast - emaSlow
    val avgVol = if (volumes.isNotEmpty()) volumes.takeLast(20).average() else 100.0
    val curVol = volumes.lastOrNull() ?: 120.0

    val bbWindow = if (closes.size >= 20) closes.takeLast(20) else closes
    val bbMid = if (bbWindow.isNotEmpty()) bbWindow.average() else currentPrice
    val std = if (bbWindow.isNotEmpty()) sqrt(bbWindow.map { (it - bbMid).pow(2) }.average()) else 10.0
    val bbUpper = bbMid + (std * 2)
    val bbLower = bbMid - (std * 2)

    val ind = IndicatorValues(
      rsi = rsi,
      emaFast = emaFast,
      emaSlow = emaSlow,
      macdLine = macd,
      macdSignal = macd * 0.9,
      macdHist = macd * 0.1,
      currentVolume = curVol,
      avgVolume = avgVol,
      bbUpper = bbUpper,
      bbMiddle = bbMid,
      bbLower = bbLower
    )

    var longScore = 0
    var shortScore = 0
    val matched = mutableListOf<String>()

    if (emaFast > emaSlow) {
      longScore += 25
      matched.add("EMA9 > EMA21 (Бычий импульс)")
    } else {
      shortScore += 25
      matched.add("EMA9 < EMA21 (Медвежий тренд)")
    }

    if (rsi <= 35.0) {
      longScore += 25
      matched.add("RSI (%.1f) в зоне перепроданности".format(rsi))
    } else if (rsi >= 65.0) {
      shortScore += 25
      matched.add("RSI (%.1f) в зоне перекупленности".format(rsi))
    } else {
      longScore += 10
      shortScore += 10
    }

    if (macd >= 0) {
      longScore += 20
      matched.add("MACD-гистограмма в положительной зоне")
    } else {
      shortScore += 20
      matched.add("MACD-гистограмма в отрицательной зоне")
    }

    if (curVol > avgVol) {
      longScore += 15
      shortScore += 15
      matched.add("Объём выше среднего за 20 периодов")
    }

    if (currentPrice <= bbLower * 1.01) {
      longScore += 15
      matched.add("Цена у нижней границы Боллинджера (L: %.1f)".format(bbLower))
    } else if (currentPrice >= bbUpper * 0.99) {
      shortScore += 15
      matched.add("Цена у верхней границы Боллинджера (U: %.1f)".format(bbUpper))
    }

    val action = when {
      longScore >= config.minScoreThreshold && longScore > shortScore -> SignalAction.BUY_LONG
      shortScore >= config.minScoreThreshold && shortScore > longScore -> SignalAction.SELL_SPOT
      else -> SignalAction.HOLD
    }

    val finalScore = max(longScore, shortScore).coerceIn(0, 100)

    val sl = if (action == SignalAction.BUY_LONG) {
      currentPrice * (1.0 - (config.stopLossPercent / 100.0))
    } else {
      currentPrice * (1.0 + (config.stopLossPercent / 100.0))
    }

    val tp = if (action == SignalAction.BUY_LONG) {
      currentPrice * (1.0 + (config.takeProfitPercent / 100.0))
    } else {
      currentPrice * (1.0 - (config.takeProfitPercent / 100.0))
    }

    return SignalData(
      symbol = symbol,
      currentPrice = currentPrice,
      indicators = ind,
      action = action,
      score = finalScore,
      matchedConditions = matched,
      recommendedStopLoss = sl,
      recommendedTakeProfit = tp
    )
  }

  private fun calculateEma(prices: List<Double>, period: Int): Double {
    if (prices.isEmpty()) return 0.0
    if (prices.size < period) return prices.average()
    val k = 2.0 / (period + 1.0)
    var ema = prices.take(period).average()
    for (i in period until prices.size) {
      ema = (prices[i] * k) + (ema * (1.0 - k))
    }
    return ema
  }

  private fun calculateRsi(prices: List<Double>, period: Int): Double {
    if (prices.size <= period) return 50.0
    var gains = 0.0
    var losses = 0.0
    for (i in 1..period) {
      val d = prices[i] - prices[i - 1]
      if (d >= 0) gains += d else losses += kotlin.math.abs(d)
    }
    var avgGain = gains / period
    var avgLoss = losses / period
    for (i in (period + 1) until prices.size) {
      val d = prices[i] - prices[i - 1]
      avgGain = (avgGain * (period - 1) + if (d >= 0) d else 0.0) / period
      avgLoss = (avgLoss * (period - 1) + if (d < 0) kotlin.math.abs(d) else 0.0) / period
    }
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - (100.0 / (1.0 + rs))
  }
}
