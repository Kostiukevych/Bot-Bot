package com.example.service

import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BacktestEngine {

  /**
   * Загрузка исторических свечей с постраничной пагинацией (до 5000 свечей через Binance klines).
   * 100% локальная симуляция — никаких реальных ордеров или API ключей.
   */
  suspend fun fetchHistoricalCandles(
    symbol: String,
    interval: String,
    targetCount: Int = 300,
    startTime: Long? = null,
    endTime: Long? = null,
    marketService: BinanceMarketService
  ): List<Candle> = withContext(Dispatchers.IO) {
    val cleanSymbol = symbol.uppercase().trim()
    val cleanInterval = interval.trim()
    val limitPerChunk = 1000

    if (startTime != null && endTime != null && endTime > startTime) {
      // Пагинация по явному диапазону времени
      val allCandles = mutableListOf<Candle>()
      var currentStart: Long = startTime
      val targetEnd: Long = endTime
      var safetyCounter = 0

      while (currentStart < targetEnd && safetyCounter < 10) {
        safetyCounter++
        val chunk = marketService.fetchCandles(
          symbol = cleanSymbol,
          interval = cleanInterval,
          limit = limitPerChunk,
          startTime = currentStart,
          endTime = targetEnd
        )
        if (chunk.isEmpty()) break
        allCandles.addAll(chunk)
        val lastTime = chunk.last().openTime
        if (lastTime <= currentStart) break
        currentStart = lastTime + 1L
        if (chunk.size < limitPerChunk) break
      }
      return@withContext allCandles.distinctBy { it.openTime }.sortedBy { it.openTime }
    }

    // Пагинация по количеству свечей назад во времени от текущего момента
    val neededCount = targetCount.coerceIn(50, 5000)
    val collectedCandles = mutableListOf<Candle>()
    var currentEndTime = endTime ?: System.currentTimeMillis()
    var attempts = 0

    while (collectedCandles.size < neededCount && attempts < 8) {
      attempts++
      val fetchSize = min(limitPerChunk, neededCount - collectedCandles.size)
      val chunk = marketService.fetchCandles(
        symbol = cleanSymbol,
        interval = cleanInterval,
        limit = fetchSize,
        startTime = null,
        endTime = currentEndTime
      )
      if (chunk.isEmpty()) break

      collectedCandles.addAll(chunk)
      val oldestTime = chunk.first().openTime
      if (oldestTime <= 0L || oldestTime >= currentEndTime) break
      currentEndTime = oldestTime - 1L

      if (chunk.size < fetchSize) break
    }

    val sorted = collectedCandles.distinctBy { it.openTime }.sortedBy { it.openTime }
    if (sorted.size > neededCount) {
      sorted.takeLast(neededCount)
    } else {
      sorted
    }
  }

  /**
   * 1. БЭКТЕСТ СИГНАЛЬНОГО БОТА (TradingStrategyService.evaluate)
   * Скользящее окно свечей, оценка условий стратегии, TakeProfit / StopLoss с учетом фитилей свечей,
   * учет биржевой комиссии 0.1% на вход и выход.
   */
  suspend fun runSignalBotBacktest(
    config: BacktestConfig,
    marketService: BinanceMarketService,
    strategyService: TradingStrategyService
  ): BacktestResult = withContext(Dispatchers.Default) {
    val candles = fetchHistoricalCandles(
      symbol = config.symbol,
      interval = config.interval,
      targetCount = config.candleCount,
      startTime = config.startTime,
      endTime = config.endTime,
      marketService = marketService
    )

    if (candles.size < 30) {
      return@withContext BacktestResult(
        config = config,
        candles = candles,
        tradeMarkers = emptyList(),
        gridFillMarkers = emptyList(),
        equityCurve = emptyList(),
        stats = createEmptyStats()
      )
    }

    val riskConfig = config.strategyRiskConfig ?: StrategyRiskConfig(
      interval = config.interval,
      stopLossPercent = 2.0,
      takeProfitPercent = 4.0,
      minScoreThreshold = 55,
      maxDepositRiskPercent = 25.0
    )

    var currentBalance = config.initialBalanceUsdt
    val tradeMarkers = mutableListOf<BacktestTradeMarker>()
    val equityCurve = mutableListOf<EquityPoint>()
    equityCurve.add(EquityPoint(candles.first().openTime, currentBalance))

    // Состояние открытой виртуальной позиции
    data class VirtualPosition(
      val entryPrice: Double,
      val entryTime: Long,
      val quantity: Double,
      val usdtAmount: Double,
      val stopLossPrice: Double,
      val takeProfitPrice: Double,
      val score: Int
    )

    var openPosition: VirtualPosition? = null
    val windowSize = 60
    val startIdx = min(windowSize, candles.size - 1)

    for (i in startIdx until candles.size) {
      val curCandle = candles[i]
      val curPrice = curCandle.close

      // 1. Проверка Take-Profit / Stop-Loss открытой позиции по фитилям текущей свечи (High/Low)
      val pos = openPosition
      if (pos != null) {
        val tpTriggered = curCandle.high >= pos.takeProfitPrice
        val slTriggered = curCandle.low <= pos.stopLossPrice

        if (tpTriggered || slTriggered) {
          // Если затронуты оба фитиля — определяем по направлению движения свечи (медвежья сначала SL, бычья TP)
          val isTp = if (tpTriggered && slTriggered) curCandle.close >= curCandle.open else tpTriggered
          val exitPrice = if (isTp) pos.takeProfitPrice else pos.stopLossPrice
          val isProfit = isTp

          val grossPnl = (exitPrice - pos.entryPrice) * pos.quantity
          val entryFee = pos.entryPrice * pos.quantity * 0.001
          val exitFee = exitPrice * pos.quantity * 0.001
          val netPnl = grossPnl - (entryFee + exitFee)
          val netPnlPct = (netPnl / pos.usdtAmount) * 100.0

          currentBalance += netPnl
          val reason = if (isProfit) {
            "TP +${"%.2f".format(Locale.US, riskConfig.takeProfitPercent)}%"
          } else {
            "SL -${"%.2f".format(Locale.US, riskConfig.stopLossPercent)}%"
          }

          tradeMarkers.add(
            BacktestTradeMarker(
              entryTime = pos.entryTime,
              entryPrice = pos.entryPrice,
              exitTime = curCandle.openTime,
              exitPrice = exitPrice,
              side = "BUY",
              pnlUsdt = netPnl,
              pnlPercent = netPnlPct,
              isProfit = isProfit,
              reason = reason
            )
          )

          openPosition = null
          equityCurve.add(EquityPoint(curCandle.openTime, currentBalance))
          continue
        }
      }

      // 2. Если позиции нет — прогон индикаторной стратегии evaluate() по скользящему окну
      if (openPosition == null) {
        val window = candles.subList(max(0, i - windowSize + 1), i + 1)
        val klines = window.map { c ->
          listOf(c.openTime.toDouble(), c.open, c.high, c.low, c.close, c.volume)
        }

        val signal = strategyService.evaluate(
          symbol = config.symbol,
          currentPrice = curPrice,
          klines = klines,
          config = riskConfig
        )

        if (signal.action == SignalAction.BUY_LONG && signal.score >= riskConfig.minScoreThreshold) {
          val riskPct = (riskConfig.maxDepositRiskPercent / 100.0).coerceIn(0.05, 1.0)
          val posAmount = (currentBalance * riskPct).coerceAtLeast(10.0)
          val entryPrice = curPrice
          val quantity = posAmount / entryPrice
          val entryFee = posAmount * 0.001
          currentBalance -= entryFee // комиссия на вход

          openPosition = VirtualPosition(
            entryPrice = entryPrice,
            entryTime = curCandle.openTime,
            quantity = quantity,
            usdtAmount = posAmount,
            stopLossPrice = signal.recommendedStopLoss,
            takeProfitPrice = signal.recommendedTakeProfit,
            score = signal.score
          )
        }
      }

      // Периодическая точка в эквити-кривой каждые 10 свечей
      if (i % 10 == 0) {
        val markBalance = if (openPosition != null) {
          val unrealized = (curPrice - openPosition!!.entryPrice) * openPosition!!.quantity
          currentBalance + unrealized
        } else {
          currentBalance
        }
        equityCurve.add(EquityPoint(curCandle.openTime, markBalance))
      }
    }

    // Закрываем позицию в конце теста по последней цене закрытия
    val lastPos = openPosition
    if (lastPos != null && candles.isNotEmpty()) {
      val lastCandle = candles.last()
      val exitPrice = lastCandle.close
      val grossPnl = (exitPrice - lastPos.entryPrice) * lastPos.quantity
      val exitFee = exitPrice * lastPos.quantity * 0.001
      val netPnl = grossPnl - exitFee
      val netPnlPct = (netPnl / lastPos.usdtAmount) * 100.0
      currentBalance += netPnl

      tradeMarkers.add(
        BacktestTradeMarker(
          entryTime = lastPos.entryTime,
          entryPrice = lastPos.entryPrice,
          exitTime = lastCandle.openTime,
          exitPrice = exitPrice,
          side = "BUY",
          pnlUsdt = netPnl,
          pnlPercent = netPnlPct,
          isProfit = netPnl >= 0,
          reason = "Конец периода"
        )
      )
    }

    equityCurve.add(EquityPoint(candles.last().openTime, currentBalance))

    val stats = computeStats(
      initialBalance = config.initialBalanceUsdt,
      finalBalance = currentBalance,
      tradesPnl = tradeMarkers.map { it.pnlUsdt },
      equityCurve = equityCurve,
      firstCandleClose = candles.first().close,
      lastCandleClose = candles.last().close
    )

    BacktestResult(
      config = config,
      candles = candles,
      tradeMarkers = tradeMarkers,
      gridFillMarkers = emptyList(),
      equityCurve = equityCurve,
      stats = stats
    )
  }

  /**
   * 2. БЭКТЕСТ СЕТОЧНОГО БОТА (GridBotEngine logic)
   * Генерация сетки уровней, проверка исполнения PENDING_BUY / PENDING_SELL по High/Low свечей,
   * выставление встречных уровней, учет трейлинга за пиком цены, комиссия 0.1%.
   */
  suspend fun runGridBotBacktest(
    config: BacktestConfig,
    marketService: BinanceMarketService
  ): BacktestResult = withContext(Dispatchers.Default) {
    val candles = fetchHistoricalCandles(
      symbol = config.symbol,
      interval = config.interval,
      targetCount = config.candleCount,
      startTime = config.startTime,
      endTime = config.endTime,
      marketService = marketService
    )

    if (candles.size < 10) {
      return@withContext BacktestResult(
        config = config,
        candles = candles,
        tradeMarkers = emptyList(),
        gridFillMarkers = emptyList(),
        equityCurve = emptyList(),
        stats = createEmptyStats()
      )
    }

    val gridConfig = config.gridConfig ?: GridConfig(
      symbol = config.symbol,
      tradingInterval = config.interval,
      levelCount = 8,
      rangePercent = 4.0,
      totalInvestmentUsdt = config.initialBalanceUsdt
    )

    val firstPrice = candles.first().close
    var effectiveLower = if (gridConfig.lowerBound > 0.0) gridConfig.lowerBound else firstPrice * (1.0 - gridConfig.rangePercent / 100.0)
    var effectiveUpper = if (gridConfig.upperBound > 0.0) gridConfig.upperBound else firstPrice * (1.0 + gridConfig.rangePercent / 100.0)

    if (effectiveUpper <= effectiveLower) {
      effectiveLower = firstPrice * 0.96
      effectiveUpper = firstPrice * 1.04
    }

    val n = gridConfig.levelCount.coerceIn(3, 50)
    val ratio = gridConfig.buySellRatio.coerceIn(0.1f, 0.9f)
    val buyCount = (n * ratio).roundToInt().coerceIn(1, n - 1)
    val sellCount = (n - buyCount).coerceAtLeast(1)
    val capitalPerLevel = gridConfig.totalInvestmentUsdt / n

    // Генерация начальных уровней
    var levels = mutableListOf<GridLevel>()
    val buyStep = (firstPrice - effectiveLower) / buyCount
    for (i in 0 until buyCount) {
      val p = effectiveLower + i * buyStep
      val qty = capitalPerLevel / p
      levels.add(
        GridLevel(
          index = i,
          price = p,
          side = "BUY",
          quantity = qty,
          status = GridLevelStatus.PENDING_BUY
        )
      )
    }

    val sellStep = (effectiveUpper - firstPrice) / sellCount
    for (j in 0 until sellCount) {
      val p = firstPrice + (j + 1) * sellStep
      val qty = capitalPerLevel / p
      levels.add(
        GridLevel(
          index = buyCount + j,
          price = p,
          side = "SELL",
          quantity = qty,
          status = GridLevelStatus.PENDING_SELL
        )
      )
    }

    levels = levels.sortedBy { it.price }.mapIndexed { idx, lvl -> lvl.copy(index = idx) }.toMutableList()

    var peakPrice = max(firstPrice, effectiveUpper)
    var currentBalance = config.initialBalanceUsdt
    val gridFillMarkers = mutableListOf<BacktestGridFillMarker>()
    val equityCurve = mutableListOf<EquityPoint>()
    val tradePnls = mutableListOf<Double>()

    equityCurve.add(EquityPoint(candles.first().openTime, currentBalance))

    // Проход по историческим свечам слева направо
    for (candle in candles) {
      // 1. Проверка трейлинга за пиком цены
      peakPrice = max(peakPrice, candle.high)
      val triggerMultiplier = 1.0 + (gridConfig.trailingTriggerPercent / 100.0)
      if (candle.high >= effectiveUpper * triggerMultiplier) {
        val newUpperBound = peakPrice * (1.0 - gridConfig.trailingOffsetPercent / 100.0)
        if (newUpperBound > effectiveLower && newUpperBound < peakPrice) {
          val delta = newUpperBound - effectiveUpper
          if (delta > 0.0) {
            effectiveUpper = newUpperBound
            effectiveLower += delta

            // Перерасчет сетки со сдвигом
            val step = (effectiveUpper - effectiveLower) / (n - 1)
            val newLevels = mutableListOf<GridLevel>()
            for (idx in 0 until n) {
              val p = effectiveLower + idx * step
              val q = capitalPerLevel / p
              val side = if (p < candle.close) "BUY" else "SELL"
              val st = if (side == "BUY") GridLevelStatus.PENDING_BUY else GridLevelStatus.PENDING_SELL
              newLevels.add(
                GridLevel(
                  index = idx,
                  price = p,
                  side = side,
                  quantity = q,
                  status = st
                )
              )
            }
            levels = newLevels
          }
        }
      }

      // 2. Симуляция исполнения лимитных уровней в пределах свечи (High/Low)
      for (lvlIdx in levels.indices) {
        val lvl = levels[lvlIdx]

        // Исполнение BUY уровня (Low свечи коснулся или пробил уровень вниз)
        if (lvl.status == GridLevelStatus.PENDING_BUY && candle.low <= lvl.price) {
          lvl.status = GridLevelStatus.FILLED
          val fee = lvl.price * lvl.quantity * 0.001
          currentBalance -= fee // учет комиссии

          gridFillMarkers.add(
            BacktestGridFillMarker(
              time = candle.openTime,
              price = lvl.price,
              levelIndex = lvl.index,
              side = "BUY",
              status = GridLevelStatus.FILLED,
              profitUsdt = -fee
            )
          )

          // Выставляем встречный SELL на уровень выше (для LONG)
          val targetIdx = if (gridConfig.direction == GridDirection.LONG) lvlIdx + 1 else lvlIdx - 1
          if (targetIdx in levels.indices) {
            val targetLevel = levels[targetIdx]
            targetLevel.side = "SELL"
            targetLevel.status = GridLevelStatus.PENDING_SELL
            targetLevel.entryPrice = lvl.price
          }
        }

        // Исполнение SELL уровня (High свечи коснулся или пробил уровень вверх)
        else if (lvl.status == GridLevelStatus.PENDING_SELL && candle.high >= lvl.price) {
          lvl.status = GridLevelStatus.FILLED

          val entryP = lvl.entryPrice ?: (lvl.price - (effectiveUpper - effectiveLower) / (n - 1))
          val grossProfit = max(0.0, (lvl.price - entryP) * lvl.quantity)
          val entryFee = entryP * lvl.quantity * 0.001
          val exitFee = lvl.price * lvl.quantity * 0.001
          val netProfit = grossProfit - (entryFee + exitFee)

          currentBalance += netProfit
          tradePnls.add(netProfit)

          gridFillMarkers.add(
            BacktestGridFillMarker(
              time = candle.openTime,
              price = lvl.price,
              levelIndex = lvl.index,
              side = "SELL",
              status = GridLevelStatus.FILLED,
              profitUsdt = netProfit
            )
          )

          // Выставляем обратный BUY на уровень ниже (для LONG)
          val targetIdx = if (gridConfig.direction == GridDirection.LONG) lvlIdx - 1 else lvlIdx + 1
          if (targetIdx in levels.indices) {
            val targetLevel = levels[targetIdx]
            targetLevel.side = "BUY"
            targetLevel.status = GridLevelStatus.PENDING_BUY
          }
        }
      }

      equityCurve.add(EquityPoint(candle.openTime, currentBalance))
    }

    val stats = computeStats(
      initialBalance = config.initialBalanceUsdt,
      finalBalance = currentBalance,
      tradesPnl = tradePnls,
      equityCurve = equityCurve,
      firstCandleClose = candles.first().close,
      lastCandleClose = candles.last().close
    )

    BacktestResult(
      config = config,
      candles = candles,
      tradeMarkers = emptyList(),
      gridFillMarkers = gridFillMarkers,
      equityCurve = equityCurve,
      stats = stats
    )
  }

  private fun computeStats(
    initialBalance: Double,
    finalBalance: Double,
    tradesPnl: List<Double>,
    equityCurve: List<EquityPoint>,
    firstCandleClose: Double,
    lastCandleClose: Double
  ): BacktestStats {
    val totalTrades = tradesPnl.size
    val winTrades = tradesPnl.count { it > 0.0 }
    val lossTrades = tradesPnl.count { it <= 0.0 }
    val winRate = if (totalTrades > 0) (winTrades.toDouble() / totalTrades) * 100.0 else 0.0

    val grossWins = tradesPnl.filter { it > 0.0 }.sum()
    val grossLosses = abs(tradesPnl.filter { it < 0.0 }.sum())
    val profitFactor = when {
      grossLosses > 0.0 -> grossWins / grossLosses
      grossWins > 0.0 -> 99.9
      else -> 1.0
    }

    // Расчет максимальной просадки (Max Drawdown)
    var peakEquity = initialBalance
    var maxDdPercent = 0.0
    for (pt in equityCurve) {
      if (pt.balanceUsdt > peakEquity) {
        peakEquity = pt.balanceUsdt
      }
      val dd = if (peakEquity > 0.0) ((peakEquity - pt.balanceUsdt) / peakEquity) * 100.0 else 0.0
      if (dd > maxDdPercent) {
        maxDdPercent = dd
      }
    }

    val totalPnlUsdt = finalBalance - initialBalance
    val totalPnlPercent = if (initialBalance > 0.0) (totalPnlUsdt / initialBalance) * 100.0 else 0.0

    val buyAndHoldPnlPercent = if (firstCandleClose > 0.0) {
      ((lastCandleClose - firstCandleClose) / firstCandleClose) * 100.0
    } else {
      0.0
    }

    return BacktestStats(
      totalTrades = totalTrades,
      winTrades = winTrades,
      lossTrades = lossTrades,
      winRatePercent = winRate,
      profitFactor = profitFactor,
      maxDrawdownPercent = maxDdPercent,
      totalPnlUsdt = totalPnlUsdt,
      totalPnlPercent = totalPnlPercent,
      buyAndHoldPnlPercent = buyAndHoldPnlPercent
    )
  }

  private fun createEmptyStats() = BacktestStats(
    totalTrades = 0,
    winTrades = 0,
    lossTrades = 0,
    winRatePercent = 0.0,
    profitFactor = 0.0,
    maxDrawdownPercent = 0.0,
    totalPnlUsdt = 0.0,
    totalPnlPercent = 0.0,
    buyAndHoldPnlPercent = 0.0
  )
}
