package com.example.service

import android.content.Context
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

data class LogEvent(
  val timestamp: Long = System.currentTimeMillis(),
  val message: String,
  val type: LogType = LogType.INFO
) {
  val formattedTime: String
    get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}

enum class LogType {
  INFO,
  SIGNAL,
  ORDER_SUCCESS,
  ORDER_ERROR,
  PROFIT,
  LOSS
}

enum class TradeFlashType {
  NONE,
  OPEN_POSITION,
  PROFIT,
  LOSS
}

data class TradeFlashEvent(
  val id: String = UUID.randomUUID().toString(),
  val type: TradeFlashType = TradeFlashType.NONE,
  val message: String = "",
  val timestamp: Long = System.currentTimeMillis()
)

data class BotEngineState(
  val isBotActive: Boolean = false,
  val botStatus: BotStatus = BotStatus.STOPPED,
  val selectedPair: TradingPair? = null,
  val tickerData: TickerData? = null,
  val currentSignal: SignalData? = null,
  val openPositions: Map<String, TradeRecord> = emptyMap(),
  val tradeHistory: List<TradeRecord> = emptyList(),
  val sessionRealizedPnlUsdt: Double = 0.0,
  val totalUsdt: Double? = null,
  val freeUsdt: Double? = null,
  val lockedUsdt: Double? = null,
  val logs: List<LogEvent> = emptyList(),
  val strategyConfig: StrategyRiskConfig = StrategyRiskConfig(
    interval = "5m",
    stopLossPercent = 2.0,
    takeProfitPercent = 4.0,
    minScoreThreshold = 55, // Сбалансированный порог по умолчанию, чтобы сигналы были активны
    maxDepositRiskPercent = 25.0
  ),
  val positionAmountUsdt: Double = 50.0,
  val positionPercent: Float = 25f,
  val lastError: String? = null,
  val lastSuccessEvent: TradeFlashEvent? = null
)

class TradingBotEngine(private val context: Context) {

  private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var analysisJob: Job? = null
  private var tickerJob: Job? = null

  val storageService = SecureStorageService(context)
  val authService = BinanceAuthService()
  val marketService = BinanceMarketService()
  val wsService = BinanceWebSocketService()
  val strategyService = TradingStrategyService()
  val orderService = OrderExecutionService()

  private val _stateFlow = MutableStateFlow(BotEngineState())
  val stateFlow: StateFlow<BotEngineState> = _stateFlow.asStateFlow()

  init {
    addLog("Движок TradingBotEngine инициализирован", LogType.INFO)
    // Фоновая загрузка пар и баланса при старте приложения
    engineScope.launch {
      initMarketData()
      refreshBalance()
    }
  }

  fun updateStrategyConfig(config: StrategyRiskConfig) {
    _stateFlow.update { it.copy(strategyConfig = config) }
    addLog("Параметры стратегии обновлены: порог=${config.minScoreThreshold}%, SL=${config.stopLossPercent}%, TP=${config.takeProfitPercent}%", LogType.INFO)
  }

  fun updatePositionAmount(amount: Double, percent: Float) {
    _stateFlow.update { it.copy(positionAmountUsdt = amount, positionPercent = percent) }
  }

  fun selectPair(pair: TradingPair) {
    _stateFlow.update { it.copy(selectedPair = pair) }
    addLog("Выбрана торговая пара: ${pair.symbol}", LogType.INFO)
    wsService.subscribeToTicker(pair.symbol)
    restartTickerObserver()
  }

  suspend fun initMarketData() {
    try {
      val pairs = marketService.getTradingPairs()
      val defaultPair = pairs.firstOrNull { it.symbol == "BTCUSDT" } ?: pairs.firstOrNull()
      if (defaultPair != null && _stateFlow.value.selectedPair == null) {
        selectPair(defaultPair)
      }
    } catch (e: Exception) {
      addLog("Ошибка загрузки торговых пар: ${e.message}", LogType.ORDER_ERROR)
    }
  }

  suspend fun refreshBalance() {
    val creds = storageService.getCredentials()
    if (creds != null && creds.apiKey.isNotBlank() && creds.secretKey.isNotBlank()) {
      try {
        val info = authService.getAccountInfo(creds.apiKey, creds.secretKey, creds.isTestnet)
        val usdt = info.balances.firstOrNull { it.asset.equals("USDT", ignoreCase = true) }
        if (usdt != null) {
          _stateFlow.update {
            it.copy(
              totalUsdt = usdt.total,
              freeUsdt = usdt.free,
              lockedUsdt = usdt.locked
            )
          }
        }
      } catch (e: Exception) {
        _stateFlow.update { it.copy(lastError = e.message) }
      }
    }
  }

  private fun restartTickerObserver() {
    tickerJob?.cancel()
    tickerJob = engineScope.launch {
      wsService.tickerFlow.collect { ticker ->
        if (ticker != null) {
          val cur = _stateFlow.value.selectedPair
          if (cur == null || ticker.symbol.equals(cur.symbol, ignoreCase = true)) {
            _stateFlow.update { it.copy(tickerData = ticker) }
          }
        }
      }
    }
  }

  fun start() {
    if (_stateFlow.value.isBotActive) return
    _stateFlow.update {
      it.copy(
        isBotActive = true,
        botStatus = if (it.openPositions.isNotEmpty()) BotStatus.LONG else BotStatus.ANALYSIS
      )
    }
    addLog("🚀 Торговый бот запущен. Анализ рынка в фоне активирован.", LogType.INFO)
    startAnalysisLoop()
  }

  fun stop() {
    _stateFlow.update {
      it.copy(
        isBotActive = false,
        botStatus = BotStatus.STOPPED
      )
    }
    analysisJob?.cancel()
    analysisJob = null
    addLog("🛑 Торговый бот остановлен пользователем.", LogType.INFO)
  }

  private fun startAnalysisLoop() {
    analysisJob?.cancel()
    analysisJob = engineScope.launch {
      while (isActive && _stateFlow.value.isBotActive) {
        try {
          analyzeAndExecuteCycle()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          addLog("Ошибка цикла анализа: ${e.message}", LogType.ORDER_ERROR)
        }
        delay(3500)
      }
    }
  }

  private suspend fun analyzeAndExecuteCycle() {
    val state = _stateFlow.value
    val pair = state.selectedPair ?: return
    val sym = pair.symbol
    val config = state.strategyConfig

    val klines = strategyService.fetchKlines(sym, config.interval, 60)
    val curPrice = state.tickerData?.lastPrice ?: (if (klines.isNotEmpty()) klines.last()[4] else 0.0)

    if (curPrice <= 0.0) return

    val sig = strategyService.evaluate(sym, curPrice, klines, config)
    _stateFlow.update { it.copy(currentSignal = sig) }

    // 1. Проверка Take-Profit / Stop-Loss по открытой позиции для текущей пары
    val openPos = orderService.openPositions[sym]
    if (openPos != null) {
      if (curPrice >= openPos.takeProfitPrice) {
        val profitUsdt = (curPrice - openPos.entryPrice) * openPos.quantity
        val profitPct = ((curPrice - openPos.entryPrice) / openPos.entryPrice) * 100.0
        val reason = "Take-Profit достигнут (+${"%.2f".format(Locale.US, profitPct)}%)"
        executeClosePosition(sym, curPrice, reason, isProfit = true, profitUsdt = profitUsdt, profitPct = profitPct)
      } else if (curPrice <= openPos.stopLossPrice) {
        val lossUsdt = (curPrice - openPos.entryPrice) * openPos.quantity
        val lossPct = ((curPrice - openPos.entryPrice) / openPos.entryPrice) * 100.0
        val reason = "Stop-Loss достигнут (${"%.2f".format(Locale.US, lossPct)}%)"
        executeClosePosition(sym, curPrice, reason, isProfit = false, profitUsdt = lossUsdt, profitPct = lossPct)
      }
      return
    }

    // 2. Логирование прогресса анализа (если нет открытой позиции)
    if (sig.score < config.minScoreThreshold) {
      addLog("Анализ $sym: score ${sig.score}%, порог ${config.minScoreThreshold}%, жду сигнал...", LogType.INFO)
      _stateFlow.update { it.copy(botStatus = BotStatus.ANALYSIS) }
      return
    }

    // 3. Сигнал преодолел порог стратегии
    val creds = storageService.getCredentials()
    if (creds == null || creds.apiKey.isBlank() || creds.secretKey.isBlank()) {
      addLog("⚠️ Сигнал ${sig.action} (score: ${sig.score}%), но API ключи не настроены!", LogType.ORDER_ERROR)
      return
    }

    if (sig.action == SignalAction.BUY_LONG && !orderService.hasOpenPosition(sym)) {
      addLog("🎯 Сигнал LONG найден, score: ${sig.score}%, открываю сделку...", LogType.SIGNAL)
      _stateFlow.update { it.copy(botStatus = BotStatus.LONG) }

      val amount = state.positionAmountUsdt.coerceAtLeast(15.0)
      val targetQty = amount / sig.currentPrice

      val res = orderService.placeOrder(
        apiKey = creds.apiKey,
        secretKey = creds.secretKey,
        symbol = sym,
        side = "BUY",
        type = "MARKET",
        quantity = targetQty,
        price = sig.currentPrice,
        pairInfo = pair
      )

      if (res.isSuccess) {
        val fillPrice = if (res.price > 0) res.price else sig.currentPrice
        val fillQty = if (res.executedQty > 0) res.executedQty else targetQty
        val rec = TradeRecord(
          id = res.orderId ?: System.currentTimeMillis().toString(),
          symbol = sym,
          side = "BUY",
          entryPrice = fillPrice,
          quantity = fillQty,
          usdtAmount = amount,
          stopLossPrice = sig.recommendedStopLoss,
          takeProfitPrice = sig.recommendedTakeProfit,
          score = sig.score,
          entryTime = System.currentTimeMillis()
        )
        orderService.recordTrade(rec)
        syncPositionsAndHistory()
        addLog(
          "✅ Сделка открыта: BUY $sym ${"%.5f".format(Locale.US, fillQty)} по ${"%.2f".format(Locale.US, fillPrice)}",
          LogType.ORDER_SUCCESS
        )
        // Триггер анимации вспышки рамки при успешном открытии сделки
        _stateFlow.update {
          it.copy(
            lastSuccessEvent = TradeFlashEvent(
              id = rec.id,
              type = TradeFlashType.OPEN_POSITION,
              message = "BUY $sym"
            )
          )
        }
        refreshBalance()
      } else {
        addLog("❌ Ошибка ордера: ${res.errorMessage}", LogType.ORDER_ERROR)
      }
    } else if (sig.action == SignalAction.SELL_SPOT && orderService.hasOpenPosition(sym)) {
      addLog("🎯 Сигнал SELL Spot (score: ${sig.score}%), фиксация позиции...", LogType.SIGNAL)
      val pos = orderService.openPositions[sym]
      if (pos != null) {
        val profitUsdt = (curPrice - pos.entryPrice) * pos.quantity
        val profitPct = ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        executeClosePosition(
          sym,
          curPrice,
          "Сигнал SELL Spot (score: ${sig.score}%)",
          isProfit = profitUsdt >= 0,
          profitUsdt = profitUsdt,
          profitPct = profitPct
        )
      }
    }
  }

  private suspend fun executeClosePosition(
    sym: String,
    exitPrice: Double,
    reason: String,
    isProfit: Boolean,
    profitUsdt: Double,
    profitPct: Double
  ) {
    val pos = orderService.openPositions[sym] ?: return
    val creds = storageService.getCredentials()

    if (creds != null && creds.apiKey.isNotBlank()) {
      val res = orderService.placeOrder(
        apiKey = creds.apiKey,
        secretKey = creds.secretKey,
        symbol = sym,
        side = "SELL",
        type = "MARKET",
        quantity = pos.quantity,
        price = exitPrice,
        pairInfo = _stateFlow.value.selectedPair
      )
      if (!res.isSuccess) {
        addLog("⚠️ Ошибка закрытия ордера на бирже: ${res.errorMessage}. Закрываем локально.", LogType.ORDER_ERROR)
      }
    }

    orderService.closePosition(sym, exitPrice, reason)
    syncPositionsAndHistory()

    val pnlSign = if (profitUsdt >= 0) "+" else ""
    val pnlPctStr = "${pnlSign}${"%.2f".format(Locale.US, profitPct)}%"
    val pnlUsdtStr = "${pnlSign}${"%.2f".format(Locale.US, profitUsdt)} USDT"

    val flashType = if (isProfit) TradeFlashType.PROFIT else TradeFlashType.LOSS

    if (isProfit) {
      addLog("✅ $reason: закрыто с прибылью $pnlPctStr ($pnlUsdtStr)", LogType.PROFIT)
    } else {
      addLog("🛑 $reason: закрыто с убытком $pnlPctStr ($pnlUsdtStr)", LogType.LOSS)
    }

    _stateFlow.update {
      it.copy(
        sessionRealizedPnlUsdt = it.sessionRealizedPnlUsdt + profitUsdt,
        botStatus = BotStatus.ANALYSIS,
        lastSuccessEvent = TradeFlashEvent(
          id = "${pos.id}_closed_${System.currentTimeMillis()}",
          type = flashType,
          message = "$reason ($pnlPctStr)"
        )
      )
    }
    refreshBalance()
  }

  fun emergencyCloseAll() {
    engineScope.launch {
      val creds = storageService.getCredentials()
      val curPositions = orderService.openPositions.values.toList()
      if (curPositions.isEmpty()) {
        addLog("Экстренное закрытие: нет открытых позиций", LogType.INFO)
        return@launch
      }

      for (pos in curPositions) {
        val curPrice = _stateFlow.value.tickerData?.lastPrice ?: pos.entryPrice
        val profitUsdt = (curPrice - pos.entryPrice) * pos.quantity
        val profitPct = ((curPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        executeClosePosition(pos.symbol, curPrice, "ЭКСТРЕННОЕ РУЧНОЕ ЗАКРЫТИЕ", profitUsdt >= 0, profitUsdt, profitPct)
      }
    }
  }

  private fun syncPositionsAndHistory() {
    _stateFlow.update {
      it.copy(
        openPositions = HashMap(orderService.openPositions),
        tradeHistory = ArrayList(orderService.tradeHistory)
      )
    }
  }

  fun addLog(message: String, type: LogType = LogType.INFO) {
    _stateFlow.update { current ->
      val newLogs = (listOf(LogEvent(message = message, type = type)) + current.logs).take(40)
      current.copy(logs = newLogs)
    }
  }
}
