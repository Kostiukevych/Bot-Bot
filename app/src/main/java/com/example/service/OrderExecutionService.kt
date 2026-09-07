package com.example.service

import com.example.model.TradeRecord
import com.example.model.TradingPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OrderExecutionResult(
  val isSuccess: Boolean,
  val orderId: String? = null,
  val symbol: String? = null,
  val executedQty: Double = 0.0,
  val price: Double = 0.0,
  val errorMessage: String? = null,
  val binanceErrorCode: Int? = null,
)

class OrderExecutionService(
  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()
) {
  companion object {
    const val TESTNET_BASE_URL = "https://testnet.binance.vision"
  }

  private val _tradeHistory = mutableListOf<TradeRecord>()
  val tradeHistory: List<TradeRecord> get() = _tradeHistory

  private val _openPositions = mutableMapOf<String, TradeRecord>()
  val openPositions: Map<String, TradeRecord> get() = _openPositions

  fun hasOpenPosition(symbol: String): Boolean = _openPositions.containsKey(symbol.uppercase().trim())

  private fun hmacSha256(data: String, key: String): String {
    val sha256Hmac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
    sha256Hmac.init(secretKey)
    val signedBytes = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
    return signedBytes.joinToString("") { "%02x".format(it) }
  }

  suspend fun placeOrder(
    apiKey: String,
    secretKey: String,
    symbol: String,
    side: String, // "BUY" or "SELL"
    type: String, // "MARKET" or "LIMIT"
    quantity: Double,
    price: Double? = null,
    pairInfo: TradingPair? = null,
  ): OrderExecutionResult = withContext(Dispatchers.IO) {
    if (pairInfo != null) {
      if (quantity < pairInfo.minQty) {
        return@withContext OrderExecutionResult(
          isSuccess = false,
          errorMessage = "Ошибка LOT_SIZE: Объем ($quantity) меньше ${pairInfo.minQty}"
        )
      }
      val notional = (price ?: 0.0) * quantity
      if (price != null && notional < pairInfo.minNotional) {
        return@withContext OrderExecutionResult(
          isSuccess = false,
          errorMessage = "Ошибка MIN_NOTIONAL: Сумма ($notional) меньше ${pairInfo.minNotional} USDT"
        )
      }
    }

    val timestamp = System.currentTimeMillis()
    val qtyStr = "%.5f".format(java.util.Locale.US, quantity)
    val queryParams = mutableListOf(
      "symbol=${symbol.uppercase().trim()}",
      "side=${side.uppercase().trim()}",
      "type=${type.uppercase().trim()}",
      "quantity=$qtyStr",
      "timestamp=$timestamp",
      "recvWindow=5000"
    )

    if (type.equals("LIMIT", ignoreCase = true) && price != null) {
      queryParams.add("price=%.2f".format(java.util.Locale.US, price))
      queryParams.add("timeInForce=GTC")
    }

    val queryString = queryParams.joinToString("&")
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$TESTNET_BASE_URL/api/v3/order?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .header("Accept", "application/json")
      .post(ByteArray(0).toRequestBody(null))
      .build()

    try {
      client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        val json = JSONObject(body)
        if (response.isSuccessful) {
          val ordId = json.optString("orderId")
          val fillPrice = json.optString("price", price?.toString() ?: "0").toDoubleOrNull() ?: (price ?: 0.0)
          val execQty = json.optString("executedQty", quantity.toString()).toDoubleOrNull() ?: quantity

          OrderExecutionResult(
            isSuccess = true,
            orderId = ordId,
            symbol = symbol,
            executedQty = execQty,
            price = fillPrice
          )
        } else {
          val code = json.optInt("code", response.code)
          val msg = json.optString("msg", "Биржевая ошибка")
          val humanMsg = parseError(code, msg)
          OrderExecutionResult(
            isSuccess = false,
            binanceErrorCode = code,
            errorMessage = humanMsg
          )
        }
      }
    } catch (e: Exception) {
      OrderExecutionResult(isSuccess = false, errorMessage = "Ошибка сети: ${e.message}")
    }
  }

  fun recordTrade(trade: TradeRecord) {
    _tradeHistory.add(0, trade)
    if (trade.side == "BUY") {
      _openPositions[trade.symbol] = trade
    }
  }

  fun closePosition(symbol: String, exitPrice: Double, reason: String) {
    val pos = _openPositions.remove(symbol)
    if (pos != null) {
      pos.exitPrice = exitPrice
      pos.exitTime = System.currentTimeMillis()
      pos.realizedPnlUsdt = (exitPrice - pos.entryPrice) * pos.quantity
      pos.realizedPnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0
      pos.status = "CLOSED"
      pos.exitReason = reason
    }
  }

  private fun parseError(code: Int, msg: String): String {
    return when (code) {
      -2010 -> "Недостаточно средств на балансе Spot Testnet (-2010)"
      -1013 -> "Ошибка фильтров ордера (LOT_SIZE / MIN_NOTIONAL): $msg"
      -1121 -> "Неверный символ торговой пары"
      -1021 -> "Рассинхронизация времени с сервером Binance"
      -2015 -> "Неверный API-ключ или нет разрешений на торговлю"
      else -> "Binance [$code]: $msg"
    }
  }
}
