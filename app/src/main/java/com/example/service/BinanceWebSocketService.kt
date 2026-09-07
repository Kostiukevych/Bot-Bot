package com.example.service

import com.example.model.TickerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

  fun subscribeToTicker(symbol: String) {
    val clean = symbol.lowercase().trim()
    if (currentSymbol == clean && webSocket != null) return

    disconnect()
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
              )
              _tickerFlow.value = ticker
            }
          }
        } catch (_: Exception) {}
        delay(2500)
      }
    }
  }

  fun disconnect() {
    pollJob?.cancel()
    webSocket?.close(1000, "Normal Closure")
    webSocket = null
  }
}
