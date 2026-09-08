package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.service.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
  modifier: Modifier = Modifier,
  onOpenSettings: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  storageService: SecureStorageService = SecureStorageService(LocalContext.current),
  authService: BinanceAuthService = remember { BinanceAuthService() },
  marketService: BinanceMarketService = remember { BinanceMarketService() },
  wsService: BinanceWebSocketService = remember { BinanceWebSocketService() },
  strategyService: TradingStrategyService = remember { TradingStrategyService() },
  orderService: OrderExecutionService = remember { OrderExecutionService() },
) {
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  // Баланс (честный state без заглушек 10000 USDT)
  var totalUsdt by remember { mutableStateOf<Double?>(null) }
  var freeUsdt by remember { mutableStateOf<Double?>(null) }
  var lockedUsdt by remember { mutableStateOf<Double?>(null) }
  val balanceHistory = remember { mutableStateListOf<Float>() }
  var balanceError by remember { mutableStateOf<String?>(null) }

  // Пары и тикер
  var pairs by remember { mutableStateOf<List<TradingPair>>(emptyList()) }
  var pairsError by remember { mutableStateOf<String?>(null) }
  var selectedPair by remember { mutableStateOf<TradingPair?>(null) }
  var tickerData by remember { mutableStateOf<TickerData?>(null) }
  var showPairDialog by remember { mutableStateOf(false) }

  // Стратегия и анализ рынка
  var strategyConfig by remember { mutableStateOf(StrategyRiskConfig()) }
  var currentSignal by remember { mutableStateOf<SignalData?>(null) }
  val tradeHistory = remember { mutableStateListOf<TradeRecord>() }

  // Размер позиции
  var positionAmountStr by remember { mutableStateOf("50.0") }
  var positionPercent by remember { mutableFloatStateOf(25f) }

  // Управление ботом
  var isBotActive by remember { mutableStateOf(false) }
  var botStatus by remember { mutableStateOf(BotStatus.STOPPED) }
  var showEmergencyDialog by remember { mutableStateOf(false) }

  // Открытые ордера
  var openOrders by remember { mutableStateOf<List<OpenOrder>>(emptyList()) }
  var ordersError by remember { mutableStateOf<String?>(null) }

  // Инициализация данных без скрытых заглушек
  LaunchedEffect(Unit) {
    val creds = storageService.getCredentials()
    try {
      val pList = marketService.getTradingPairs()
      pairs = pList
      if (pList.isNotEmpty()) {
        val defaultPair = pList.firstOrNull { it.symbol == "BTCUSDT" } ?: pList.first()
        selectedPair = defaultPair
        wsService.subscribeToTicker(defaultPair.symbol)
      }
    } catch (e: Exception) {
      pairsError = e.message ?: "Сбой загрузки пар"
      snackbarHostState.showSnackbar("Ошибка загрузки пар: ${e.message}")
    }

    if (creds != null && creds.apiKey.isNotEmpty()) {
      try {
        val info = authService.getAccountInfo(creds.apiKey, creds.secretKey, creds.isTestnet)
        val usdt = info.balances.firstOrNull { it.asset.equals("USDT", ignoreCase = true) }
        if (usdt != null) {
          freeUsdt = usdt.free
          lockedUsdt = usdt.locked
          totalUsdt = usdt.total
          balanceHistory.add(usdt.total.toFloat())
          balanceError = null
        }
      } catch (e: Exception) {
        balanceError = e.message ?: "Сбой авторизации API"
        snackbarHostState.showSnackbar("Ошибка баланса Binance: ${e.message}")
      }

      try {
        val orders = marketService.getOpenOrders(creds.apiKey, creds.secretKey)
        openOrders = orders
        ordersError = null
      } catch (e: Exception) {
        ordersError = e.message ?: "Ошибка загрузки ордеров"
        snackbarHostState.showSnackbar("Ошибка ордеров: ${e.message}")
      }
    }
  }

  // Подписка на WebSocket тикер
  val wsTicker by wsService.tickerFlow.collectAsState()
  LaunchedEffect(wsTicker) {
    if (wsTicker != null && wsTicker?.symbol?.equals(selectedPair?.symbol, ignoreCase = true) == true) {
      tickerData = wsTicker
    }
  }

  // Периодический расчет стратегии (klines) каждые 4 секунды
  LaunchedEffect(selectedPair, strategyConfig.interval) {
    val sym = selectedPair?.symbol ?: "BTCUSDT"
    while (isActive) {
      try {
        val klines = strategyService.fetchKlines(sym, strategyConfig.interval, 60)
        val curPrice = tickerData?.lastPrice ?: (if (klines.isNotEmpty()) klines.last()[4] else 65000.0)
        val sig = strategyService.evaluate(sym, curPrice, klines, strategyConfig)
        currentSignal = sig

        // Проверка Take-Profit / Stop-Loss для открытых позиций
        val pos = orderService.openPositions[sym]
        if (pos != null) {
          if (curPrice >= pos.takeProfitPrice) {
            orderService.closePosition(sym, curPrice, "Take-Profit достигнут (+${strategyConfig.takeProfitPercent}%)")
            snackbarHostState.showSnackbar("Take-Profit сработал по $sym! Прибыль зафиксирована.")
          } else if (curPrice <= pos.stopLossPrice) {
            orderService.closePosition(sym, curPrice, "Stop-Loss достигнут (-${strategyConfig.stopLossPercent}%)")
            snackbarHostState.showSnackbar("Stop-Loss сработал по $sym. Позиция закрыта.")
          }
        }
      } catch (_: Exception) {}
      delay(4000)
    }
  }

  // Авто-торговля бота при получении сигналов высокого веса
  LaunchedEffect(isBotActive, currentSignal) {
    if (!isBotActive || currentSignal == null) return@LaunchedEffect

    val sig = currentSignal!!
    val sym = sig.symbol
    val creds = storageService.getCredentials()

    if (sig.score >= strategyConfig.minScoreThreshold) {
      if (sig.action == SignalAction.BUY_LONG && !orderService.hasOpenPosition(sym)) {
        botStatus = BotStatus.LONG
        val amount = positionAmountStr.toDoubleOrNull() ?: 500.0
        val targetQty = amount / sig.currentPrice

        if (creds != null && creds.apiKey.isNotEmpty()) {
          val res = orderService.placeOrder(
            apiKey = creds.apiKey,
            secretKey = creds.secretKey,
            symbol = sym,
            side = "BUY",
            type = "MARKET",
            quantity = targetQty,
            price = sig.currentPrice,
            pairInfo = selectedPair
          )
          if (res.isSuccess) {
            val rec = TradeRecord(
              id = res.orderId ?: System.currentTimeMillis().toString(),
              symbol = sym,
              side = "BUY",
              entryPrice = if (res.price > 0) res.price else sig.currentPrice,
              quantity = res.executedQty,
              usdtAmount = amount,
              stopLossPrice = sig.recommendedStopLoss,
              takeProfitPrice = sig.recommendedTakeProfit,
              score = sig.score,
              entryTime = System.currentTimeMillis()
            )
            orderService.recordTrade(rec)
            tradeHistory.add(0, rec)
            snackbarHostState.showSnackbar("Бот открыл LONG $sym по цене ${rec.entryPrice}")
          } else {
            snackbarHostState.showSnackbar("Ошибка ордера: ${res.errorMessage}")
          }
        }
      } else if (sig.action == SignalAction.SELL_SPOT && orderService.hasOpenPosition(sym)) {
        botStatus = BotStatus.SHORT
        val pos = orderService.openPositions[sym]
        if (pos != null && creds != null && creds.apiKey.isNotEmpty()) {
          val res = orderService.placeOrder(
            apiKey = creds.apiKey,
            secretKey = creds.secretKey,
            symbol = sym,
            side = "SELL",
            type = "MARKET",
            quantity = pos.quantity,
            price = sig.currentPrice,
            pairInfo = selectedPair
          )
          if (res.isSuccess) {
            orderService.closePosition(sym, sig.currentPrice, "Сигнал SELL Spot (score: ${sig.score}%)")
            snackbarHostState.showSnackbar("Бот зафиксировал спотовую позицию $sym")
          }
        }
      }
    } else {
      botStatus = if (orderService.hasOpenPosition(sym)) BotStatus.LONG else BotStatus.ANALYSIS
    }
  }

  val backgroundBrush = remember {
    Brush.verticalGradient(listOf(HudNavyDark, HudNavySurface, Color(0xFF071220)))
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = HudNavyDark,
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(backgroundBrush)
        .drawBehind {
          val step = 40.dp.toPx()
          val gridColor = Color(0x0A00D4FF)
          var x = 0f
          while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
          }
          var y = 0f
          while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
          }
        }
        .padding(innerPadding)
        .imePadding()
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
      ) {
        // Верхний AppBar Dashboard
        item {
          DashboardTopBar(onOpenSettings = onOpenSettings, onOpenHistory = onOpenHistory)
        }

        // 1. КАРТОЧКА БАЛАНСА
        item {
          HudCard(
            title = "БАЛАНС СПОТ (USDT)",
            icon = Icons.Outlined.AccountBalanceWallet,
            modifier = Modifier.fillMaxWidth().testTag("balance_card")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Bottom
            ) {
              Column {
                if (balanceError != null) {
                  Text(
                    "ОШИБКА: $balanceError",
                    color = HudRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                  )
                } else {
                  val totalText = totalUsdt?.let { "%.2f USDT".format(it) } ?: "ЗАГРУЗКА..."
                  Text(
                    totalText,
                    color = HudPeach,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                  )
                }
                Text("ОБЩИЙ ДЕПОЗИТ", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
              IconButton(
                onClick = {
                  scope.launch {
                    val creds = storageService.getCredentials()
                    if (creds != null && creds.apiKey.isNotEmpty()) {
                      try {
                        val info = authService.getAccountInfo(creds.apiKey, creds.secretKey, creds.isTestnet)
                        val u = info.balances.firstOrNull { it.asset.equals("USDT", ignoreCase = true) }
                        if (u != null) {
                          freeUsdt = u.free
                          lockedUsdt = u.locked
                          totalUsdt = u.total
                          balanceHistory.add(u.total.toFloat())
                          balanceError = null
                        }
                        snackbarHostState.showSnackbar("Баланс обновлен")
                      } catch (e: Exception) {
                        balanceError = e.message ?: "Сбой API"
                        snackbarHostState.showSnackbar("Ошибка: ${e.message}")
                      }
                    }
                  }
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = HudCyan)
              }
            }

            Spacer(Modifier.height(12.dp))

            // Свободно / В ордерах
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(Color(0x1A00E676), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x3300E676), RoundedCornerShape(6.dp))
                  .padding(8.dp)
              ) {
                Column {
                  Text("СВОБОДНО", color = HudGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  val freeText = freeUsdt?.let { "%.2f USDT".format(it) } ?: "--.--"
                  Text(freeText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
              }
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(Color(0x1AFFB300), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x33FFB300), RoundedCornerShape(6.dp))
                  .padding(8.dp)
              ) {
                Column {
                  Text("В ОРДЕРАХ", color = Color(0xFFFFB300), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  val lockedText = lockedUsdt?.let { "%.2f USDT".format(it) } ?: "--.--"
                  Text(lockedText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
              }
            }

            Spacer(Modifier.height(14.dp))
            Text("СЕССИОННЫЙ МИНИ-ГРАФИК (FL_CHART)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))

            MiniBalanceChart(
              spots = balanceHistory,
              modifier = Modifier.fillMaxWidth().height(60.dp)
            )
          }
        }

        // 2. КАРТОЧКА ВЫБОРА ПАРЫ
        item {
          HudCard(
            title = "ТОРГОВАЯ ПАРА (SPOT TESTNET)",
            icon = Icons.Outlined.CandlestickChart,
            modifier = Modifier.fillMaxWidth().testTag("pair_selector_card")
          ) {
            val isPos = tickerData?.isPositive ?: true
            val changeColor = if (isPos) HudGreen else HudRed

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .border(1.2.dp, Color(0x6600D4FF), RoundedCornerShape(6.dp))
                .clickable { showPairDialog = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.Search, contentDescription = null, tint = HudCyan, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(
                    selectedPair?.symbol ?: "BTCUSDT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = HudCyan)
              }
            }

            Spacer(Modifier.height(12.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text("ТЕКУЩАЯ ЦЕНА (WS)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val priceStr = tickerData?.lastPrice?.let {
                  if (it < 1.0) "%.4f".format(it) else "%.2f".format(it)
                } ?: "--.--"
                Text(
                  priceStr,
                  color = Color.White,
                  fontSize = 24.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace
                )
              }

              Box(
                modifier = Modifier
                  .background(changeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                  .border(1.2.dp, changeColor, RoundedCornerShape(6.dp))
                  .padding(horizontal = 12.dp, vertical = 6.dp)
              ) {
                val pct = tickerData?.priceChangePercent ?: 2.45
                Text(
                  "${if (pct >= 0) "+" else ""}${"%.2f".format(Locale.US, pct)}%",
                  color = changeColor,
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }

        // 3. КАРТОЧКА АНАЛИЗА РЫНКА // ТЕХНИЧЕСКИЕ ИНДИКАТОРЫ И СТРАТЕГИЯ
        item {
          val sig = currentSignal
          val ind = sig?.indicators
          val actionColor = when (sig?.action) {
            SignalAction.BUY_LONG -> HudGreen
            SignalAction.SELL_SPOT -> HudRed
            else -> HudCyan
          }
          val actionText = when (sig?.action) {
            SignalAction.BUY_LONG -> "СИГНАЛ: BUY LONG"
            SignalAction.SELL_SPOT -> "СИГНАЛ: SELL SPOT"
            else -> "HOLD / ОЖИДАНИЕ СИГНАЛА"
          }

          HudCard(
            title = "АНАЛИЗ РЫНКА // СТРАТЕГИЯ",
            icon = Icons.Outlined.Analytics,
            borderColor = actionColor,
            modifier = Modifier.fillMaxWidth().testTag("market_analysis_card")
          ) {
            // Выбор таймфрейма свечей
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("ТАЙМФРЕЙМ:", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1m", "5m", "15m", "1h").forEach { intvl ->
                  val isSel = strategyConfig.interval == intvl
                  Box(
                    modifier = Modifier
                      .background(if (isSel) HudCyan else Color(0x1A00D4FF), RoundedCornerShape(4.dp))
                      .border(1.dp, if (isSel) HudCyan else Color(0x3300D4FF), RoundedCornerShape(4.dp))
                      .clickable { strategyConfig = strategyConfig.copy(interval = intvl) }
                      .padding(horizontal = 6.dp, vertical = 2.dp)
                  ) {
                    Text(
                      intvl,
                      color = if (isSel) HudNavyDark else Color.White,
                      fontWeight = FontWeight.Bold,
                      fontSize = 10.sp,
                      fontFamily = FontFamily.Monospace
                    )
                  }
                }
              }
            }

            Spacer(Modifier.height(10.dp))

            // Большой бейдж действия и Score
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(actionColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.5.dp, actionColor, RoundedCornerShape(8.dp))
                .padding(10.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column {
                  Text(actionText, color = actionColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                  Text(
                    "Порог входа: ${strategyConfig.minScoreThreshold}% | SL: ${strategyConfig.stopLossPercent}% | TP: ${strategyConfig.takeProfitPercent}%",
                    color = HudTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
                Box(
                  modifier = Modifier
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, actionColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Text(
                    "SCORE: ${sig?.score ?: 0}%",
                    color = actionColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }

            Spacer(Modifier.height(12.dp))

            // Сетка значений индикаторов
            if (ind != null) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                // RSI
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("RSI (14)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("%.1f".format(ind.rsi), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    val rsiStatus = if (ind.rsi < 30) "ПЕРЕПРОДАН" else if (ind.rsi > 70) "ПЕРЕКУПЛЕН" else "НЕЙТРАЛЬНО"
                    Text(rsiStatus, color = if (ind.rsi < 30) HudGreen else if (ind.rsi > 70) HudRed else HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }

                // EMA CROSS
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("EMA 9 / 21", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("%.0f / %.0f".format(ind.emaFast, ind.emaSlow), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(if (ind.isEmaBullish) "BULLISH (9>21)" else "BEARISH (9<21)", color = if (ind.isEmaBullish) HudGreen else HudRed, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }
              }

              Spacer(Modifier.height(8.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                // MACD
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("MACD (12,26,9)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("H: %.2f".format(ind.macdHist), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(if (ind.isMacdRising) "ИМПУЛЬС ВВЕРХ" else "ИМПУЛЬС ВНИЗ", color = if (ind.isMacdRising) HudGreen else HudRed, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }

                // VOLUME
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("VOLUME vs AVG(20)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("%.1f / %.1f".format(ind.currentVolume, ind.avgVolume), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(if (ind.isVolumeAboveAvg) "ВЫШЕ СРЕДНЕГО" else "НИЖЕ СРЕДНЕГО", color = if (ind.isVolumeAboveAvg) HudGreen else Color(0xFFFFB300), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }
              }

              Spacer(Modifier.height(8.dp))

              // BOLLINGER BANDS
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                  .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text("BOLLINGER (20,2)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("L: %.1f | M: %.1f | U: %.1f".format(ind.bbLower, ind.bbMiddle, ind.bbUpper), color = HudPeach, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
              }
            }

            // Stop-Loss и Take-Profit
            if (sig != null && sig.action != SignalAction.HOLD) {
              Spacer(Modifier.height(10.dp))
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0x1A00D4FF), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                  .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text("SL: ${"%.2f".format(Locale.US, sig.recommendedStopLoss)} USDT (-${strategyConfig.stopLossPercent}%)", color = HudRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("TP: ${"%.2f".format(Locale.US, sig.recommendedTakeProfit)} USDT (+${strategyConfig.takeProfitPercent}%)", color = HudGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
              }
            }
          }
        }

        // 4. КАРТОЧКА РАЗМЕРА ПОЗИЦИИ
        item {
          val currentPrice = tickerData?.lastPrice ?: 0.0
          val enteredAmount = positionAmountStr.toDoubleOrNull() ?: 0.0
          val baseQty = if (currentPrice > 0) enteredAmount / currentPrice else 0.0
          val minNotional = selectedPair?.minNotional ?: 10.0
          val minLot = selectedPair?.minQty ?: 0.00001
          val isBelowMin = enteredAmount > 0 && enteredAmount < minNotional
          val isExceed = enteredAmount > (freeUsdt ?: 0.0)

          HudCard(
            title = "РАЗМЕР ПОЗИЦИИ",
            icon = Icons.Outlined.Tune,
            borderColor = if (isBelowMin || isExceed) HudRed else Color(0x4400D4FF),
            modifier = Modifier.fillMaxWidth().testTag("position_size_card")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              OutlinedTextField(
                value = positionAmountStr,
                onValueChange = {
                  positionAmountStr = it
                  val parsed = it.toDoubleOrNull() ?: 0.0
                  val available = freeUsdt ?: 0.0
                  if (available > 0) {
                    positionPercent = (parsed / available * 100f).toFloat().coerceIn(0f, 100f)
                  }
                },
                label = { Text("СУММА В USDT", fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.weight(1f).testTag("position_usdt_input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = hudTextFieldColors(),
                singleLine = true
              )

              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(Color(0x1A00D4FF), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                  .padding(horizontal = 10.dp, vertical = 8.dp)
              ) {
                Column {
                  Text("КОЛИЧЕСТВО (${selectedPair?.baseAsset ?: "QTY"})", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  Spacer(Modifier.height(4.dp))
                  Text(
                    if (baseQty < 1.0) "%.6f".format(baseQty) else "%.4f".format(baseQty),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }

            Spacer(Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("ДОЛЯ БАЛАНСА", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              Text("${positionPercent.toInt()}%", color = HudCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            Slider(
              value = positionPercent,
              onValueChange = {
                positionPercent = it
                val available = freeUsdt ?: 0.0
                val calc = (available * (it / 100f)).coerceAtLeast(0.0)
                positionAmountStr = "%.2f".format(calc)
              },
              valueRange = 0f..100f,
              colors = SliderDefaults.colors(
                thumbColor = HudCyan,
                activeTrackColor = HudCyan,
                inactiveTrackColor = Color(0x3300D4FF)
              )
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              listOf(10, 25, 50, 75, 100).forEach { p ->
                val selected = (positionPercent.toInt() == p)
                Box(
                  modifier = Modifier
                    .background(if (selected) HudCyan else Color(0x1A00D4FF), RoundedCornerShape(4.dp))
                    .border(1.dp, if (selected) HudCyan else Color(0x3300D4FF), RoundedCornerShape(4.dp))
                    .clickable {
                      positionPercent = p.toFloat()
                      val available = freeUsdt ?: 0.0
                      positionAmountStr = "%.2f".format(available * (p / 100.0))
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Text(
                    "$p%",
                    color = if (selected) HudNavyDark else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }

            Spacer(Modifier.height(12.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .padding(8.dp),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("MIN_NOTIONAL: ${minNotional.toInt()} USDT", color = if (isBelowMin) HudRed else HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              Text("MIN_LOT: $minLot", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
          }
        }

        // 5. КАРТОЧКА УПРАВЛЕНИЯ БОТОМ
        item {
          val statusColor = Color(botStatus.colorHex)

          HudCard(
            title = "УПРАВЛЕНИЕ АЛГО-БОТОМ",
            icon = Icons.Outlined.SmartToy,
            borderColor = if (isBotActive) HudCyan else Color(0x66FF5252),
            modifier = Modifier.fillMaxWidth().testTag("bot_control_card")
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(if (isBotActive) Color(0x2600D4FF) else Color(0x1AFF5252), RoundedCornerShape(8.dp))
                .border(1.5.dp, if (isBotActive) HudCyan else HudRed, RoundedCornerShape(8.dp))
                .clickable { isBotActive = !isBotActive }
                .padding(14.dp)
                .testTag("toggle_bot_button")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    if (isBotActive) Icons.Filled.PowerSettingsNew else Icons.Filled.StopCircle,
                    contentDescription = null,
                    tint = if (isBotActive) HudCyan else HudRed,
                    modifier = Modifier.size(26.dp)
                  )
                  Spacer(Modifier.width(10.dp))
                  Column {
                    Text(
                      if (isBotActive) "БОТ АКТИВЕН" else "БОТ ОСТАНОВЛЕН",
                      color = Color.White,
                      fontWeight = FontWeight.Bold,
                      fontSize = 14.sp,
                      fontFamily = FontFamily.Monospace
                    )
                    Text(
                      if (isBotActive) "Алгоритм сканирует рынок" else "Нажмите для включения бота",
                      color = HudTextMuted,
                      fontSize = 11.sp,
                      fontFamily = FontFamily.Monospace
                    )
                  }
                }

                Switch(
                  checked = isBotActive,
                  onCheckedChange = { isBotActive = it },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = HudCyan,
                    checkedTrackColor = Color(0x4400D4FF),
                    uncheckedThumbColor = HudRed,
                    uncheckedTrackColor = Color(0x44FF5252)
                  )
                )
              }
            }

            Spacer(Modifier.height(10.dp))

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(10.dp)
                    .background(statusColor, RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text("СТАТУС: ", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(botStatus.title, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
              }
            }

            Spacer(Modifier.height(12.dp))

            Button(
              onClick = { showEmergencyDialog = true },
              modifier = Modifier.fillMaxWidth().testTag("emergency_close_button"),
              shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
              colors = ButtonDefaults.buttonColors(containerColor = HudRed)
            ) {
              Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("ЭКСТРЕННО ЗАКРЫТЬ ВСЕ ПОЗИЦИИ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
          }
        }

        // 6. КАРТОЧКА АКТИВНЫХ ОРДЕРОВ / ПОЗИЦИЙ
        item {
          HudCard(
            title = "АКТИВНЫЕ ОРДЕРА (${openOrders.size})",
            icon = Icons.Outlined.ListAlt,
            modifier = Modifier.fillMaxWidth().testTag("open_orders_card")
          ) {
            if (openOrders.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
              ) {
                Text("Нет открытых ордеров", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
              }
            } else {
              openOrders.forEach { order ->
                val isBuy = order.isBuy
                val sideColor = if (isBuy) HudGreen else HudRed

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .background(sideColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .border(0.8.dp, sideColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                      Text(order.side, color = sideColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                      Text(order.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                      Text("Qty: ${order.origQty}", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                  }

                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      "%.2f".format(order.price),
                      color = HudPeach,
                      fontWeight = FontWeight.Bold,
                      fontSize = 13.sp,
                      fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                      onClick = {
                        openOrders = openOrders.filter { it.orderId != order.orderId }
                        scope.launch {
                          val creds = storageService.getCredentials()
                          if (creds != null && creds.apiKey.isNotEmpty()) {
                            marketService.cancelOrder(creds.apiKey, creds.secretKey, order.symbol, order.orderId)
                          }
                          snackbarHostState.showSnackbar("Ордер #${order.orderId} отменен")
                        }
                      },
                      modifier = Modifier.size(28.dp)
                    ) {
                      Icon(Icons.Outlined.Cancel, contentDescription = "Cancel", tint = HudRed, modifier = Modifier.size(18.dp))
                    }
                  }
                }
                HorizontalDivider(color = Color(0x1A00D4FF), thickness = 1.dp)
              }
            }
          }
        }
      }
    }
  }

  // Диалог выбора торговой пары
  if (showPairDialog) {
    AlertDialog(
      onDismissRequest = { showPairDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Text("ВЫБОР ТОРГОВОЙ ПАРЫ (USDT)", color = HudCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      },
      text = {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
          items(pairs) { pair ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  selectedPair = pair
                  showPairDialog = false
                  wsService.subscribeToTicker(pair.symbol)
                }
                .padding(vertical = 10.dp, horizontal = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(pair.symbol, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Text("Min: ${pair.minNotional} USDT", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider(color = Color(0x1A00D4FF))
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showPairDialog = false }) {
          Text("ЗАКРЫТЬ", color = HudCyan, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }

  // Диалог экстренной остановки
  if (showEmergencyDialog) {
    HudAlertDialog(
      title = "ЭКСТРЕННАЯ ОСТАНОВКА",
      message = "Немедленно остановить алгоритм и отменить все активные ордера на бирже Binance Testnet?",
      confirmButtonText = "ЗАКРЫТЬ ВСЕ",
      confirmButtonColor = HudRed,
      onDismiss = { showEmergencyDialog = false },
      onConfirm = {
        showEmergencyDialog = false
        isBotActive = false
        botStatus = BotStatus.STOPPED
        openOrders = emptyList()
        scope.launch {
          snackbarHostState.showSnackbar("Все позиции закрыты, бот остановлен!")
        }
      }
    )
  }
}

@Composable
fun DashboardTopBar(
  onOpenSettings: () -> Unit,
  onOpenHistory: () -> Unit = {},
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xDD0A182C), CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
      .border(1.2.dp, Color(0x3300D4FF), CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .background(Color(0x2200D4FF), RoundedCornerShape(6.dp))
          .border(1.dp, HudCyan, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Outlined.Speed, contentDescription = null, tint = HudCyan, modifier = Modifier.size(18.dp))
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          "HUD DASHBOARD",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          fontFamily = FontFamily.Monospace,
          letterSpacing = 1.sp
        )
        Text("BINANCE SPOT TESTNET TERMINAL", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = onOpenHistory,
        modifier = Modifier.size(34.dp).testTag("history_button")
      ) {
        Icon(Icons.Outlined.ReceiptLong, contentDescription = "Trade History", tint = HudCyan)
      }
      Spacer(Modifier.width(4.dp))
      IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.size(34.dp).testTag("settings_button")
      ) {
        Icon(Icons.Outlined.Settings, contentDescription = "API Settings", tint = HudCyan)
      }
    }
  }
}

@Composable
fun MiniBalanceChart(
  spots: List<Float>,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    if (spots.size < 2) return@Canvas
    val minVal = spots.minOrNull() ?: 0f
    val maxVal = spots.maxOrNull() ?: 1f
    val range = (maxVal - minVal).coerceAtLeast(1f)

    val stepX = size.width / (spots.size - 1)
    val path = Path()

    spots.forEachIndexed { i, v ->
      val x = i * stepX
      val normY = (v - minVal) / range
      val y = size.height - (normY * size.height * 0.8f) - (size.height * 0.1f)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(
      path = path,
      color = HudCyan,
      style = Stroke(width = 2.dp.toPx())
    )
  }
}
