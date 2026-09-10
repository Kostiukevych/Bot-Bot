package com.example.service

import android.content.Context
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
    maxDepositRiskPercent = 25.0,
    useAtrSlTp = true,
    atrPeriod = 14,
    atrSlMultiplier = 1.5,
    atrTpMultiplier = 3.0,
    trailingEnabled = false,
    trailingActivationPercent = 1.0,
    trailingStepPercent = 0.5,
    maxConsecutiveLosses = 3,
    dailyLossLimitPercent = 5.0,
    maxSpreadPercent = 0.15,
    scannerEnabled = false,
    scannerTopN = 20,
    scannerIntervalSeconds = 60
  ),
  val positionAmountUsdt: Double = 50.0,
  val positionPercent: Float = 25f,
  val consecutiveLosses: Int = 0,
  val dailySessionStartBalance: Double? = null,
  val dailySessionStartDay: Int? = null,
  val isCircuitBreakerTripped: Boolean = false,
  val circuitBreakerCooldownUntil: Long? = null,
  val circuitBreakerReason: String? = null,
  val lastScanResults: List<PairScanResult> = emptyList(),
  val lastScanTimestamp: Long? = null,
  val nextScanInSeconds: Int? = null,
  val lastError: String? = null,
  val lastSuccessEvent: TradeFlashEvent? = null
)

class TradingBotEngine(private val context: Context) {

  companion object {
    const val TAKER_FEE_PERCENT = 0.1
  }

  private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var analysisJob: Job? = null
  private var tickerJob: Job? = null
  private var scannerJob: Job? = null
  private val retryJobs = ConcurrentHashMap<String, Job>()

  fun hasOpenPosition(symbol: String): Boolean {
    val key = symbol.uppercase().trim()
    val pos = orderService.openPositions[key] ?: return false
    return pos.status == TradeRecord.STATUS_OPEN ||
      pos.status == TradeRecord.STATUS_CLOSE_PENDING_RETRY ||
      pos.status == TradeRecord.STATUS_ERROR
  }

  val storageService = SecureStorageService(context)
  val authService = BinanceAuthService()
  val marketService = BinanceMarketService()
  val wsService = BinanceWebSocketService()
  val strategyService = TradingStrategyService()
  val orderService = OrderExecutionService()
  val scannerService = MarketScannerService(strategyService)

  private var cachedAllPairs: List<TradingPair> = emptyList()

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

  fun setScannerEnabled(enabled: Boolean) {
    val newConfig = _stateFlow.value.strategyConfig.copy(scannerEnabled = enabled)
    updateStrategyConfig(newConfig)
    if (enabled && _stateFlow.value.isBotActive) {
      startScannerLoop()
    } else {
      scannerJob?.cancel()
      scannerJob = null
      _stateFlow.update { it.copy(nextScanInSeconds = null) }
    }
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
      cachedAllPairs = pairs
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
    val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val curBal = (_stateFlow.value.freeUsdt ?: 0.0) + (_stateFlow.value.lockedUsdt ?: 0.0)
    val startBal = if (_stateFlow.value.dailySessionStartBalance == null || _stateFlow.value.dailySessionStartDay != currentDay) {
      if (curBal > 0.0) curBal else (_stateFlow.value.totalUsdt ?: 1000.0)
    } else {
      _stateFlow.value.dailySessionStartBalance
    }

    _stateFlow.update {
      it.copy(
        isBotActive = true,
        botStatus = if (it.openPositions.isNotEmpty()) BotStatus.LONG else BotStatus.ANALYSIS,
        dailySessionStartBalance = startBal,
        dailySessionStartDay = currentDay
      )
    }
    addLog("🚀 Торговый бот запущен. Анализ рынка в фоне активирован. Дневной баланс отсчета: ${"%.2f".format(Locale.US, startBal)} USDT", LogType.INFO)
    startAnalysisLoop()
    if (_stateFlow.value.strategyConfig.scannerEnabled) {
      startScannerLoop()
    }
  }

  fun stop() {
    _stateFlow.update {
      it.copy(
        isBotActive = false,
        botStatus = BotStatus.STOPPED,
        nextScanInSeconds = null
      )
    }
    analysisJob?.cancel()
    analysisJob = null
    scannerJob?.cancel()
    scannerJob = null
    addLog("🛑 Торговый бот остановлен пользователем.", LogType.INFO)
  }

  private fun startScannerLoop() {
    scannerJob?.cancel()
    scannerJob = engineScope.launch {
      while (isActive && _stateFlow.value.isBotActive && _stateFlow.value.strategyConfig.scannerEnabled) {
        runScanCycle()
        val intervalSec = _stateFlow.value.strategyConfig.scannerIntervalSeconds.coerceAtLeast(10)
        for (secLeft in intervalSec downTo 1) {
          if (!isActive || !_stateFlow.value.isBotActive || !_stateFlow.value.strategyConfig.scannerEnabled) break
          _stateFlow.update { it.copy(nextScanInSeconds = secLeft) }
          delay(1000L)
        }
      }
      _stateFlow.update { it.copy(nextScanInSeconds = null) }
    }
  }

  private suspend fun runScanCycle() {
    if (orderService.openPositions.values.any { it.status in listOf(TradeRecord.STATUS_OPEN, TradeRecord.STATUS_CLOSE_PENDING_RETRY, TradeRecord.STATUS_ERROR) }) {
      // Позиция уже открыта или ожидает повтора закрытия, дожидаемся её закрытия по TP/SL как обычно, сканер не переключает пару
      return
    }

    val allPairs = if (cachedAllPairs.isNotEmpty()) cachedAllPairs else {
      try {
        val p = marketService.getTradingPairs()
        cachedAllPairs = p
        p
      } catch (e: Exception) {
        addLog("Ошибка получения списка пар для сканера: ${e.message}", LogType.ORDER_ERROR)
        emptyList()
      }
    }

    if (allPairs.isEmpty()) return

    val config = _stateFlow.value.strategyConfig
    try {
      val results = scannerService.scanTopPairs(marketService, allPairs, config)
      _stateFlow.update {
        it.copy(
          lastScanResults = results,
          lastScanTimestamp = System.currentTimeMillis()
        )
      }

      val best = results.firstOrNull { it.signal.action != SignalAction.HOLD && it.signal.score >= config.minScoreThreshold }
      if (best != null && best.pair.symbol != _stateFlow.value.selectedPair?.symbol) {
        selectPair(best.pair)
        addLog("🔍 Сканер: переключение на ${best.pair.symbol}, score ${best.signal.score}%", LogType.SIGNAL)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      addLog("Ошибка цикла сканера: ${e.message}", LogType.ORDER_ERROR)
    }
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
    // Защита Circuit Breaker: полная блокировка торговых операций до ручного сброса
    if (state.isCircuitBreakerTripped) return

    val pair = state.selectedPair ?: return
    val sym = pair.symbol
    val config = state.strategyConfig

    val klines = strategyService.fetchKlines(sym, config.interval, 60)
    val curPrice = state.tickerData?.lastPrice ?: (if (klines.isNotEmpty()) klines.last()[4] else 0.0)

    if (curPrice <= 0.0) return

    val sig = strategyService.evaluate(sym, curPrice, klines, config)
    _stateFlow.update { it.copy(currentSignal = sig) }

    // 1. Проверка Трейлинг-стопа и Take-Profit / Stop-Loss по открытой позиции
    val openPos = orderService.openPositions[sym]
    if (openPos != null) {
      if (openPos.status == TradeRecord.STATUS_CLOSE_PENDING_RETRY || openPos.status == TradeRecord.STATUS_ERROR) {
        // Позиция уже в процессе повтора закрытия или переведена в статус ошибки
        return
      }
      // Трейлинг-стоп для сигнального бота
      if (config.trailingEnabled) {
        if (curPrice > openPos.peakPrice) {
          val newPeak = curPrice
          val profitFromEntryPct = ((newPeak - openPos.entryPrice) / openPos.entryPrice) * 100.0
          val isTrailingNowActive = openPos.trailingActive || (profitFromEntryPct >= config.trailingActivationPercent)
          var newSL = openPos.stopLossPrice
          if (isTrailingNowActive) {
            val calculatedSL = newPeak * (1.0 - (config.trailingStepPercent / 100.0))
            if (calculatedSL > openPos.stopLossPrice) {
              newSL = calculatedSL
              addLog("⚡ Трейлинг-стоп $sym: пик ${"%.2f".format(Locale.US, newPeak)}, SL подтянут до ${"%.2f".format(Locale.US, newSL)}", LogType.INFO)
            }
          }
          orderService.updatePeakPrice(sym, newPeak, newSL, isTrailingNowActive)
          syncPositionsAndHistory()
        }
      }

      val activePos = orderService.openPositions[sym] ?: openPos
      if (curPrice >= activePos.takeProfitPrice) {
        val grossUsdt = (curPrice - activePos.entryPrice) * activePos.quantity
        val exitFeeUsdt = (curPrice * activePos.quantity) * TAKER_FEE_PERCENT / 100.0
        val netProfitUsdt = grossUsdt - (activePos.entryFeeUsdt + exitFeeUsdt)
        val netPct = (netProfitUsdt / (activePos.entryPrice * activePos.quantity).coerceAtLeast(0.0001)) * 100.0
        val reason = if (activePos.trailingActive) {
          "Take-Profit / Трейлинг (+${"%.2f".format(Locale.US, netPct)}%)"
        } else {
          "Take-Profit достигнут (+${"%.2f".format(Locale.US, netPct)}%)"
        }
        executeClosePosition(sym, curPrice, reason, skipSpreadCheck = true)
      } else if (curPrice <= activePos.stopLossPrice) {
        val grossUsdt = (curPrice - activePos.entryPrice) * activePos.quantity
        val exitFeeUsdt = (curPrice * activePos.quantity) * TAKER_FEE_PERCENT / 100.0
        val netLossUsdt = grossUsdt - (activePos.entryFeeUsdt + exitFeeUsdt)
        val netPct = (netLossUsdt / (activePos.entryPrice * activePos.quantity).coerceAtLeast(0.0001)) * 100.0
        val reason = if (activePos.trailingActive) {
          "Трейлинг Stop-Loss сработал (${"%.2f".format(Locale.US, netPct)}%)"
        } else {
          "Stop-Loss достигнут (${"%.2f".format(Locale.US, netPct)}%)"
        }
        executeClosePosition(sym, curPrice, reason, skipSpreadCheck = true)
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

    if (sig.action == SignalAction.BUY_LONG && !hasOpenPosition(sym)) {
      // Фильтр спреда перед открытием новой позиции (BUY)
      val book = marketService.getBookTicker(sym)
      if (book != null && book.spreadPercent > config.maxSpreadPercent) {
        addLog("⚠️ Спред ${"%.3f".format(Locale.US, book.spreadPercent)}% > лимита ${config.maxSpreadPercent}%, ордер отменён (защита от проскальзывания)", LogType.ORDER_ERROR)
        return
      }

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
        val entryFee = amount * TAKER_FEE_PERCENT / 100.0
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
          entryTime = System.currentTimeMillis(),
          entryFeeUsdt = entryFee,
          peakPrice = max(fillPrice, sig.currentPrice),
          trailingActive = false
        )
        orderService.recordTrade(rec)
        syncPositionsAndHistory()
        addLog(
          "✅ Сделка открыта: BUY $sym ${"%.5f".format(Locale.US, fillQty)} по ${"%.2f".format(Locale.US, fillPrice)} (комиссия входа: ${"%.4f".format(Locale.US, entryFee)} USDT)",
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
    } else if (sig.action == SignalAction.SELL_SPOT && hasOpenPosition(sym)) {
      val pos = orderService.openPositions[sym]
      if (pos != null && pos.status == TradeRecord.STATUS_OPEN) {
        addLog("🎯 Сигнал SELL Spot (score: ${sig.score}%), фиксация позиции...", LogType.SIGNAL)
        executeClosePosition(
          sym = sym,
          exitPrice = curPrice,
          reason = "Сигнал SELL Spot (score: ${sig.score}%)",
          skipSpreadCheck = false
        )
      }
    }
  }

  suspend fun executeClosePosition(
    sym: String,
    exitPrice: Double,
    reason: String,
    skipSpreadCheck: Boolean = false,
    retryCount: Int = 0
  ) {
    val pos = orderService.openPositions[sym] ?: return
    val config = _stateFlow.value.strategyConfig

    // Проверка спреда только если не закрытие по TP/SL или повтор
    if (!skipSpreadCheck) {
      val book = marketService.getBookTicker(sym)
      if (book != null && book.spreadPercent > config.maxSpreadPercent) {
        addLog("⚠️ Спред ${"%.3f".format(Locale.US, book.spreadPercent)}% > лимита ${config.maxSpreadPercent}%, ордер отменён (защита от проскальзывания)", LogType.ORDER_ERROR)
        return
      }
    }

    val creds = storageService.getCredentials()
    if (creds == null || creds.apiKey.isBlank() || creds.secretKey.isBlank()) {
      pos.status = TradeRecord.STATUS_ERROR
      syncPositionsAndHistory()
      addLog("⚠️ НЕ УДАЛОСЬ закрыть позицию $sym на бирже (API ключи не настроены). Позиция ОСТАЁТСЯ открытой. Статус: ERROR.", LogType.ORDER_ERROR)
      return
    }

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

    if (res.isSuccess) {
      // 1. orderService.closePosition(...) вызывается ТОЛЬКО внутри блока if (res.isSuccess)
      val fillPrice = if (res.price > 0) res.price else exitPrice
      val exitFeeUsdt = (fillPrice * pos.quantity) * TAKER_FEE_PERCENT / 100.0
      val grossProfitUsdt = (fillPrice - pos.entryPrice) * pos.quantity
      val netProfitUsdt = grossProfitUsdt - (pos.entryFeeUsdt + exitFeeUsdt)
      val invested = (pos.entryPrice * pos.quantity).coerceAtLeast(0.0001)
      val netProfitPct = (netProfitUsdt / invested) * 100.0
      val isProfit = netProfitUsdt >= 0

      orderService.closePosition(
        symbol = sym,
        exitPrice = fillPrice,
        reason = reason,
        exitFeeUsdt = exitFeeUsdt,
        netPnlUsdt = netProfitUsdt
      )
      retryJobs[sym]?.cancel()
      retryJobs.remove(sym)
      syncPositionsAndHistory()

      val pnlSign = if (netProfitUsdt >= 0) "+" else ""
      val pnlPctStr = "${pnlSign}${"%.2f".format(Locale.US, netProfitPct)}%"
      val pnlUsdtStr = "${pnlSign}${"%.2f".format(Locale.US, netProfitUsdt)} USDT"
      val totalFeeStr = "комиссия: ${"%.3f".format(Locale.US, pos.entryFeeUsdt + exitFeeUsdt)} USDT"

      val flashType = if (isProfit) TradeFlashType.PROFIT else TradeFlashType.LOSS

      if (isProfit) {
        addLog("✅ $reason: чистая прибыль $pnlPctStr ($pnlUsdtStr, $totalFeeStr)", LogType.PROFIT)
      } else {
        addLog("🛑 $reason: чистый убыток $pnlPctStr ($pnlUsdtStr, $totalFeeStr)", LogType.LOSS)
      }

      val updatedConsecutiveLosses = if (netProfitUsdt < 0) {
        _stateFlow.value.consecutiveLosses + 1
      } else {
        0
      }

      val updatedSessionPnl = _stateFlow.value.sessionRealizedPnlUsdt + netProfitUsdt

      _stateFlow.update {
        it.copy(
          sessionRealizedPnlUsdt = updatedSessionPnl,
          consecutiveLosses = updatedConsecutiveLosses,
          botStatus = if (orderService.openPositions.isEmpty()) BotStatus.ANALYSIS else BotStatus.LONG,
          lastSuccessEvent = TradeFlashEvent(
            id = "${pos.id}_closed_${System.currentTimeMillis()}",
            type = flashType,
            message = "$reason ($pnlPctStr)"
          )
        )
      }
      // 4. Синхронизация баланса с реальным аккаунтом после подтверждённого биржей закрытия
      refreshBalance()

      // Проверка условий Circuit Breaker
      checkCircuitBreaker(updatedConsecutiveLosses)
    } else {
      // 2. Если res.isSuccess == false — НЕ закрываем позицию локально
      val errDetail = res.errorMessage ?: "Неизвестная ошибка биржи"
      if (retryCount < 5) {
        pos.status = TradeRecord.STATUS_CLOSE_PENDING_RETRY
        syncPositionsAndHistory()
        addLog(
          "⚠️ НЕ УДАЛОСЬ закрыть позицию $sym на бирже ($errDetail). Позиция ОСТАЁТСЯ открытой. Повтор через 8 секунд.",
          LogType.ORDER_ERROR
        )

        retryJobs[sym]?.cancel()
        retryJobs[sym] = engineScope.launch {
          delay(8000)
          if (!isActive) return@launch
          val currentPos = orderService.openPositions[sym]
          if (currentPos != null && currentPos.status == TradeRecord.STATUS_CLOSE_PENDING_RETRY) {
            val curPrice = _stateFlow.value.tickerData?.lastPrice ?: exitPrice
            executeClosePosition(
              sym = sym,
              exitPrice = curPrice,
              reason = reason,
              skipSpreadCheck = true,
              retryCount = retryCount + 1
            )
          }
        }
      } else {
        pos.status = TradeRecord.STATUS_ERROR
        retryJobs[sym]?.cancel()
        retryJobs.remove(sym)
        syncPositionsAndHistory()
        addLog(
          "❌ Превышен лимит (5) попыток закрытия позиции $sym на бирже ($errDetail). Позиция переведена в статус ERROR. Требуется ручное закрытие.",
          LogType.ORDER_ERROR
        )
      }
    }
  }

  fun retryClosePositionManually(symbol: String) {
    val sym = symbol.uppercase().trim()
    val pos = orderService.openPositions[sym] ?: return
    pos.status = TradeRecord.STATUS_CLOSE_PENDING_RETRY
    syncPositionsAndHistory()
    addLog("🔄 Ручной повтор закрытия позиции $sym...", LogType.INFO)

    retryJobs[sym]?.cancel()
    retryJobs[sym] = engineScope.launch {
      val curPrice = _stateFlow.value.tickerData?.lastPrice ?: pos.entryPrice
      executeClosePosition(
        sym = sym,
        exitPrice = curPrice,
        reason = "Ручной повтор закрытия",
        skipSpreadCheck = true,
        retryCount = 0
      )
    }
  }

  private fun checkCircuitBreaker(consecutiveLosses: Int) {
    val state = _stateFlow.value
    val config = state.strategyConfig
    val startBal = state.dailySessionStartBalance

    val currentBal = (state.totalUsdt ?: state.freeUsdt ?: startBal ?: 1000.0)
    val dailyLossPct = if (startBal != null && startBal > 0.0) {
      ((startBal - currentBal) / startBal) * 100.0
    } else {
      0.0
    }

    val trippedByLosses = consecutiveLosses >= config.maxConsecutiveLosses
    val trippedByDailyLimit = startBal != null && dailyLossPct >= config.dailyLossLimitPercent

    if (trippedByLosses || trippedByDailyLimit) {
      val reasonMsg = if (trippedByLosses) {
        "🛑 Circuit Breaker: бот остановлен после $consecutiveLosses убыточных сделок подряд (лимит: ${config.maxConsecutiveLosses})"
      } else {
        "🛑 Circuit Breaker: достигнут дневной лимит просадки ${"%.1f".format(Locale.US, dailyLossPct)}% (порог: ${config.dailyLossLimitPercent}%)"
      }

      stop()
      _stateFlow.update {
        it.copy(
          isCircuitBreakerTripped = true,
          circuitBreakerReason = reasonMsg
        )
      }
      addLog(reasonMsg, LogType.ORDER_ERROR)
    }
  }

  fun resetCircuitBreaker() {
    _stateFlow.update {
      it.copy(
        isCircuitBreakerTripped = false,
        consecutiveLosses = 0,
        circuitBreakerReason = null
      )
    }
    addLog("🛡️ Circuit Breaker сброшен вручную. Торговый цикл разблокирован.", LogType.INFO)
  }

  fun emergencyCloseAll() {
    engineScope.launch {
      val curPositions = orderService.openPositions.values.toList()
      if (curPositions.isEmpty()) {
        addLog("Экстренное закрытие: нет открытых позиций", LogType.INFO)
        return@launch
      }

      for (pos in curPositions) {
        val curPrice = _stateFlow.value.tickerData?.lastPrice ?: pos.entryPrice
        executeClosePosition(pos.symbol, curPrice, "ЭКСТРЕННОЕ РУЧНОЕ ЗАКРЫТИЕ", skipSpreadCheck = true)
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
