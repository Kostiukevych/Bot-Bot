package com.example.service

import android.content.Context
import com.example.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GridBotEngine(private val context: Context) {

  private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var monitorJob: Job? = null
  private var tickerJob: Job? = null

  val storageService = SecureStorageService(context)
  val marketService = BinanceMarketService()
  val wsService = BinanceWebSocketService()
  val orderService = OrderExecutionService()

  private val _stateFlow = MutableStateFlow(GridBotState())
  val stateFlow: StateFlow<GridBotState> = _stateFlow.asStateFlow()

  // Отслеживание накопленного объёма купленного актива на исполненных уровнях (для закрытия по рынку)
  private var accumulatedFilledBaseQty: Double = 0.0
  private var pairInfoCache: TradingPair? = null

  init {
    addLog("Модуль GridBotEngine инициализирован", isHighlight = true)
    // Подписка на тикер для обновления текущей цены и peakPrice
    observeTicker()
  }

  private fun observeTicker() {
    tickerJob?.cancel()
    tickerJob = engineScope.launch {
      wsService.tickerFlow.collect { ticker ->
        if (ticker != null) {
          val curPrice = ticker.lastPrice
          val currentState = _stateFlow.value

          if (curPrice > 0.0) {
            val newPeak = if (currentState.isActive) {
              max(currentState.peakPrice, curPrice)
            } else {
              curPrice
            }

            _stateFlow.update {
              it.copy(
                currentPrice = curPrice,
                peakPrice = newPeak
              )
            }

            // Если сетка активна — проверяем условие трейлинга
            if (currentState.isActive) {
              checkTrailingCondition(curPrice, newPeak)
            }
          }
        }
      }
    }
  }

  fun updateConfig(config: GridConfig) {
    _stateFlow.update { it.copy(config = config) }
  }

  /**
   * Настройка границ сетки от текущей цены по заданному проценту
   */
  fun calculateBoundsFromPercent(rangePercent: Double, currentPrice: Double? = null): Pair<Double, Double> {
    val price = currentPrice ?: _stateFlow.value.currentPrice
    if (price <= 0.0) return Pair(0.0, 0.0)
    val lower = price * (1.0 - rangePercent / 100.0)
    val upper = price * (1.0 + rangePercent / 100.0)
    return Pair(lower, upper)
  }

  /**
   * 1. АКТИВАЦИЯ СЕТКИ
   */
  fun startGrid(config: GridConfig) {
    engineScope.launch {
      if (_stateFlow.value.isActive) {
        addLog("Сетка уже запущена", isError = true)
        return@launch
      }

      val creds = storageService.getCredentials()
      if (creds == null || creds.apiKey.isBlank() || creds.secretKey.isBlank()) {
        val err = "API ключи Binance Testnet не настроены! Перейдите в Настройки API."
        addLog(err, isError = true)
        _stateFlow.update { it.copy(errorMessage = err) }
        return@launch
      }

      val symbol = config.symbol.uppercase().trim()
      wsService.subscribeToTicker(symbol)

      // Загрузка параметров пары для фильтров LOT_SIZE и MIN_NOTIONAL
      try {
        val pairs = marketService.getTradingPairs()
        pairInfoCache = pairs.find { it.symbol.equals(symbol, ignoreCase = true) }
      } catch (_: Exception) {}

      val pair = pairInfoCache ?: TradingPair(
        symbol = symbol,
        baseAsset = symbol.replace("USDT", ""),
        quoteAsset = "USDT",
        status = "TRADING",
        minQty = 0.0001,
        maxQty = 9000.0,
        stepSize = 0.0001,
        minNotional = 10.0
      )

      val curPrice = _stateFlow.value.currentPrice
      if (curPrice <= 0.0) {
        val err = "Ожидание тикера цены от Binance..."
        addLog(err, isError = true)
        _stateFlow.update { it.copy(errorMessage = err) }
        return@launch
      }

      val effectiveLower = if (config.lowerBound > 0.0) config.lowerBound else curPrice * (1.0 - config.rangePercent / 100.0)
      val effectiveUpper = if (config.upperBound > 0.0) config.upperBound else curPrice * (1.0 + config.rangePercent / 100.0)

      if (effectiveUpper <= effectiveLower) {
        val err = "Ошибка диапазона: верхняя граница должна быть выше нижней"
        addLog(err, isError = true)
        _stateFlow.update { it.copy(errorMessage = err) }
        return@launch
      }

      val finalConfig = config.copy(
        lowerBound = effectiveLower,
        upperBound = effectiveUpper
      )

      // Рассчитываем уровни цены с учетом соотношения BUY / SELL уровней (buySellRatio)
      // В нейтральном положении 50/50: половина BUY под текущей ценой, половина SELL над текущей ценой
      val n = finalConfig.levelCount.coerceIn(3, 50)
      val ratio = finalConfig.buySellRatio.coerceIn(0.1f, 0.9f)
      val buyCount = (n * ratio).roundToInt().coerceIn(1, n - 1)
      val sellCount = (n - buyCount).coerceAtLeast(1)

      val capitalPerLevel = if (finalConfig.isAutoCapitalPerLevel) {
        finalConfig.totalInvestmentUsdt / n
      } else {
        finalConfig.totalInvestmentUsdt * (finalConfig.capitalPercentPerLevel / 100.0)
      }

      val levels = mutableListOf<GridLevel>()

      // 1. BUY уровни (зеленые, ниже текущей цены)
      val buyStep = (curPrice - effectiveLower) / buyCount
      for (i in 0 until buyCount) {
        val p = effectiveLower + i * buyStep
        val rawQty = capitalPerLevel / p
        val qty = roundQty(rawQty, pair.stepSize, pair.minQty)
        levels.add(
          GridLevel(
            index = i,
            price = roundPrice(p),
            side = "BUY",
            quantity = qty,
            status = GridLevelStatus.PENDING_BUY
          )
        )
      }

      // 2. SELL уровни (красные, выше текущей цены)
      val sellStep = (effectiveUpper - curPrice) / sellCount
      for (j in 0 until sellCount) {
        val p = curPrice + (j + 1) * sellStep
        val rawQty = capitalPerLevel / p
        val qty = roundQty(rawQty, pair.stepSize, pair.minQty)
        levels.add(
          GridLevel(
            index = buyCount + j,
            price = roundPrice(p),
            side = "SELL",
            quantity = qty,
            status = GridLevelStatus.PENDING_SELL
          )
        )
      }

      val sortedLevels = levels.sortedBy { it.price }.mapIndexed { idx, lvl ->
        lvl.copy(index = idx)
      }

      // Инициализируем peakPrice
      val initialPeak = max(curPrice, effectiveUpper)
      accumulatedFilledBaseQty = 0.0

      _stateFlow.update {
        it.copy(
          isActive = true,
          config = finalConfig,
          peakPrice = initialPeak,
          levels = sortedLevels,
          errorMessage = null,
          totalProfitUsdt = 0.0,
          completedGrids = 0
        )
      }

      addLog("🚀 Активация сетки ${finalConfig.direction.name} по $symbol: диапазон [${formatPrice(effectiveLower)} - ${formatPrice(effectiveUpper)}], $n уровней (BUY: $buyCount, SELL: $sellCount), капитал: ${finalConfig.totalInvestmentUsdt} USDT", isHighlight = true)

      // Выставление начальных лимитных ордеров
      placeInitialOrders(creds.apiKey, creds.secretKey, pair, curPrice, finalConfig.direction)

      // Обновление баланса после выставления ордеров
      refreshBalanceAsync()

      // Запуск циклического мониторинга исполнения ордеров
      startMonitoringLoop(creds.apiKey, creds.secretKey, pair)
    }
  }

  private suspend fun placeInitialOrders(
    apiKey: String,
    secretKey: String,
    pair: TradingPair,
    currentPrice: Double,
    direction: GridDirection
  ) = withContext(Dispatchers.IO) {
    val currentLevels = _stateFlow.value.levels.toMutableList()
    var activeCount = 0

    for (i in currentLevels.indices) {
      val level = currentLevels[i]
      val shouldPlace = if (direction == GridDirection.LONG) {
        // Для LONG: на уровнях НИЖЕ текущей цены выставляем LIMIT BUY
        level.price < currentPrice
      } else {
        // Для SHORT: на уровнях ВЫШЕ текущей цены выставляем LIMIT SELL
        level.price > currentPrice
      }

      if (shouldPlace) {
        val result = orderService.placeOrder(
          apiKey = apiKey,
          secretKey = secretKey,
          symbol = pair.symbol,
          side = level.side,
          type = "LIMIT",
          quantity = level.quantity,
          price = level.price,
          pairInfo = pair
        )

        if (result.isSuccess && result.orderId != null) {
          level.orderId = result.orderId.toLongOrNull()
          activeCount++
          addLog("Уровень ${i + 1} выставлен: ${level.side} ${formatQty(level.quantity)} по цене ${formatPrice(level.price)}")
        } else {
          addLog("Ошибка выставления уровня ${i + 1}: ${result.errorMessage}", isError = true)
        }
      }
    }

    _stateFlow.update {
      it.copy(
        levels = currentLevels,
        activeOrderCount = activeCount
      )
    }
  }

  /**
   * 2. МОНИТОРИНГ ИСПОЛНЕНИЯ УРОВНЕЙ И ВЫСТАВЛЕНИЕ ВСТРЕЧНЫХ ОРДЕРОВ (BUY LOW / SELL HIGH)
   */
  private fun startMonitoringLoop(apiKey: String, secretKey: String, pair: TradingPair) {
    monitorJob?.cancel()
    monitorJob = engineScope.launch {
      while (isActive && _stateFlow.value.isActive) {
        delay(2500)
        try {
          checkLevelsExecution(apiKey, secretKey, pair)
        } catch (e: Exception) {
          // тихо подавляем сетевые сбои в цикле
        }
      }
    }
  }

  private suspend fun checkLevelsExecution(apiKey: String, secretKey: String, pair: TradingPair) = withContext(Dispatchers.IO) {
    val state = _stateFlow.value
    if (!state.isActive) return@withContext

    val levels = state.levels.map { it.copy() }.toMutableList()
    var stateChanged = false
    var newCompletedGrids = state.completedGrids
    var newProfit = state.totalProfitUsdt

    for (i in levels.indices) {
      val level = levels[i]
      val oId = level.orderId ?: continue

      if (level.status == GridLevelStatus.PENDING_BUY || level.status == GridLevelStatus.PENDING_SELL) {
        val orderStatus = marketService.getOrderStatus(apiKey, secretKey, pair.symbol, oId)

        if (orderStatus.equals("FILLED", ignoreCase = true)) {
          stateChanged = true
          val isLong = state.config.direction == GridDirection.LONG

          if (level.side == "BUY") {
            // BUY исполнен
            level.status = GridLevelStatus.FILLED
            level.orderId = null
            accumulatedFilledBaseQty += level.quantity
            addLog("Уровень ${i + 1} исполнен: BUY ${formatQty(level.quantity)} ${pair.baseAsset} по ${formatPrice(level.price)}", isHighlight = true)

            // Выставляем встречный SELL на уровне i + 1 (для LONG) или i - 1 (для SHORT)
            val targetIdx = if (isLong) i + 1 else i - 1
            if (targetIdx in levels.indices) {
              val targetLevel = levels[targetIdx]
              val sellPrice = targetLevel.price
              val sellRes = orderService.placeOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair.symbol,
                side = "SELL",
                type = "LIMIT",
                quantity = level.quantity,
                price = sellPrice,
                pairInfo = pair
              )

              if (sellRes.isSuccess && sellRes.orderId != null) {
                targetLevel.orderId = sellRes.orderId.toLongOrNull()
                targetLevel.side = "SELL"
                targetLevel.status = GridLevelStatus.PENDING_SELL
                targetLevel.entryPrice = level.price
                addLog("Выставлен тейк-профит SELL на уровне ${targetIdx + 1} по ${formatPrice(sellPrice)}")
              } else {
                addLog("Сбой выставления SELL на уровне ${targetIdx + 1}: ${sellRes.errorMessage}", isError = true)
              }
            }
          } else if (level.side == "SELL") {
            // SELL исполнен
            level.status = GridLevelStatus.FILLED
            level.orderId = null
            accumulatedFilledBaseQty = max(0.0, accumulatedFilledBaseQty - level.quantity)

            val entryP = level.entryPrice ?: (level.price - state.config.stepSizePrice)
            val levelProfit = max(0.0, (level.price - entryP) * level.quantity)
            newProfit += levelProfit
            newCompletedGrids++

            addLog("Уровень ${i + 1} исполнен: SELL ${formatQty(level.quantity)} ${pair.baseAsset} по ${formatPrice(level.price)} (Профит: +${formatUsdt(levelProfit)} USDT)", isHighlight = true)

            // Выставляем обратный BUY на уровне i - 1 (для LONG) или i + 1 (для SHORT)
            val targetIdx = if (isLong) i - 1 else i + 1
            if (targetIdx in levels.indices) {
              val targetLevel = levels[targetIdx]
              val buyPrice = targetLevel.price
              val buyRes = orderService.placeOrder(
                apiKey = apiKey,
                secretKey = secretKey,
                symbol = pair.symbol,
                side = "BUY",
                type = "LIMIT",
                quantity = level.quantity,
                price = buyPrice,
                pairInfo = pair
              )

              if (buyRes.isSuccess && buyRes.orderId != null) {
                targetLevel.orderId = buyRes.orderId.toLongOrNull()
                targetLevel.side = "BUY"
                targetLevel.status = GridLevelStatus.PENDING_BUY
                addLog("Выставлен повторный BUY на уровне ${targetIdx + 1} по цене ${formatPrice(buyPrice)}")
              }
            }
          }
        }
      }
    }

    if (stateChanged) {
      val activeCount = levels.count { it.orderId != null }
      _stateFlow.update {
        it.copy(
          levels = levels,
          completedGrids = newCompletedGrids,
          totalProfitUsdt = newProfit,
          activeOrderCount = activeCount
        )
      }
      refreshBalanceAsync()
    }
  }

  /**
   * 4. ТРЕЙЛИНГ: ЕСЛИ currentPrice >= gridUpperBound * (1 + trailingTriggerPercent/100)
   */
  private fun checkTrailingCondition(currentPrice: Double, peakPrice: Double) {
    val state = _stateFlow.value
    if (!state.isActive) return

    val config = state.config
    val gridUpper = config.upperBound
    val gridLower = config.lowerBound
    if (gridUpper <= 0.0 || gridLower <= 0.0) return

    val triggerMultiplier = 1.0 + (config.trailingTriggerPercent / 100.0)
    val triggerPrice = gridUpper * triggerMultiplier

    if (currentPrice >= triggerPrice) {
      // Расчет новой верхней границы
      val newUpperBound = peakPrice * (1.0 - config.trailingOffsetPercent / 100.0)

      // ВАЖНО: newUpperBound должен быть строго БОЛЬШЕ текущей нижней границы сетки
      // и строго МЕНЬШЕ peakPrice
      if (newUpperBound <= gridLower || newUpperBound >= peakPrice) {
        addLog("Недостаточно диапазона для трейлинга (newUpperBound ${formatPrice(newUpperBound)} <= lowerBound ${formatPrice(gridLower)})", isError = true)
        return
      }

      val delta = newUpperBound - gridUpper
      if (delta <= 0.0) return

      // Запуск пересчета сетки
      recalculateGridTrailing(delta, newUpperBound, gridLower + delta)
    }
  }

  private fun recalculateGridTrailing(delta: Double, newUpper: Double, newLower: Double) {
    engineScope.launch {
      val state = _stateFlow.value
      val creds = storageService.getCredentials() ?: return@launch
      val pair = pairInfoCache ?: return@launch
      val symbol = state.config.symbol

      addLog("⚡ Триггер трейлинга сработал: пик ${formatPrice(state.peakPrice)}, новая верхняя граница ${formatPrice(newUpper)}, сдвиг +${formatPrice(delta)}", isHighlight = true)

      // 1. Отменяем все еще не исполненные ордера сетки за пределами нового диапазона
      for (level in state.levels) {
        val oId = level.orderId
        if (oId != null && (level.status == GridLevelStatus.PENDING_BUY || level.status == GridLevelStatus.PENDING_SELL)) {
          try {
            marketService.cancelOrder(creds.apiKey, creds.secretKey, symbol, oId)
          } catch (_: Exception) {}
        }
      }

      // 2. Пересчитываем уровни внутри нового диапазона
      val n = state.config.levelCount
      val priceStep = (newUpper - newLower) / (n - 1)
      val curPrice = state.currentPrice
      val capitalPerLevel = if (state.config.isAutoCapitalPerLevel) {
        state.config.totalInvestmentUsdt / n
      } else {
        state.config.totalInvestmentUsdt * (state.config.capitalPercentPerLevel / 100.0)
      }

      val newLevels = mutableListOf<GridLevel>()
      for (i in 0 until n) {
        val p = newLower + i * priceStep
        val rawQty = capitalPerLevel / p
        val qty = roundQty(rawQty, pair.stepSize, pair.minQty)
        val side = if (state.config.direction == GridDirection.LONG) {
          if (p < curPrice) "BUY" else "SELL"
        } else {
          if (p > curPrice) "SELL" else "BUY"
        }
        val status = if (side == "BUY") GridLevelStatus.PENDING_BUY else GridLevelStatus.PENDING_SELL

        newLevels.add(
          GridLevel(
            index = i,
            price = roundPrice(p),
            side = side,
            quantity = qty,
            status = status
          )
        )
      }

      val updatedConfig = state.config.copy(
        upperBound = newUpper,
        lowerBound = newLower
      )

      _stateFlow.update {
        it.copy(
          config = updatedConfig,
          levels = newLevels,
          recalculationCount = it.recalculationCount + 1,
          lastRecalculationTimestamp = System.currentTimeMillis(),
          lastFlashTrigger = System.currentTimeMillis()
        )
      }

      // 3. Выставляем новые LIMIT-ордера на освободившихся уровнях
      placeInitialOrders(creds.apiKey, creds.secretKey, pair, curPrice, state.config.direction)
    }
  }

  /**
   * 7. ОСТАНОВИТЬ СЕТКУ
   * @param marketClose true: отменяет ордера И рыночно закрывает то, что успело исполниться
   *                    false: отменяет ВСЕ еще не исполненные ордера, оставляет купленный актив
   */
  fun stopGrid(marketClose: Boolean = false) {
    engineScope.launch {
      val state = _stateFlow.value
      monitorJob?.cancel()

      val creds = storageService.getCredentials()
      val symbol = state.config.symbol
      val pair = pairInfoCache

      if (creds != null) {
        // Отмена всех открытых лимитных ордеров сетки
        for (level in state.levels) {
          val oId = level.orderId
          if (oId != null) {
            try {
              marketService.cancelOrder(creds.apiKey, creds.secretKey, symbol, oId)
            } catch (_: Exception) {}
          }
        }

        if (marketClose && accumulatedFilledBaseQty > 0.0 && pair != null) {
          val qtyToSell = roundQty(accumulatedFilledBaseQty, pair.stepSize, pair.minQty)
          if (qtyToSell >= pair.minQty) {
            addLog("Рыночное закрытие накопленной позиции: SELL $qtyToSell ${pair.baseAsset}...", isHighlight = true)
            val res = orderService.placeOrder(
              apiKey = creds.apiKey,
              secretKey = creds.secretKey,
              symbol = symbol,
              side = "SELL",
              type = "MARKET",
              quantity = qtyToSell,
              pairInfo = pair
            )
            if (res.isSuccess) {
              addLog("Позиция сетки успешно закрыта по рынку!", isHighlight = true)
            } else {
              addLog("Сбой рыночного закрытия: ${res.errorMessage}", isError = true)
            }
          }
        }
      }

      accumulatedFilledBaseQty = 0.0

      _stateFlow.update {
        it.copy(
          isActive = false,
          activeOrderCount = 0,
          levels = emptyList()
        )
      }

      refreshBalanceAsync()

      if (marketClose) {
        addLog("🛑 Сетка остановлена и позиции закрыты по рынку", isHighlight = true)
      } else {
        addLog("🛑 Сетка остановлена пользователем — открытые ордера отменены, купленный актив сохранён", isHighlight = true)
      }
    }
  }

  fun refreshBalanceAsync() {
    engineScope.launch {
      try {
        com.example.MyApplication.instance.botEngine.refreshBalance()
      } catch (_: Exception) {}
    }
  }

  fun addLog(msg: String, isHighlight: Boolean = false, isError: Boolean = false) {
    val event = GridLogEvent(message = msg, isHighlight = isHighlight, isError = isError)
    _stateFlow.update {
      val list = it.logs.toMutableList()
      list.add(0, event)
      if (list.size > 100) list.removeAt(list.lastIndex)
      it.copy(logs = list)
    }
  }

  private fun roundQty(qty: Double, stepSize: Double, minQty: Double): Double {
    val effStep = if (stepSize > 0.0) stepSize else 0.0001
    val precision = when {
      effStep >= 1.0 -> 0
      effStep >= 0.1 -> 1
      effStep >= 0.01 -> 2
      effStep >= 0.001 -> 3
      effStep >= 0.0001 -> 4
      effStep >= 0.00001 -> 5
      else -> 6
    }
    val factor = Math.pow(10.0, precision.toDouble())
    val rounded = Math.floor(qty * factor) / factor
    return max(rounded, minQty)
  }

  private fun roundPrice(price: Double): Double {
    return if (price >= 1.0) {
      Math.round(price * 100.0) / 100.0
    } else {
      Math.round(price * 10000.0) / 10000.0
    }
  }

  private fun formatPrice(price: Double): String {
    return if (price >= 1.0) "%.2f".format(Locale.US, price) else "%.4f".format(Locale.US, price)
  }

  private fun formatQty(qty: Double): String {
    return "%.4f".format(Locale.US, qty)
  }

  private fun formatUsdt(v: Double): String {
    return "%.2f".format(Locale.US, v)
  }
}
