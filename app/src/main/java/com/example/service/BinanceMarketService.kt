package com.example.service

import com.example.model.Candle
import com.example.model.OpenOrder
import com.example.model.TradingPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceMarketService(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()
) {
  companion object {
    const val TESTNET_BASE_URL = "https://testnet.binance.vision"
  }

  private fun hmacSha256(data: String, key: String): String {
    val sha256Hmac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
    sha256Hmac.init(secretKey)
    val signedBytes = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
    return signedBytes.joinToString("") { "%02x".format(it) }
  }

  suspend fun getTradingPairs(): List<TradingPair> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url("$TESTNET_BASE_URL/api/v3/exchangeInfo")
      .get()
      .build()

    client.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (response.isSuccessful) {
        val root = JSONObject(body)
        val symbols = root.optJSONArray("symbols") ?: return@withContext emptyList()
        val list = mutableListOf<TradingPair>()
          for (i in 0 until symbols.length()) {
            val item = symbols.getJSONObject(i)
            val status = item.optString("status")
            val quoteAsset = item.optString("quoteAsset")
            if (status == "TRADING" && quoteAsset.equals("USDT", ignoreCase = true)) {
              var minQty = 0.00001
              var maxQty = 9000.0
              var stepSize = 0.00001
              var minNotional = 10.0

              val filters = item.optJSONArray("filters")
              if (filters != null) {
                for (j in 0 until filters.length()) {
                  val f = filters.getJSONObject(j)
                  val type = f.optString("filterType")
                  if (type == "LOT_SIZE") {
                    minQty = f.optString("minQty", "0.00001").toDoubleOrNull() ?: minQty
                    maxQty = f.optString("maxQty", "9000").toDoubleOrNull() ?: maxQty
                    stepSize = f.optString("stepSize", "0.00001").toDoubleOrNull() ?: stepSize
                  } else if (type == "MIN_NOTIONAL" || type == "NOTIONAL") {
                    minNotional = f.optString("minNotional", f.optString("notional", "10")).toDoubleOrNull() ?: minNotional
                  }
                }
              }

              list.add(
                TradingPair(
                  symbol = item.optString("symbol"),
                  baseAsset = item.optString("baseAsset"),
                  quoteAsset = quoteAsset,
                  status = status,
                  minQty = minQty,
                  maxQty = maxQty,
                  stepSize = stepSize,
                  minNotional = minNotional,
                )
              )
            }
          }
          list
        } else {
          throw java.io.IOException("Ошибка сервера Binance exchangeInfo: HTTP ${response.code} ${response.message}")
        }
      }
    }

  suspend fun getOpenOrders(apiKey: String, secretKey: String): List<OpenOrder> = withContext(Dispatchers.IO) {
    if (apiKey.isBlank() || secretKey.isBlank()) return@withContext emptyList()
    val timestamp = System.currentTimeMillis()
    val queryString = "timestamp=$timestamp&recvWindow=5000"
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$TESTNET_BASE_URL/api/v3/openOrders?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .header("Accept", "application/json")
      .get()
      .build()

    try {
      client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) {
          val array = org.json.JSONArray(body)
          val list = mutableListOf<OpenOrder>()
          for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
              OpenOrder(
                orderId = item.optLong("orderId"),
                symbol = item.optString("symbol"),
                side = item.optString("side"),
                type = item.optString("type"),
                price = item.optString("price", "0").toDoubleOrNull() ?: 0.0,
                origQty = item.optString("origQty", "0").toDoubleOrNull() ?: 0.0,
                executedQty = item.optString("executedQty", "0").toDoubleOrNull() ?: 0.0,
                status = item.optString("status"),
                time = item.optLong("time"),
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

  suspend fun cancelOrder(apiKey: String, secretKey: String, symbol: String, orderId: Long): Boolean = withContext(Dispatchers.IO) {
    val timestamp = System.currentTimeMillis()
    val queryString = "symbol=$symbol&orderId=$orderId&timestamp=$timestamp&recvWindow=5000"
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$TESTNET_BASE_URL/api/v3/order?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .delete()
      .build()

    try {
      client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
      false
    }
  }

  suspend fun getOrderStatus(apiKey: String, secretKey: String, symbol: String, orderId: Long): String? = withContext(Dispatchers.IO) {
    if (apiKey.isBlank() || secretKey.isBlank()) return@withContext null
    val timestamp = System.currentTimeMillis()
    val queryString = "symbol=${symbol.uppercase().trim()}&orderId=$orderId&timestamp=$timestamp&recvWindow=5000"
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$TESTNET_BASE_URL/api/v3/order?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .header("Accept", "application/json")
      .get()
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          val obj = JSONObject(response.body?.string().orEmpty())
          obj.optString("status")
        } else {
          null
        }
      }
    } catch (_: Exception) {
      null
    }
  }

  suspend fun getMyTrades(apiKey: String, secretKey: String, symbol: String, limit: Int = 50): List<Map<String, Any>> = withContext(Dispatchers.IO) {
    if (apiKey.isBlank() || secretKey.isBlank()) return@withContext emptyList()
    val timestamp = System.currentTimeMillis()
    val queryString = "symbol=${symbol.uppercase()}&limit=$limit&timestamp=$timestamp&recvWindow=5000"
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$TESTNET_BASE_URL/api/v3/myTrades?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .header("Accept", "application/json")
      .get()
      .build()

    try {
      client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) {
          val array = org.json.JSONArray(body)
          val list = mutableListOf<Map<String, Any>>()
          for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val map = mutableMapOf<String, Any>()
            map["id"] = item.optLong("id")
            map["orderId"] = item.optLong("orderId")
            map["price"] = item.optString("price", "0")
            map["qty"] = item.optString("qty", "0")
            map["quoteQty"] = item.optString("quoteQty", "0")
            map["time"] = item.optLong("time")
            map["isBuyer"] = item.optBoolean("isBuyer")
            list.add(map)
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

  suspend fun fetchCandles(symbol: String, interval: String, limit: Int = 150): List<Candle> = withContext(Dispatchers.IO) {
    val cleanSymbol = symbol.uppercase().trim()
    val cleanInterval = interval.trim()
    val url = "$TESTNET_BASE_URL/api/v3/klines?symbol=$cleanSymbol&interval=$cleanInterval&limit=$limit"
    val req = Request.Builder().url(url).get().build()
    try {
      client.newCall(req).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (res.isSuccessful) {
          val array = org.json.JSONArray(body)
          val list = mutableListOf<Candle>()
          for (i in 0 until array.length()) {
            val k = array.getJSONArray(i)
            list.add(
              Candle(
                openTime = k.getLong(0),
                open = k.getString(1).toDoubleOrNull() ?: 0.0,
                high = k.getString(2).toDoubleOrNull() ?: 0.0,
                low = k.getString(3).toDoubleOrNull() ?: 0.0,
                close = k.getString(4).toDoubleOrNull() ?: 0.0,
                volume = k.getString(5).toDoubleOrNull() ?: 0.0,
                closeTime = k.optLong(6, 0L),
                isClosed = true
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
}
