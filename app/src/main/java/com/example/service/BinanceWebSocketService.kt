package com.example.service

import com.example.model.Candle
import com.example.model.KlineUpdate
import com.example.model.TickerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BinanceWebSocketService {
  private val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var currentSymbol: String? = null
  private val scope = CoroutineScope(Dispatchers.IO + Job())
  private var pollJob: Job? = null

  private val _tickerFlow = MutableStateFlow<TickerData?>(null)
  val tickerFlow: StateFlow<TickerData?> = _tickerFlow.asStateFlow()

  // Отдельный сокет и поток для свечей (Klines)
  private var klineWebSocket: WebSocket? = null
  private var currentKlineSymbol: String? = null
  private var currentKlineInterval: String? = null

  private val _klineFlow = MutableSharedFlow<KlineUpdate>(replay = 1, extraBufferCapacity = 64)
  val klineFlow: SharedFlow<KlineUpdate> = _klineFlow.asSharedFlow()

  fun subscribeToTicker(symbol: String) {
    val clean = symbol.lowercase().trim()
    if (currentSymbol == clean && webSocket != null) return

    disconnectTicker()
    currentSymbol = clean

    val url = "wss://testnet.binance.vision/ws/${clean}@ticker"
    val request = Request.Builder().url(url).build()

    webSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        try {
          val json = JSONObject(text)
          if (json.has("c") && json.has("s")) {
            val ticker = TickerData(
              symbol = json.optString("s"),
              lastPrice = json.optString("c", "0").toDoubleOrNull() ?: 0.0,
              priceChangePercent = json.optString("P", "0").toDoubleOrNull() ?: 0.0,
              highPrice = json.optString("h", "0").toDoubleOrNull() ?: 0.0,
              lowPrice = json.optString("l", "0").toDoubleOrNull() ?: 0.0,
              volume = json.optString("v", "0").toDoubleOrNull() ?: 0.0,
            )
            _tickerFlow.value = ticker
          }
        } catch (_: Exception) {}
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // Запускаем резервный REST polling при разрыве WebSocket
        startFallbackPoll(symbol)
      }
    })
  }

  fun subscribeToKline(symbol: String, interval: String) {
    val cleanSymbol = symbol.lowercase().trim()
    val cleanInterval = interval.lowercase().trim()

    if (currentKlineSymbol == cleanSymbol && currentKlineInterval == cleanInterval && klineWebSocket != null) {
      return
    }

    unsubscribeKline()
    currentKlineSymbol = cleanSymbol
    currentKlineInterval = cleanInterval

    val url = "wss://testnet.binance.vision/ws/${cleanSymbol}@kline_${cleanInterval}"
    val request = Request.Builder().url(url).build()

    klineWebSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        try {
          val json = JSONObject(text)
          if (json.optString("e") == "kline" && json.has("k")) {
            val k = json.getJSONObject("k")
            val candle = Candle(
              openTime = k.optLong("t"),
              open = k.optString("o", "0").toDoubleOrNull() ?: 0.0,
              high = k.optString("h", "0").toDoubleOrNull() ?: 0.0,
              low = k.optString("l", "0").toDoubleOrNull() ?: 0.0,
              close = k.optString("c", "0").toDoubleOrNull() ?: 0.0,
              volume = k.optString("v", "0").toDoubleOrNull() ?: 0.0,
              closeTime = k.optLong("T"),
              isClosed = k.optBoolean("x")
            )
            val update = KlineUpdate(
              symbol = k.optString("s"),
              interval = k.optString("i"),
              candle = candle,
              isClosed = k.optBoolean("x")
            )
            _klineFlow.tryEmit(update)
          }
        } catch (_: Exception) {}
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // Ошибка вебсокета kline
      }
    })
  }

  fun unsubscribeKline() {
    try {
      klineWebSocket?.close(1000, "Unsubscribe kline")
    } catch (_: Exception) {}
    klineWebSocket = null
    currentKlineSymbol = null
    currentKlineInterval = null
  }

  private fun startFallbackPoll(symbol: String) {
    pollJob?.cancel()
    pollJob = scope.launch {
      while (isActive) {
        try {
          val req = Request.Builder()
            .url("https://testnet.binance.vision/api/v3/ticker/24hr?symbol=${symbol.uppercase()}")
            .get()
            .build()
          client.newCall(req).execute().use { res ->
            val b = res.body?.string().orEmpty()
            if (res.isSuccessful) {
              val json = JSONObject(b)
              val ticker = TickerData(
                symbol = json.optString("symbol"),
                lastPrice = json.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0,
                priceChangePercent = json.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0,
                highPrice = json.optString("highPrice", "0").toDoubleOrNull() ?: 0.0,
                lowPrice = json.optString("lowPrice", "0").toDoubleOrNull() ?: 0.0,
                volume = json.optString("volume", "0").toDoubleOrNull() ?: 0.0,
              )
              _tickerFlow.value = ticker
            }
          }
        } catch (_: Exception) {}
        delay(2500)
      }
    }
  }

  private fun disconnectTicker() {
    pollJob?.cancel()
    try {
      webSocket?.close(1000, "Normal Closure")
    } catch (_: Exception) {}
    webSocket = null
  }

  fun disconnect() {
    disconnectTicker()
    unsubscribeKline()
  }
}
