package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MyApplication
import com.example.model.*
import com.example.ui.components.BacktestBottomSheet
import com.example.ui.components.BacktestStatsCard
import com.example.ui.components.EquityCurveChart
import com.example.ui.components.GridBotConfigPanel
import com.example.ui.components.GridOverlayRenderer
import com.example.ui.components.HudCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Режим визуализации графика (Свечи / Линия).
 */
enum class ChartVisualType {
  CANDLES,
  LINE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
  modifier: Modifier = Modifier,
  onBackToDashboard: () -> Unit = {}
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val app = context.applicationContext as MyApplication
  val botEngine = app.botEngine
  val wsService = botEngine.wsService
  val marketService = botEngine.marketService

  val intervals = remember { listOf("1m", "5m", "15m", "1h", "4h", "1d") }

  // Начальный выбор пары из активного состояния бота или BTCUSDT
  var selectedSymbol by remember {
    mutableStateOf(botEngine.stateFlow.value.selectedPair?.symbol ?: "BTCUSDT")
  }
  var selectedInterval by remember { mutableStateOf("15m") }

  // Сохраняемый масштаб и скролл графика (rememberSaveable) (Requirement 3)
  var zoomFactor by rememberSaveable { mutableFloatStateOf(1.0f) }
  var scrollOffset by rememberSaveable { mutableFloatStateOf(0f) }
  var chartVisualType by rememberSaveable { mutableStateOf(ChartVisualType.CANDLES) }

  // Сброс масштаба и скролла ТОЛЬКО при смене пары или интервала (не при каждой новой свече!)
  LaunchedEffect(selectedSymbol, selectedInterval) {
    scrollOffset = 0f
    zoomFactor = 1.0f
  }

  // Список доступных торговых пар (переиспользуется из BinanceMarketService)
  var pairs by remember { mutableStateOf<List<TradingPair>>(emptyList()) }
  var showPairDialog by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }

  // Загрузка списка пар при первом входе
  LaunchedEffect(Unit) {
    try {
      pairs = marketService.getTradingPairs()
    } catch (_: Exception) {}
  }

  // Подписка на тикер (текущая цена, 24h change %, 24h High, 24h Low)
  val tickerData by wsService.tickerFlow.collectAsState()

  val gridBotEngine = app.gridBotEngine
  val gridState by gridBotEngine.stateFlow.collectAsState()
  var isGridBotMode by remember { mutableStateOf(false) }

  var showBacktestSheet by remember { mutableStateOf(false) }
  var backtestResult by remember { mutableStateOf<BacktestResult?>(null) }

  // Синхронизация символа с GridBotEngine
  LaunchedEffect(selectedSymbol) {
    gridBotEngine.updateConfig(gridBotEngine.stateFlow.value.config.copy(symbol = selectedSymbol))
  }

  // Если сетка активна, автоматически переключаемся в режим Grid Bot
  LaunchedEffect(gridState.isActive) {
    if (gridState.isActive) {
      isGridBotMode = true
    }
  }

  // При старте экрана или смене пары подписываем тикер
  LaunchedEffect(selectedSymbol) {
    wsService.subscribeToTicker(selectedSymbol)
  }

  // ИЗОЛИРОВАННЫЙ БУФЕР СВЕЧЕЙ (список закрытых свечей + лёгкий объект для текущей формирующейся свечи)
  var closedCandles by remember { mutableStateOf<List<Candle>>(emptyList()) }
  var liveCandle by remember { mutableStateOf<Candle?>(null) }
  var isLoadingCandles by remember { mutableStateOf(true) }
  var chartError by remember { mutableStateOf<String?>(null) }

  // При КАЖДОЙ смене пары ИЛИ таймфрейма:
  LaunchedEffect(selectedSymbol, selectedInterval) {
    closedCandles = emptyList()
    liveCandle = null
    isLoadingCandles = true
    chartError = null

    wsService.unsubscribeKline()

    try {
      val freshList = marketService.fetchCandles(selectedSymbol, selectedInterval, 150)
      if (freshList.isNotEmpty()) {
        val limited = freshList.takeLast(150)
        closedCandles = limited.dropLast(1)
        liveCandle = limited.lastOrNull()
      } else {
        closedCandles = emptyList()
        liveCandle = null
      }
      isLoadingCandles = false
    } catch (e: Exception) {
      chartError = e.message ?: "Сбой загрузки свечей"
      isLoadingCandles = false
    }

    // Подключаем живой поток свечей для текущей активной комбинации
    wsService.subscribeToKline(selectedSymbol, selectedInterval)
  }

  // Обновление буфера свечей из WebSocket без сброса scrollOffset!
  LaunchedEffect(selectedSymbol, selectedInterval) {
    wsService.klineFlow.collect { update ->
      if (update.symbol.equals(selectedSymbol, ignoreCase = true) &&
        update.interval.equals(selectedInterval, ignoreCase = true)
      ) {
        val incoming = update.candle
        val currentLive = liveCandle
        if (currentLive == null) {
          liveCandle = incoming
        } else if (incoming.openTime == currentLive.openTime) {
          // Тот же openTime — просто обновляем отдельный лёгкий объект текущей свечи (без пересоздания списка)
          liveCandle = incoming
        } else if (incoming.openTime > currentLive.openTime) {
          // Свеча закрылась: добавляем предыдущую liveCandle в closedCandles (с лимитом 150)
          val next = closedCandles.toMutableList()
          next.add(currentLive)
          while (next.size >= 150) {
            next.removeAt(0)
          }
          closedCandles = next
          liveCandle = incoming
        }
      }
    }
  }

  // При уходе с экрана отменяем WebSocket kline
  DisposableEffect(Unit) {
    onDispose {
      wsService.unsubscribeKline()
    }
  }

  val backgroundBrush = remember {
    Brush.verticalGradient(
      listOf(
        HudNavyDark,
        Color(0xFF0C0C1E),
        Color(0xFF060610)
      )
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = Color.Transparent
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(backgroundBrush)
        .padding(paddingValues)
    ) {
      // HUD Сетка на фоне
      Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 40.dp.toPx()
        for (x in 0..(size.width / step).toInt()) {
          drawLine(
            color = Color(0x0800D4FF),
            start = Offset(x * step, 0f),
            end = Offset(x * step, size.height),
            strokeWidth = 1f
          )
        }
        for (y in 0..(size.height / step).toInt()) {
          drawLine(
            color = Color(0x0800D4FF),
            start = Offset(0f, y * step),
            end = Offset(size.width, y * step),
            strokeWidth = 1f
          )
        }
      }

      Column(modifier = Modifier.fillMaxSize()) {
        // ВЕРХНИЙ ТУЛБАР: Кнопка Назад + Заголовок + Бейдж Live / Бэктест
        ChartTopBar(
          selectedSymbol = selectedSymbol,
          isBacktest = (backtestResult != null),
          onBack = onBackToDashboard
        )

        val mainScrollState = rememberScrollState()

        Column(
          modifier = Modifier
            .fillMaxSize()
            .then(if (isGridBotMode) Modifier.verticalScroll(mainScrollState) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
          // 1. СЕЛЕКТОР ПАРЫ + ТЕКУЩАЯ ЦЕНА
          val currentPrice = tickerData?.lastPrice ?: 0.0
          val changePct = tickerData?.priceChangePercent ?: 0.0
          val isPositive = changePct >= 0
          val changeColor = if (isPositive) HudGreen else HudRed
          val high24h = tickerData?.highPrice ?: 0.0
          val low24h = tickerData?.lowPrice ?: 0.0

          // Инициализация границ сетки при первом получении цены
          LaunchedEffect(currentPrice) {
            if (!gridState.isActive && currentPrice > 0.0) {
              if (gridState.config.lowerBound == 0.0 || gridState.config.upperBound == 0.0) {
                val bounds = gridBotEngine.calculateBoundsFromPercent(gridState.config.rangePercent, currentPrice)
                gridBotEngine.updateConfig(
                  gridState.config.copy(
                    lowerBound = bounds.first,
                    upperBound = bounds.second,
                    tradingInterval = selectedInterval
                  )
                )
              }
            }
          }

          HudCard(
            title = "ИНСТРУМЕНТ // РЫНОЧНЫЙ ТИКЕР",
            icon = Icons.Outlined.ShowChart,
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Кнопка выбора инструмента (модальное окно поиска)
              Box(
                modifier = Modifier
                  .background(Color(0xFF0C1E36), CutCornerShape(4.dp))
                  .border(1.dp, HudCyan.copy(alpha = 0.5f), CutCornerShape(4.dp))
                  .clickable { showPairDialog = true }
                  .padding(horizontal = 10.dp, vertical = 6.dp)
                  .testTag("symbol_selector_button")
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    selectedSymbol,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                  )
                  Spacer(Modifier.width(4.dp))
                  Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = HudCyan)
                }
              }

              // Отображение живой цены и 24h изменения
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  "$${formatPrice(currentPrice)}",
                  color = HudCyan,
                  fontWeight = FontWeight.Bold,
                  fontSize = 17.sp,
                  fontFamily = FontFamily.Monospace
                )
                Text(
                  "${if (isPositive) "+" else ""}${"%.2f".format(Locale.US, changePct)}%",
                  color = changeColor,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }

            Spacer(Modifier.height(6.dp))

            // Переключатель вкладок: ОБЗОР ГРАФИКА vs GRID BOT СЕТКА
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071220), CutCornerShape(4.dp))
                .border(1.dp, Color(0x3300D4FF), CutCornerShape(4.dp))
                .padding(3.dp),
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              // Вкладка "ОБЗОР ГРАФИКА"
              val isChartActive = !isGridBotMode
              val chartBg = if (isChartActive) Brush.linearGradient(listOf(HudNeonBlue, Color(0xFF00527A))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(chartBg, RoundedCornerShape(4.dp))
                  .clickable { isGridBotMode = false }
                  .padding(vertical = 6.dp)
                  .testTag("tab_chart_view"),
                contentAlignment = Alignment.Center
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    Icons.Outlined.CandlestickChart,
                    contentDescription = null,
                    tint = if (isChartActive) Color.White else HudTextMuted,
                    modifier = Modifier.size(15.dp)
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    "ГРАФИК",
                    color = if (isChartActive) Color.White else HudTextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }

              // Вкладка "GRID BOT"
              val isGridActive = isGridBotMode
              val gridBg = if (isGridActive) Brush.linearGradient(listOf(Color(0xFF5A142A), HudNeonPink)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(gridBg, RoundedCornerShape(4.dp))
                  .clickable { isGridBotMode = true }
                  .padding(vertical = 6.dp)
                  .testTag("tab_grid_bot"),
                contentAlignment = Alignment.Center
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    Icons.Outlined.GridOn,
                    contentDescription = null,
                    tint = if (isGridActive) Color.White else HudTextMuted,
                    modifier = Modifier.size(15.dp)
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    if (gridState.isActive) "GRID BOT [ON]" else "GRID BOT",
                    color = if (isGridActive) Color.White else if (gridState.isActive) HudGreen else HudTextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }
          }

          Spacer(Modifier.height(6.dp))

          // 2. СЕЛЕКТОР ТАЙМФРЕЙМОВ (КНОПКИ 1m, 5m, 15m, 1h, 4h, 1d)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            intervals.forEach { interval ->
              val isSelected = interval == selectedInterval
              val btnBg = if (isSelected) {
                Brush.linearGradient(listOf(HudNeonBlue, Color(0xFF004466)))
              } else {
                Brush.linearGradient(listOf(Color(0xFF0C1E36), Color(0xFF0C1E36)))
              }
              val borderColor = if (isSelected) HudCyan else Color(0x2200D4FF)

              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(btnBg, RoundedCornerShape(4.dp))
                  .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                  .clickable {
                    if (selectedInterval != interval) {
                      selectedInterval = interval
                    }
                  }
                  .padding(vertical = 6.dp)
                  .testTag("timeframe_$interval"),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  interval,
                  color = if (isSelected) Color.White else HudTextMuted,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }

          Spacer(Modifier.height(6.dp))

          // 3. ОБЛАСТЬ ГРАФИКА СВЕЧЕЙ (CANVAS)
          val chartBoxModifier = if (isGridBotMode) {
            Modifier
              .fillMaxWidth()
              .height(310.dp)
              .background(Color(0xFF071220), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
              .border(1.2.dp, HudNeonBlue.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
          } else {
            Modifier
              .fillMaxWidth()
              .weight(1f)
              .defaultMinSize(minHeight = 280.dp)
              .background(Color(0xFF071220), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
              .border(1.2.dp, HudNeonBlue.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
          }

          Box(modifier = chartBoxModifier) {
            when {
              backtestResult != null -> {
                CandlestickChart(
                  candles = backtestResult!!.candles,
                  currentPrice = currentPrice,
                  symbol = selectedSymbol,
                  interval = selectedInterval,
                  chartVisualType = chartVisualType,
                  zoomFactor = zoomFactor,
                  onZoomChange = { zoomFactor = it },
                  scrollOffset = scrollOffset,
                  onScrollOffsetChange = { scrollOffset = it },
                  gridState = if (isGridBotMode) gridState else null,
                  backtestResult = backtestResult,
                  modifier = Modifier.fillMaxSize()
                )
              }

              isLoadingCandles -> {
                Column(
                  modifier = Modifier.fillMaxSize(),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
                ) {
                  CircularProgressIndicator(
                    color = HudNeonPink,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                  )
                  Spacer(Modifier.height(12.dp))
                  Text(
                    "ЗАГРУЗКА 150 СВЕЧЕЙ [$selectedSymbol : $selectedInterval]...",
                    color = HudCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }

              chartError != null -> {
                Column(
                  modifier = Modifier.fillMaxSize().padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
                ) {
                  Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = HudRed, modifier = Modifier.size(32.dp))
                  Spacer(Modifier.height(8.dp))
                  Text(chartError ?: "Ошибка", color = HudRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                  Spacer(Modifier.height(8.dp))
                  Button(
                    onClick = {
                      scope.launch {
                        isLoadingCandles = true
                        chartError = null
                        try {
                          val freshList = marketService.fetchCandles(selectedSymbol, selectedInterval, 150)
                          if (freshList.isNotEmpty()) {
                            val limited = freshList.takeLast(150)
                            closedCandles = limited.dropLast(1)
                            liveCandle = limited.lastOrNull()
                          } else {
                            closedCandles = emptyList()
                            liveCandle = null
                          }
                          isLoadingCandles = false
                        } catch (e: Exception) {
                          chartError = e.message
                          isLoadingCandles = false
                        }
                      }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF132F52))
                  ) {
                    Text("ПОВТОРИТЬ", color = HudCyan, fontFamily = FontFamily.Monospace)
                  }
                }
              }

              closedCandles.isEmpty() && liveCandle == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Text("НЕТ ДАННЫХ ПО СВЕЧАМ", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
              }

              else -> {
                CandlestickChart(
                  closedCandles = closedCandles,
                  liveCandle = liveCandle,
                  currentPrice = currentPrice,
                  symbol = selectedSymbol,
                  interval = selectedInterval,
                  chartVisualType = chartVisualType,
                  zoomFactor = zoomFactor,
                  onZoomChange = { zoomFactor = it },
                  scrollOffset = scrollOffset,
                  onScrollOffsetChange = { scrollOffset = it },
                  gridState = if (isGridBotMode) gridState else null,
                  onDragUpperBound = { newUpper ->
                    val cleanUpper = if (newUpper >= 1.0) Math.round(newUpper * 100.0) / 100.0 else Math.round(newUpper * 10000.0) / 10000.0
                    gridBotEngine.updateConfig(gridState.config.copy(upperBound = cleanUpper))
                  },
                  onDragLowerBound = { newLower ->
                    val cleanLower = if (newLower >= 1.0) Math.round(newLower * 100.0) / 100.0 else Math.round(newLower * 10000.0) / 10000.0
                    gridBotEngine.updateConfig(gridState.config.copy(lowerBound = cleanLower))
                  },
                  modifier = Modifier.fillMaxSize()
                )
              }
            }
          }

          Spacer(Modifier.height(6.dp))

          // ==========================================
          // ТУЛБАР ГРАФИКА (Requirement 6)
          // ==========================================
          ChartToolbar(
            chartVisualType = chartVisualType,
            onChartVisualTypeChange = { chartVisualType = it },
            onZoomIn = { zoomFactor = (zoomFactor * 1.25f).coerceAtMost(3.5f) },
            onZoomOut = { zoomFactor = (zoomFactor / 1.25f).coerceAtLeast(0.25f) },
            onFitToScreen = {
              zoomFactor = 1.0f
              scrollOffset = 0f
            },
            onOpenBacktest = { showBacktestSheet = true },
            isBacktestActive = (backtestResult != null),
            modifier = Modifier.fillMaxWidth()
          )

          // РЕЗУЛЬТАТЫ БЭКТЕСТА (Карточка статистики + График эквити)
          if (backtestResult != null) {
            Spacer(Modifier.height(8.dp))
            BacktestStatsCard(
              result = backtestResult!!,
              onReset = { backtestResult = null }
            )
            Spacer(Modifier.height(8.dp))
            EquityCurveChart(equity = backtestResult!!.equityCurve)
          }

          // 4. ПАНЕЛЬ НАСТРОЙКИ И УПРАВЛЕНИЯ GRID BOT
          if (isGridBotMode) {
            Spacer(Modifier.height(8.dp))
            GridBotConfigPanel(
              gridBotEngine = gridBotEngine,
              currentPrice = currentPrice,
              selectedSymbol = selectedSymbol,
              selectedInterval = selectedInterval
            )
            Spacer(Modifier.height(20.dp))
          } else {
            // Краткая сводка инструмента (24h high/low/volume)
            Spacer(Modifier.height(8.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A182C), CutCornerShape(4.dp))
                .border(1.dp, Color(0x2200D4FF), CutCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text("24h MAX", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("$${formatPrice(high24h)}", color = HudGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
              }
              Column {
                Text("24h MIN", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("$${formatPrice(low24h)}", color = HudRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
              }
              Column(horizontalAlignment = Alignment.End) {
                Text("24h ОБЪЁМ", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("${"%.1f".format(Locale.US, tickerData?.volume ?: 0.0)}", color = HudCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }
  }

  // ДИАЛОГ ВЫБОРА ТОРГОВОЙ ПАРЫ С ЖИВЫМ ПОИСКОМ
  if (showPairDialog) {
    AlertDialog(
      onDismissRequest = {
        showPairDialog = false
        searchQuery = ""
      },
      containerColor = Color(0xFF0A1C30),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Search, contentDescription = null, tint = HudCyan)
          Spacer(Modifier.width(8.dp))
          Text(
            "ВЫБОР ТОРГОВОЙ ПАРЫ",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск (например BTC, ETH, SOL)", color = HudTextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pair_search_input"),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = HudCyan,
              unfocusedBorderColor = Color(0x3300D4FF),
              focusedContainerColor = Color(0xFF06101E),
              unfocusedContainerColor = Color(0xFF06101E)
            )
          )

          Spacer(Modifier.height(10.dp))

          val filtered = remember(pairs, searchQuery) {
            if (searchQuery.isBlank()) pairs
            else pairs.filter { it.symbol.contains(searchQuery.trim(), ignoreCase = true) }
          }

          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .height(280.dp)
          ) {
            if (filtered.isEmpty()) {
              item {
                Box(
                  modifier = Modifier.fillMaxWidth().padding(24.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("Пары не найдены", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
              }
            } else {
              items(filtered) { pair ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      selectedSymbol = pair.symbol
                      showPairDialog = false
                      searchQuery = ""
                    }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    pair.symbol,
                    color = if (pair.symbol == selectedSymbol) HudCyan else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                  )
                  Text(
                    "${pair.baseAsset} / ${pair.quoteAsset}",
                    color = HudTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
                HorizontalDivider(color = Color(0x1100D4FF))
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          showPairDialog = false
          searchQuery = ""
        }) {
          Text("ЗАКРЫТЬ", color = HudCyan, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }

  // БОТТОМ-ШИТ НАСТРОЙКИ И ЗАПУСКА БЭКТЕСТА
  if (showBacktestSheet) {
    BacktestBottomSheet(
      symbol = selectedSymbol,
      interval = selectedInterval,
      botType = if (isGridBotMode) BacktestBotType.GRID_BOT else BacktestBotType.SIGNAL_BOT,
      onDismiss = { showBacktestSheet = false },
      onBacktestFinished = { result ->
        backtestResult = result
        zoomFactor = 1.0f
        scrollOffset = 0f
      }
    )
  }
}

/**
 * Тулбар управления графиком (Requirement 6):
 * - Кнопки зума [+] и [-]
 * - Кнопка "Fit to screen" / "Вписать"
 * - Переключатель вида: Свечи / Линейный график (Candles / Line)
 * - Кнопка "БЭКТЕСТ" для запуска симуляции
 */
@Composable
fun ChartToolbar(
  chartVisualType: ChartVisualType,
  onChartVisualTypeChange: (ChartVisualType) -> Unit,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onFitToScreen: () -> Unit,
  onOpenBacktest: (() -> Unit)? = null,
  isBacktestActive: Boolean = false,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .background(Color(0xFF071220), CutCornerShape(4.dp))
      .border(1.dp, Color(0x2200D4FF), CutCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    // 1. Переключатель вида: СВЕЧИ / ЛИНИЯ
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      val isCandles = chartVisualType == ChartVisualType.CANDLES
      Box(
        modifier = Modifier
          .background(
            if (isCandles) Brush.linearGradient(listOf(HudNeonBlue, Color(0xFF00527A)))
            else Brush.linearGradient(listOf(Color(0xFF0A182C), Color(0xFF0A182C))),
            RoundedCornerShape(4.dp)
          )
          .border(1.dp, if (isCandles) HudCyan else Color(0x2200D4FF), RoundedCornerShape(4.dp))
          .clickable { onChartVisualTypeChange(ChartVisualType.CANDLES) }
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .testTag("chart_type_candles"),
        contentAlignment = Alignment.Center
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.CandlestickChart, contentDescription = null, tint = if (isCandles) Color.White else HudTextMuted, modifier = Modifier.size(13.dp))
          Spacer(Modifier.width(4.dp))
          Text("СВЕЧИ", color = if (isCandles) Color.White else HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      }

      Box(
        modifier = Modifier
          .background(
            if (!isCandles) Brush.linearGradient(listOf(HudNeonBlue, Color(0xFF00527A)))
            else Brush.linearGradient(listOf(Color(0xFF0A182C), Color(0xFF0A182C))),
            RoundedCornerShape(4.dp)
          )
          .border(1.dp, if (!isCandles) HudCyan else Color(0x2200D4FF), RoundedCornerShape(4.dp))
          .clickable { onChartVisualTypeChange(ChartVisualType.LINE) }
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .testTag("chart_type_line"),
        contentAlignment = Alignment.Center
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.ShowChart, contentDescription = null, tint = if (!isCandles) Color.White else HudTextMuted, modifier = Modifier.size(13.dp))
          Spacer(Modifier.width(4.dp))
          Text("ЛИНИЯ", color = if (!isCandles) Color.White else HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      }
    }

    // 2. Кнопка БЭКТЕСТ и Кнопки масштабирования: [-], [+], [ВПИСАТЬ]
    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (onOpenBacktest != null) {
        Box(
          modifier = Modifier
            .height(28.dp)
            .background(
              if (isBacktestActive) Brush.linearGradient(listOf(HudNeonPurple, Color(0xFF5A142A)))
              else Brush.linearGradient(listOf(Color(0xFF0C1E36), Color(0xFF0C1E36))),
              RoundedCornerShape(4.dp)
            )
            .border(1.dp, if (isBacktestActive) HudNeonPurple else Color(0x3300D4FF), RoundedCornerShape(4.dp))
            .clickable(onClick = onOpenBacktest)
            .padding(horizontal = 6.dp)
            .testTag("chart_backtest_button"),
          contentAlignment = Alignment.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Science, contentDescription = "Бэктест", tint = if (isBacktestActive) Color.White else HudCyan, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(
              if (isBacktestActive) "БЭКТЕСТ [ON]" else "БЭКТЕСТ",
              color = if (isBacktestActive) Color.White else HudCyan,
              fontSize = 9.5.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      // Кнопка Zoom Out (-)
      Box(
        modifier = Modifier
          .size(28.dp)
          .background(Color(0xFF0C1E36), RoundedCornerShape(4.dp))
          .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(4.dp))
          .clickable(onClick = onZoomOut)
          .testTag("chart_zoom_out"),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Filled.Remove, contentDescription = "Отдалить", tint = HudCyan, modifier = Modifier.size(16.dp))
      }

      // Кнопка Zoom In (+)
      Box(
        modifier = Modifier
          .size(28.dp)
          .background(Color(0xFF0C1E36), RoundedCornerShape(4.dp))
          .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(4.dp))
          .clickable(onClick = onZoomIn)
          .testTag("chart_zoom_in"),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Приблизить", tint = HudCyan, modifier = Modifier.size(16.dp))
      }

      // Кнопка Fit to Screen
      Box(
        modifier = Modifier
          .height(28.dp)
          .background(Color(0xFF0C1E36), RoundedCornerShape(4.dp))
          .border(1.dp, HudNeonPink.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
          .clickable(onClick = onFitToScreen)
          .padding(horizontal = 7.dp)
          .testTag("chart_fit_screen"),
        contentAlignment = Alignment.Center
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.FitScreen, contentDescription = "Вписать", tint = HudNeonPink, modifier = Modifier.size(13.dp))
          Spacer(Modifier.width(4.dp))
          Text("ВПИСАТЬ", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

/**
 * Поиск ближайшей свечи по времени открытия (бинарный поиск)
 */
private fun findCandleIndexByTime(candleList: List<Candle>, time: Long): Int {
  if (candleList.isEmpty()) return 0
  var low = 0
  var high = candleList.lastIndex
  var bestIdx = 0
  var minDiff = Long.MAX_VALUE
  while (low <= high) {
    val mid = (low + high) ushr 1
    val t = candleList[mid].openTime
    val diff = abs(t - time)
    if (diff < minDiff) {
      minDiff = diff
      bestIdx = mid
    }
    if (t < time) low = mid + 1
    else if (t > time) high = mid - 1
    else return mid
  }
  return bestIdx
}

@Composable
fun CandlestickChart(
  candles: List<Candle>,
  currentPrice: Double,
  symbol: String,
  interval: String,
  chartVisualType: ChartVisualType = ChartVisualType.CANDLES,
  zoomFactor: Float = 1.0f,
  onZoomChange: (Float) -> Unit = {},
  scrollOffset: Float = 0f,
  onScrollOffsetChange: (Float) -> Unit = {},
  gridState: GridBotState? = null,
  backtestResult: BacktestResult? = null,
  onDragUpperBound: ((Double) -> Unit)? = null,
  onDragLowerBound: ((Double) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  CandlestickChart(
    closedCandles = candles,
    liveCandle = null,
    currentPrice = currentPrice,
    symbol = symbol,
    interval = interval,
    chartVisualType = chartVisualType,
    zoomFactor = zoomFactor,
    onZoomChange = onZoomChange,
    scrollOffset = scrollOffset,
    onScrollOffsetChange = onScrollOffsetChange,
    gridState = gridState,
    backtestResult = backtestResult,
    onDragUpperBound = onDragUpperBound,
    onDragLowerBound = onDragLowerBound,
    modifier = modifier
  )
}

/**
 * Отрисовка свечей или линии на Canvas:
 * - Поддержка pinch-to-zoom (двумя пальцами)
 * - Сохраняемый скролл и зум
 * - Режим Свечи (Candles) / Линия (Line)
 * - Автомасштабирование по видимым свечам
 * - Сетка уровней Grid Bot с перетаскиваемыми маркерами
 * - Визуальные маркеры бэктеста (сделки сигнального бота, исполненные уровни сетки, эквити)
 */
@Composable
fun CandlestickChart(
  closedCandles: List<Candle>,
  liveCandle: Candle? = null,
  currentPrice: Double,
  symbol: String,
  interval: String,
  chartVisualType: ChartVisualType = ChartVisualType.CANDLES,
  zoomFactor: Float = 1.0f,
  onZoomChange: (Float) -> Unit = {},
  scrollOffset: Float = 0f,
  onScrollOffsetChange: (Float) -> Unit = {},
  gridState: GridBotState? = null,
  backtestResult: BacktestResult? = null,
  onDragUpperBound: ((Double) -> Unit)? = null,
  onDragLowerBound: ((Double) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val density = LocalDensity.current

  val flashAlpha by animateFloatAsState(
    targetValue = if (System.currentTimeMillis() - (gridState?.lastFlashTrigger ?: 0L) < 1500L) 1f else 0f,
    animationSpec = tween(1000),
    label = "grid_flash_alpha"
  )

  var currentDisplayMin by remember { mutableDoubleStateOf(0.0) }
  var currentDisplayRange by remember { mutableDoubleStateOf(1.0) }
  var currentChartHeight by remember { mutableFloatStateOf(1f) }

  // Расчет динамической ширины свечей на основе зума
  val candleWidthPx = with(density) { (8.dp * zoomFactor).toPx().coerceIn(2f, 60f) }
  val candleGapPx = with(density) { (3.5.dp * zoomFactor).toPx().coerceIn(1f, 25f) }
  val slotWidthPx = candleWidthPx + candleGapPx
  val priceScaleWidthPx = with(density) { 68.dp.toPx() }
  val timeScaleHeightPx = with(density) { 24.dp.toPx() }

  // Состояние касания для перекрестия (Crosshair)
  var touchPoint by remember { mutableStateOf<Offset?>(null) }
  var touchedCandle by remember { mutableStateOf<Candle?>(null) }

  // Paint для нативных текстовых подписей шкалы
  val pricePaint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.argb(220, 126, 155, 184)
      textSize = with(density) { 9.sp.toPx() }
      typeface = android.graphics.Typeface.MONOSPACE
      isAntiAlias = true
    }
  }

  val curPriceBadgePaint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.WHITE
      textSize = with(density) { 9.sp.toPx() }
      typeface = android.graphics.Typeface.MONOSPACE
      isFakeBoldText = true
      isAntiAlias = true
    }
  }

  val timePaint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.argb(200, 126, 155, 184)
      textSize = with(density) { 8.5.sp.toPx() }
      typeface = android.graphics.Typeface.MONOSPACE
      textAlign = android.graphics.Paint.Align.CENTER
      isAntiAlias = true
    }
  }

  val tradeTagPaint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.WHITE
      textSize = with(density) { 8.5.sp.toPx() }
      typeface = android.graphics.Typeface.MONOSPACE
      isFakeBoldText = true
      isAntiAlias = true
    }
  }

  val timeFormat = remember(interval) {
    if (interval == "1d") SimpleDateFormat("dd.MM", Locale.US)
    else SimpleDateFormat("HH:mm", Locale.US)
  }

  Column(modifier = modifier.fillMaxSize()) {
    // Верхняя строка O/H/L/C для выбранной свечи или последней
    val displayCandle = touchedCandle ?: liveCandle ?: closedCandles.lastOrNull()
    if (displayCandle != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF0A182C))
          .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        val dateStr = timeFormat.format(Date(displayCandle.openTime))
        val candleColor = if (displayCandle.isBullish) HudGreen else HudRed
        Text("T: $dateStr", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("O: ${formatPrice(displayCandle.open)}", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("H: ${formatPrice(displayCandle.high)}", color = HudGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("L: ${formatPrice(displayCandle.low)}", color = HudRed, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("C: ${formatPrice(displayCandle.close)}", color = candleColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        // Обработка жестов: pinch-to-zoom (2 пальца) + перетаскивание границ + скролл (1 палец) (Requirement 3)
        .pointerInput(gridState?.config, zoomFactor, scrollOffset, backtestResult) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var boundDragging: String? = null
            touchPoint = down.position

            if (backtestResult == null && gridState != null && currentDisplayRange > 0.0) {
              val cfg = gridState.config
              if (cfg.upperBound > 0.0 && cfg.lowerBound > 0.0) {
                val upNorm = (cfg.upperBound - currentDisplayMin) / currentDisplayRange
                val upY = (currentChartHeight - upNorm * currentChartHeight).toFloat()

                val lowNorm = (cfg.lowerBound - currentDisplayMin) / currentDisplayRange
                val lowY = (currentChartHeight - lowNorm * currentChartHeight).toFloat()

                val threshold = 36.dp.toPx()
                if (abs(down.position.y - upY) < threshold) {
                  boundDragging = "UPPER"
                } else if (abs(down.position.y - lowY) < threshold) {
                  boundDragging = "LOWER"
                }
              }
            }

            do {
              val event = awaitPointerEvent()
              val pressedPointers = event.changes.filter { it.pressed }

              if (pressedPointers.size >= 2) {
                // ПИНЧ-ТУ-ЗУМ ДВУМЯ ПАЛЬЦАМИ
                boundDragging = null
                val p1 = pressedPointers[0]
                val p2 = pressedPointers[1]
                val curDist = (p1.position - p2.position).getDistance()
                val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
                if (prevDist > 0f) {
                  val scale = curDist / prevDist
                  val newZoom = (zoomFactor * scale).coerceIn(0.25f, 3.5f)
                  onZoomChange(newZoom)
                }
                val panX = ((p1.position.x - p1.previousPosition.x) + (p2.position.x - p2.previousPosition.x)) / 2f
                onScrollOffsetChange(scrollOffset + panX)
                event.changes.forEach { it.consume() }
              } else if (pressedPointers.size == 1) {
                // СКРОЛЛ ИЛИ ПЕРЕТАСКИВАНИЕ ГРАНИЦЫ
                val pointer = pressedPointers[0]
                touchPoint = pointer.position
                val panX = pointer.position.x - pointer.previousPosition.x

                if (boundDragging == "UPPER") {
                  pointer.consume()
                  val norm = (1f - (pointer.position.y / currentChartHeight)).coerceIn(0f, 1f)
                  val newPrice = currentDisplayMin + norm * currentDisplayRange
                  onDragUpperBound?.invoke(newPrice)
                } else if (boundDragging == "LOWER") {
                  pointer.consume()
                  val norm = (1f - (pointer.position.y / currentChartHeight)).coerceIn(0f, 1f)
                  val newPrice = currentDisplayMin + norm * currentDisplayRange
                  onDragLowerBound?.invoke(newPrice)
                } else {
                  if (abs(panX) > 0.5f) {
                    pointer.consume()
                    onScrollOffsetChange(scrollOffset + panX)
                  }
                }
              }
            } while (event.changes.any { it.pressed })

            touchPoint = null
            touchedCandle = null
          }
        }
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val totalWidth = size.width
        val totalHeight = size.height

        val chartWidth = (totalWidth - priceScaleWidthPx).coerceAtLeast(10f)
        val chartHeight = (totalHeight - timeScaleHeightPx).coerceAtLeast(10f)

        val totalCandleCount = closedCandles.size + (if (liveCandle != null) 1 else 0)
        if (totalCandleCount == 0) return@Canvas

        // Ограничение диапазона скролла
        val contentWidth = totalCandleCount * slotWidthPx
        val minScroll = if (contentWidth > chartWidth) -(contentWidth - chartWidth) else 0f
        val maxScroll = 0f
        val clampedScrollOffset = scrollOffset.coerceIn(minScroll, maxScroll)

        // 1. Определение видимых свечей и их экстремумов (АВТОМАСШТАБ)
        val visibleCandles = mutableListOf<Pair<Int, Candle>>()
        for (i in closedCandles.indices) {
          val candleCenterX = chartWidth - (totalCandleCount - 1 - i) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
          if (candleCenterX + slotWidthPx >= 0 && candleCenterX - slotWidthPx <= chartWidth) {
            visibleCandles.add(i to closedCandles[i])
          }
        }

        val liveIdx = totalCandleCount - 1
        val liveCandleCenterX = if (liveCandle != null) {
          chartWidth - (totalCandleCount - 1 - liveIdx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
        } else null
        val isLiveVisible = liveCandle != null && liveCandleCenterX != null &&
          (liveCandleCenterX + slotWidthPx >= 0 && liveCandleCenterX - slotWidthPx <= chartWidth)

        var visibleMin = visibleCandles.minOfOrNull { it.second.low } ?: (liveCandle?.low ?: (closedCandles.minOfOrNull { it.low } ?: 0.0))
        var visibleMax = visibleCandles.maxOfOrNull { it.second.high } ?: (liveCandle?.high ?: (closedCandles.maxOfOrNull { it.high } ?: 1.0))
        if (isLiveVisible && liveCandle != null) {
          visibleMin = min(visibleMin, liveCandle.low)
          visibleMax = max(visibleMax, liveCandle.high)
        }

        val rawRange = visibleMax - visibleMin
        val range = if (rawRange <= 0.0) visibleMin * 0.01 else rawRange
        val pad = range * 0.06
        val displayMin = visibleMin - pad
        val displayMax = visibleMax + pad
        val displayRange = displayMax - displayMin

        currentDisplayMin = displayMin
        currentDisplayRange = displayRange
        currentChartHeight = chartHeight

        fun priceToY(p: Double): Float {
          if (displayRange <= 0.0) return chartHeight / 2f
          val norm = (p - displayMin) / displayRange
          return (chartHeight - norm * chartHeight).toFloat().coerceIn(0f, chartHeight)
        }

        // 2. Сетка цен (4 горизонтальные линии) и метки на правой шкале
        val priceSteps = 4
        for (step in 0..priceSteps) {
          val frac = step.toFloat() / priceSteps
          val priceVal = displayMin + (1f - frac) * displayRange
          val lineY = frac * chartHeight

          drawLine(
            color = Color(0x1200D4FF),
            start = Offset(0f, lineY),
            end = Offset(chartWidth, lineY),
            strokeWidth = 1f
          )

          drawIntoCanvas { canvas ->
            val pText = formatPrice(priceVal)
            canvas.nativeCanvas.drawText(
              pText,
              chartWidth + 6.dp.toPx(),
              lineY + 3.dp.toPx(),
              pricePaint
            )
          }
        }

        // Разделительные линии шкалы
        drawLine(
          color = Color(0x3300D4FF),
          start = Offset(chartWidth, 0f),
          end = Offset(chartWidth, chartHeight),
          strokeWidth = 1.dp.toPx()
        )
        drawLine(
          color = Color(0x3300D4FF),
          start = Offset(0f, chartHeight),
          end = Offset(totalWidth, chartHeight),
          strokeWidth = 1.dp.toPx()
        )

        var lastDrawnTimeX = -100f
        val minTimeLabelGap = 55.dp.toPx()

        // Вспомогательная функция для отрисовки свечи
        fun drawSingleCandle(candle: Candle, candleCenterX: Float) {
          val isBull = candle.close >= candle.open
          val candleColor = if (isBull) HudGreen else HudRed

          val highY = priceToY(candle.high)
          val lowY = priceToY(candle.low)
          val openY = priceToY(candle.open)
          val closeY = priceToY(candle.close)

          // Фитиль (тень)
          drawLine(
            color = candleColor,
            start = Offset(candleCenterX, highY),
            end = Offset(candleCenterX, lowY),
            strokeWidth = 1.2.dp.toPx()
          )

          // Тело свечи
          val bodyTop = min(openY, closeY)
          val bodyHeight = max(abs(openY - closeY), 2.dp.toPx())
          drawRect(
            color = candleColor,
            topLeft = Offset(candleCenterX - (candleWidthPx / 2), bodyTop),
            size = Size(candleWidthPx, bodyHeight)
          )

          // Временная метка по нижнему краю
          if (candleCenterX - lastDrawnTimeX >= minTimeLabelGap && candleCenterX in 20f..(chartWidth - 20f)) {
            val tText = timeFormat.format(Date(candle.openTime))
            drawIntoCanvas { canvas ->
              canvas.nativeCanvas.drawText(
                tText,
                candleCenterX,
                chartHeight + 16.dp.toPx(),
                timePaint
              )
            }
            lastDrawnTimeX = candleCenterX
          }
        }

        // 3. ОТРИСОВКА СВЕЧЕЙ ИЛИ ЛИНИИ (Requirement 6)
        if (chartVisualType == ChartVisualType.LINE) {
          // Отрисовка линейного графика с градиентной заливкой
          val linePath = Path()
          val fillPath = Path()
          var firstPt: Offset? = null
          var lastPt: Offset? = null

          for (k in visibleCandles.indices) {
            val (idx, candle) = visibleCandles[k]
            val candleCenterX = chartWidth - (totalCandleCount - 1 - idx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
            val closeY = priceToY(candle.close)
            val pt = Offset(candleCenterX, closeY)

            if (k == 0) {
              linePath.moveTo(pt.x, pt.y)
              firstPt = pt
            } else {
              linePath.lineTo(pt.x, pt.y)
            }
            lastPt = pt

            // Временная метка по нижнему краю
            if (candleCenterX - lastDrawnTimeX >= minTimeLabelGap && candleCenterX in 20f..(chartWidth - 20f)) {
              val tText = timeFormat.format(Date(candle.openTime))
              drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(tText, candleCenterX, chartHeight + 16.dp.toPx(), timePaint)
              }
              lastDrawnTimeX = candleCenterX
            }
          }

          if (isLiveVisible && liveCandle != null && liveCandleCenterX != null) {
            val closeY = priceToY(liveCandle.close)
            val pt = Offset(liveCandleCenterX, closeY)
            if (firstPt == null) {
              linePath.moveTo(pt.x, pt.y)
              firstPt = pt
            } else {
              linePath.lineTo(pt.x, pt.y)
            }
            lastPt = pt

            if (liveCandleCenterX - lastDrawnTimeX >= minTimeLabelGap && liveCandleCenterX in 20f..(chartWidth - 20f)) {
              val tText = timeFormat.format(Date(liveCandle.openTime))
              drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(tText, liveCandleCenterX, chartHeight + 16.dp.toPx(), timePaint)
              }
              lastDrawnTimeX = liveCandleCenterX
            }
          }

          if (firstPt != null && lastPt != null) {
            fillPath.addPath(linePath)
            fillPath.lineTo(lastPt.x, chartHeight)
            fillPath.lineTo(firstPt.x, chartHeight)
            fillPath.close()

            drawPath(
              path = fillPath,
              brush = Brush.verticalGradient(
                listOf(HudCyan.copy(alpha = 0.25f), Color.Transparent),
                startY = 0f,
                endY = chartHeight
              )
            )

            drawPath(
              path = linePath,
              color = HudCyan,
              style = Stroke(width = 2.dp.toPx())
            )
          }
        } else {
          // Отрисовка японских свечей (Candlesticks)
          // Отрисовка закрытых свечей
          for ((idx, candle) in visibleCandles) {
            val candleCenterX = chartWidth - (totalCandleCount - 1 - idx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
            drawSingleCandle(candle, candleCenterX)
          }

          // Отрисовка текущей формирующейся liveCandle поверх (без пересоздания списка)
          if (isLiveVisible && liveCandle != null && liveCandleCenterX != null) {
            drawSingleCandle(liveCandle, liveCandleCenterX)
          }
        }

        // 4. Горизонтальная пунктирная линия текущей цены (только в Live-режиме)
        if (backtestResult == null && currentPrice in displayMin..displayMax) {
          val curY = priceToY(currentPrice)
          val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)

          drawLine(
            color = HudCyan,
            start = Offset(0f, curY),
            end = Offset(chartWidth, curY),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = dashPathEffect
          )

          val badgeH = 16.dp.toPx()
          val badgeTop = (curY - badgeH / 2).coerceIn(0f, chartHeight - badgeH)
          drawRect(
            color = Color(0xFF003852),
            topLeft = Offset(chartWidth + 2.dp.toPx(), badgeTop),
            size = Size(priceScaleWidthPx - 4.dp.toPx(), badgeH)
          )
          drawRect(
            color = HudCyan,
            topLeft = Offset(chartWidth + 2.dp.toPx(), badgeTop),
            size = Size(priceScaleWidthPx - 4.dp.toPx(), badgeH),
            style = Stroke(1.dp.toPx())
          )
          drawIntoCanvas { canvas ->
            val curText = formatPrice(currentPrice)
            canvas.nativeCanvas.drawText(
              curText,
              chartWidth + 5.dp.toPx(),
              badgeTop + badgeH - 4.dp.toPx(),
              curPriceBadgePaint
            )
          }
        }

        // 5. Интерактивное перекрестие (Crosshair) при касании
        touchPoint?.let { pt ->
          if (pt.x in 0f..chartWidth && pt.y in 0f..chartHeight) {
            val crossDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            drawLine(
              color = Color.White.copy(alpha = 0.5f),
              start = Offset(pt.x, 0f),
              end = Offset(pt.x, chartHeight),
              strokeWidth = 1f,
              pathEffect = crossDash
            )
            drawLine(
              color = Color.White.copy(alpha = 0.5f),
              start = Offset(0f, pt.y),
              end = Offset(chartWidth, pt.y),
              strokeWidth = 1f,
              pathEffect = crossDash
            )

            val relX = pt.x - clampedScrollOffset
            val idxFromRight = ((chartWidth - relX) / slotWidthPx).toInt()
            val targetIdx = totalCandleCount - 1 - idxFromRight
            if (targetIdx in closedCandles.indices) {
              touchedCandle = closedCandles[targetIdx]
            } else if (targetIdx == totalCandleCount - 1 && liveCandle != null) {
              touchedCandle = liveCandle
            }
          }
        }

        // 6. Отрисовка уровней сетки Grid Bot, маркеров границ и линии peakPrice (Live)
        if (backtestResult == null && gridState != null) {
          GridOverlayRenderer.drawGridOverlay(
            drawScope = this,
            gridState = gridState,
            currentPrice = currentPrice,
            chartWidth = chartWidth,
            chartHeight = chartHeight,
            priceScaleWidthPx = priceScaleWidthPx,
            displayMin = displayMin,
            displayMax = displayMax,
            priceToY = ::priceToY,
            gridPaint = pricePaint,
            badgePaint = curPriceBadgePaint,
            flashAlpha = flashAlpha
          )
        }

        // 7. Отрисовка результатов Бэктеста
        if (backtestResult != null) {
          // Если это бэктест Grid Bot — отрисовываем уровни сетки
          if (backtestResult.config.gridConfig != null) {
            val dummyGridState = GridBotState(
              isActive = true,
              config = backtestResult.config.gridConfig
            )
            GridOverlayRenderer.drawGridOverlay(
              drawScope = this,
              gridState = dummyGridState,
              currentPrice = 0.0,
              chartWidth = chartWidth,
              chartHeight = chartHeight,
              priceScaleWidthPx = priceScaleWidthPx,
              displayMin = displayMin,
              displayMax = displayMax,
              priceToY = ::priceToY,
              gridPaint = pricePaint,
              badgePaint = curPriceBadgePaint,
              flashAlpha = 0f
            )
          }

          // Отрисовка исполненных уровней сетки (Grid Fill Markers)
          for (fill in backtestResult.gridFillMarkers) {
            val fillIdx = findCandleIndexByTime(closedCandles, fill.time)
            val fillX = chartWidth - (totalCandleCount - 1 - fillIdx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
            val fillY = priceToY(fill.price)

            if (fillX in -15f..(chartWidth + 15f)) {
              val isBuy = fill.side == "BUY"
              val dotColor = if (isBuy) HudGreen else HudNeonPink

              drawCircle(
                color = dotColor,
                radius = 4.5f,
                center = Offset(fillX, fillY)
              )
              drawCircle(
                color = Color.White,
                radius = 2f,
                center = Offset(fillX, fillY)
              )

              if (!isBuy && fill.profitUsdt > 0.001) {
                drawIntoCanvas { canvas ->
                  val profitStr = "+$${"%.2f".format(Locale.US, fill.profitUsdt)}"
                  canvas.nativeCanvas.drawText(
                    profitStr,
                    fillX + 6f,
                    fillY - 4f,
                    tradeTagPaint.apply {
                      color = android.graphics.Color.argb(255, 0, 230, 118)
                    }
                  )
                }
              }
            }
          }

          // Отрисовка сделок сигнального бота (Signal Bot Trades: Entry/Exit + Connector + % PnL)
          for (marker in backtestResult.tradeMarkers) {
            val entryIdx = findCandleIndexByTime(closedCandles, marker.entryTime)
            val exitIdx = findCandleIndexByTime(closedCandles, marker.exitTime)

            val entryX = chartWidth - (totalCandleCount - 1 - entryIdx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
            val exitX = chartWidth - (totalCandleCount - 1 - exitIdx) * slotWidthPx + clampedScrollOffset - (slotWidthPx / 2)
            val entryY = priceToY(marker.entryPrice)
            val exitY = priceToY(marker.exitPrice)

            if (entryX in -40f..(chartWidth + 40f) || exitX in -40f..(chartWidth + 40f)) {
              // Зелёный треугольник вверх на entryPrice
              val entryTri = Path().apply {
                moveTo(entryX, entryY + 2f)
                lineTo(entryX - 6f, entryY + 12f)
                lineTo(entryX + 6f, entryY + 12f)
                close()
              }
              drawPath(entryTri, color = HudGreen)

              // Треугольник вниз на exitPrice
              val exitColor = if (marker.isProfit) HudGreen else HudRed
              val exitTri = Path().apply {
                moveTo(exitX, exitY - 2f)
                lineTo(exitX - 6f, exitY - 12f)
                lineTo(exitX + 6f, exitY - 12f)
                close()
              }
              drawPath(exitTri, color = exitColor)

              // Тонкая пунктирная линия входа-выхода
              val lineDash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
              drawLine(
                color = exitColor.copy(alpha = 0.85f),
                start = Offset(entryX, entryY),
                end = Offset(exitX, exitY),
                strokeWidth = 1.5f,
                pathEffect = lineDash
              )

              // Процент прибыли / убытка
              val pnlText = "${if (marker.pnlPercent >= 0) "+" else ""}${"%.2f".format(Locale.US, marker.pnlPercent)}%"
              drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                  pnlText,
                  exitX + 8f,
                  exitY - 4f,
                  tradeTagPaint.apply {
                    color = if (marker.isProfit) android.graphics.Color.argb(255, 0, 230, 118) else android.graphics.Color.argb(255, 255, 82, 82)
                  }
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun ChartTopBar(
  selectedSymbol: String,
  isBacktest: Boolean = false,
  onBack: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xDD0A182C), CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
      .border(1.2.dp, Color(0x3300D4FF), CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
      .padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.size(36.dp).testTag("chart_back_button")
      ) {
        Icon(
          Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = "Назад к дашборду",
          tint = HudCyan
        )
      }
      Spacer(Modifier.width(4.dp))
      Column {
        Text(
          "ГРАФИК И СЕТОЧНЫЙ БОТ",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          fontFamily = FontFamily.Monospace
        )
        Text(
          "BINANCE SPOT // $selectedSymbol",
          color = HudTextMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    // Бейдж соединения или бэктеста
    if (isBacktest) {
      Box(
        modifier = Modifier
          .background(Color(0xFF2C0A3E), RoundedCornerShape(4.dp))
          .border(1.dp, HudNeonPurple, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .background(HudNeonPurple, RoundedCornerShape(3.dp))
          )
          Spacer(Modifier.width(5.dp))
          Text(
            "БЭКТЕСТ",
            color = HudNeonPurple,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    } else {
      Box(
        modifier = Modifier
          .background(Color(0xFF003820), RoundedCornerShape(4.dp))
          .border(1.dp, HudGreen, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 4.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .background(HudGreen, RoundedCornerShape(3.dp))
          )
          Spacer(Modifier.width(5.dp))
          Text(
            "LIVE WS",
            color = HudGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}
