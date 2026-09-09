package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import com.example.model.Candle
import com.example.model.TradingPair
import com.example.ui.components.HudCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

  // При старте экрана или смене пары подписываем тикер
  LaunchedEffect(selectedSymbol) {
    wsService.subscribeToTicker(selectedSymbol)
  }

  // ИЗОЛИРОВАННЫЙ БУФЕР СВЕЧЕЙ (ровно 150 свечей для текущей пары и таймфрейма)
  var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
  var isLoadingCandles by remember { mutableStateOf(true) }
  var chartError by remember { mutableStateOf<String?>(null) }

  // При КАЖДОЙ смене пары ИЛИ таймфрейма:
  // 1. Полностью очищаем буфер свечей из памяти
  // 2. Отменяем предыдущую WebSocket-подписку на kline
  // 3. Запрашиваем ровно 150 свечей REST под новую пару + таймфрейм
  // 4. Подписываемся на новый живой WebSocket kline
  LaunchedEffect(selectedSymbol, selectedInterval) {
    candles = emptyList()
    isLoadingCandles = true
    chartError = null

    wsService.unsubscribeKline()

    try {
      val freshList = marketService.fetchCandles(selectedSymbol, selectedInterval, 150)
      candles = freshList.takeLast(150)
      isLoadingCandles = false
    } catch (e: Exception) {
      chartError = e.message ?: "Сбой загрузки свечей"
      isLoadingCandles = false
    }

    // Подключаем живой поток свечей для текущей активной комбинации
    wsService.subscribeToKline(selectedSymbol, selectedInterval)
  }

  // Обновление ТОЛЬКО текущего активного буфера свечей из WebSocket
  LaunchedEffect(selectedSymbol, selectedInterval) {
    wsService.klineFlow.collect { update ->
      if (update.symbol.equals(selectedSymbol, ignoreCase = true) &&
        update.interval.equals(selectedInterval, ignoreCase = true)
      ) {
        val current = candles.toMutableList()
        if (current.isNotEmpty()) {
          val lastIdx = current.lastIndex
          val last = current[lastIdx]
          if (last.openTime == update.candle.openTime) {
            // Обновляем текущую свечу в реальном времени
            current[lastIdx] = update.candle
          } else if (update.candle.openTime > last.openTime) {
            // Новая свеча: добавляем в конец
            current.add(update.candle)
            // Буфер остаётся ровно до 150 свечей — сдвигающееся окно
            while (current.size > 150) {
              current.removeAt(0)
            }
          }
          candles = current
        } else {
          candles = listOf(update.candle)
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
    containerColor = HudNavyDark
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(backgroundBrush)
        .padding(innerPadding)
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
        // ВЕРХНИЙ ТУЛБАР: Кнопка Назад + Заголовок + Бейдж Live
        ChartTopBar(
          selectedSymbol = selectedSymbol,
          onBack = onBackToDashboard
        )

        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
          // 1. СЕЛЕКТОР ПАРЫ + ТЕКУЩАЯ ЦЕНА
          val currentPrice = tickerData?.lastPrice ?: 0.0
          val changePct = tickerData?.priceChangePercent ?: 0.0
          val isPositive = changePct >= 0
          val changeColor = if (isPositive) HudGreen else HudRed
          val high24h = tickerData?.highPrice ?: 0.0
          val low24h = tickerData?.lowPrice ?: 0.0

          HudCard(
            title = "ИНСТРУМЕНТ // РЫНОЧНЫЙ ТИКЕР",
            icon = Icons.Outlined.ShowChart,
            borderColor = HudNeonPurple.copy(alpha = 0.8f),
            modifier = Modifier.fillMaxWidth()
          ) {
            // Кнопка выбора пары
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .border(1.dp, HudNeonPink.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .clickable { showPairDialog = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  Icons.Outlined.CurrencyExchange,
                  contentDescription = null,
                  tint = HudNeonPink,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  selectedSymbol,
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 16.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "СМЕНИТЬ ПАРУ",
                  color = HudCyan,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = HudCyan)
              }
            }

            Spacer(Modifier.height(10.dp))

            // Крупная строка текущей цены
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  "ТЕКУЩАЯ ЦЕНА (BINANCE WS)",
                  color = HudTextMuted,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace
                )
                Text(
                  text = formatPrice(currentPrice),
                  color = Color.White,
                  fontSize = 28.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace,
                  letterSpacing = 1.sp
                )
              }

              Box(
                modifier = Modifier
                  .background(changeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                  .border(1.2.dp, changeColor, RoundedCornerShape(6.dp))
                  .padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                Text(
                  text = "${if (isPositive) "+" else ""}${"%.2f".format(Locale.US, changePct)}%",
                  color = changeColor,
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }

            Spacer(Modifier.height(6.dp))

            // 24h High / 24h Low
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                "24h Max: ${formatPrice(high24h)}",
                color = HudGreen.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
              Text(
                "24h Min: ${formatPrice(low24h)}",
                color = HudRed.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }

          Spacer(Modifier.height(10.dp))

          // 2. ПЕРЕКЛЮЧАТЕЛЬ ТАЙМФРЕЙМА (1m / 5m / 15m / 1h / 4h / 1d)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF071220), CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
              .border(1.dp, Color(0x3300D4FF), CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
              .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            intervals.forEach { interval ->
              val isSelected = interval == selectedInterval
              val buttonBg = if (isSelected) {
                Brush.linearGradient(listOf(HudNeonPink, HudNeonPurple))
              } else {
                Brush.linearGradient(listOf(Color(0xFF0A182C), Color(0xFF0A182C)))
              }
              val borderBrush = if (isSelected) {
                Brush.linearGradient(listOf(Color.White, HudNeonPink))
              } else {
                Brush.linearGradient(listOf(Color(0x2200D4FF), Color(0x2200D4FF)))
              }

              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(buttonBg, CutCornerShape(4.dp))
                  .border(1.dp, borderBrush, CutCornerShape(4.dp))
                  .clickable {
                    if (selectedInterval != interval) {
                      selectedInterval = interval
                    }
                  }
                  .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = interval,
                  color = if (isSelected) Color.White else HudTextMuted,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 12.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }

          Spacer(Modifier.height(10.dp))

          // 3. ОБЛАСТЬ ГРАФИКА СВЕЧЕЙ (CANVAS)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .background(Color(0xFF071220), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
              .border(1.2.dp, HudNeonBlue.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
          ) {
            when {
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
                          candles = freshList.takeLast(150)
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

              candles.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Text("НЕТ ДАННЫХ ПО СВЕЧАМ", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
              }

              else -> {
                CandlestickChart(
                  candles = candles,
                  currentPrice = currentPrice,
                  interval = selectedInterval,
                  modifier = Modifier.fillMaxSize()
                )
              }
            }
          }
        }
      }
    }
  }

  // ДИАЛОГ ВЫБОРА ТОРГОВОЙ ПАРЫ С ЖИВЫМ ПОИСКОМ
  if (showPairDialog) {
    val filteredPairs = remember(pairs, searchQuery) {
      if (searchQuery.isBlank()) pairs
      else pairs.filter { it.symbol.contains(searchQuery.trim().uppercase(), ignoreCase = true) }
    }

    AlertDialog(
      onDismissRequest = { showPairDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.CurrencyExchange, contentDescription = null, tint = HudNeonPink)
          Spacer(Modifier.width(8.dp))
          Text(
            "ВЫБОР ПАРЫ (USDT)",
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
            placeholder = { Text("Поиск пары (BTC, ETH, SOL...)", color = HudTextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = HudNeonPink,
              unfocusedBorderColor = Color(0x3300D4FF),
              focusedContainerColor = Color(0xFF071220),
              unfocusedContainerColor = Color(0xFF071220)
            )
          )

          Spacer(Modifier.height(8.dp))

          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .height(280.dp)
          ) {
            items(filteredPairs) { pair ->
              val isCurrent = pair.symbol == selectedSymbol
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(if (isCurrent) Color(0x33FF2A85) else Color.Transparent)
                  .clickable {
                    if (selectedSymbol != pair.symbol) {
                      selectedSymbol = pair.symbol
                    }
                    showPairDialog = false
                  }
                  .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  pair.symbol,
                  color = if (isCurrent) HudNeonPink else Color.White,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp
                )
                Text(
                  "Мин: ${pair.minNotional} USDT",
                  color = HudTextMuted,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
              HorizontalDivider(color = Color(0x1A00D4FF))
            }
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
}

/**
 * Отрисовка свечей на чистом Canvas без сторонних библиотек:
 * - Зеленый/красный прямоугольник тела свечи (close >= open -> зеленый, иначе красный)
 * - Тонкий фитиль/тень по high/low
 * - Горизонтальный drag-скролл
 * - Автомасштабирование по видимому диапазону high/low
 * - Подписи цен по правой шкале и времени снизу
 * - Пунктирная неоновая линия текущей цены (last price из tickerFlow)
 * - Интерактивное перекрестие (Crosshair) при удержании/касании
 */
@Composable
fun CandlestickChart(
  candles: List<Candle>,
  currentPrice: Double,
  interval: String,
  modifier: Modifier = Modifier
) {
  val density = LocalDensity.current

  // Настройки отображения
  val candleWidthPx = with(density) { 9.dp.toPx() }
  val candleGapPx = with(density) { 4.dp.toPx() }
  val slotWidthPx = candleWidthPx + candleGapPx
  val priceScaleWidthPx = with(density) { 68.dp.toPx() }
  val timeScaleHeightPx = with(density) { 24.dp.toPx() }

  // Смещение скролла (в пикселях). 0f — свежие свечи прижаты к правой шкале цен
  var scrollOffset by remember { mutableFloatStateOf(0f) }

  // Состояние касания для перекрестия (Crosshair)
  var touchPoint by remember { mutableStateOf<Offset?>(null) }
  var touchedCandle by remember { mutableStateOf<Candle?>(null) }

  // Сброс скролла при смене размера буфера/набора
  LaunchedEffect(candles.size) {
    scrollOffset = 0f
    touchPoint = null
    touchedCandle = null
  }

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

  // Формат времени в зависимости от таймфрейма
  val timeFormat = remember(interval) {
    if (interval == "1d") SimpleDateFormat("dd.MM", Locale.US)
    else SimpleDateFormat("HH:mm", Locale.US)
  }

  Column(modifier = modifier.fillMaxSize()) {
    // Верхняя информационная строка: отображение O/H/L/C для выбранной свечи или последней
    val displayCandle = touchedCandle ?: candles.lastOrNull()
    if (displayCandle != null) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF0A182C))
          .padding(horizontal = 8.dp, vertical = 4.dp),
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
        .pointerInput(candles.size) {
          detectDragGestures(
            onDragStart = { offset ->
              touchPoint = offset
            },
            onDragEnd = {
              touchPoint = null
              touchedCandle = null
            },
            onDragCancel = {
              touchPoint = null
              touchedCandle = null
            },
            onDrag = { change, dragAmount ->
              change.consume()
              scrollOffset += dragAmount.x
              touchPoint = change.position
            }
          )
        }
        .pointerInput(candles.size) {
          detectTapGestures(
            onPress = { offset ->
              touchPoint = offset
              tryAwaitRelease()
              touchPoint = null
              touchedCandle = null
            }
          )
        }
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val totalWidth = size.width
        val totalHeight = size.height

        val chartWidth = (totalWidth - priceScaleWidthPx).coerceAtLeast(10f)
        val chartHeight = (totalHeight - timeScaleHeightPx).coerceAtLeast(10f)

        if (candles.isEmpty()) return@Canvas

        // Ограничение диапазона скролла
        val contentWidth = candles.size * slotWidthPx
        val minScroll = if (contentWidth > chartWidth) -(contentWidth - chartWidth) else 0f
        val maxScroll = 0f
        scrollOffset = scrollOffset.coerceIn(minScroll, maxScroll)

        // 1. Определение видимых свечей и их экстремумов (АВТОМАСШТАБ)
        val visibleCandles = mutableListOf<Pair<Int, Candle>>()
        for (i in candles.indices) {
          val candleCenterX = chartWidth - (candles.size - 1 - i) * slotWidthPx + scrollOffset - (slotWidthPx / 2)
          if (candleCenterX + slotWidthPx >= 0 && candleCenterX - slotWidthPx <= chartWidth) {
            visibleCandles.add(i to candles[i])
          }
        }

        val pool = if (visibleCandles.isNotEmpty()) visibleCandles.map { it.second } else candles
        val visibleMin = pool.minOfOrNull { it.low } ?: 0.0
        val visibleMax = pool.maxOfOrNull { it.high } ?: 1.0

        val rawRange = visibleMax - visibleMin
        val range = if (rawRange <= 0.0) visibleMin * 0.01 else rawRange
        val pad = range * 0.06
        val displayMin = visibleMin - pad
        val displayMax = visibleMax + pad
        val displayRange = displayMax - displayMin

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

          // Горизонтальная линия сетки
          drawLine(
            color = Color(0x1200D4FF),
            start = Offset(0f, lineY),
            end = Offset(chartWidth, lineY),
            strokeWidth = 1f
          )

          // Текст цены справа
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

        // Разделительная линия между графиком и правой шкалой
        drawLine(
          color = Color(0x3300D4FF),
          start = Offset(chartWidth, 0f),
          end = Offset(chartWidth, chartHeight),
          strokeWidth = 1.dp.toPx()
        )

        // Разделительная линия между графиком и нижней временной шкалой
        drawLine(
          color = Color(0x3300D4FF),
          start = Offset(0f, chartHeight),
          end = Offset(totalWidth, chartHeight),
          strokeWidth = 1.dp.toPx()
        )

        // 3. Отрисовка свечей и временных меток
        var lastDrawnTimeX = -100f
        val minTimeLabelGap = 55.dp.toPx()

        for ((idx, candle) in visibleCandles) {
          val candleCenterX = chartWidth - (candles.size - 1 - idx) * slotWidthPx + scrollOffset - (slotWidthPx / 2)

          val isBull = candle.close >= candle.open
          val candleColor = if (isBull) HudGreen else HudRed

          val highY = priceToY(candle.high)
          val lowY = priceToY(candle.low)
          val openY = priceToY(candle.open)
          val closeY = priceToY(candle.close)

          // Фитиль (тень свечи)
          drawLine(
            color = candleColor,
            start = Offset(candleCenterX, highY),
            end = Offset(candleCenterX, lowY),
            strokeWidth = 1.2.dp.toPx()
          )

          // Тело свечи (прямоугольник)
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

        // 4. Горизонтальная пунктирная линия текущей цены (last price из tickerFlow)
        if (currentPrice in displayMin..displayMax) {
          val curY = priceToY(currentPrice)
          val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)

          drawLine(
            color = HudCyan,
            start = Offset(0f, curY),
            end = Offset(chartWidth, curY),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = dashPathEffect
          )

          // Бейдж текущей цены на правой шкале
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
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
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
            // Вертикальная линия перекрестия
            drawLine(
              color = Color.White.copy(alpha = 0.5f),
              start = Offset(pt.x, 0f),
              end = Offset(pt.x, chartHeight),
              strokeWidth = 1f,
              pathEffect = crossDash
            )
            // Горизонтальная линия перекрестия
            drawLine(
              color = Color.White.copy(alpha = 0.5f),
              start = Offset(0f, pt.y),
              end = Offset(chartWidth, pt.y),
              strokeWidth = 1f,
              pathEffect = crossDash
            )

            // Определение свечи под пальцем
            val relX = pt.x - scrollOffset
            val idxFromRight = ((chartWidth - relX) / slotWidthPx).toInt()
            val targetIdx = candles.size - 1 - idxFromRight
            if (targetIdx in candles.indices) {
              touchedCandle = candles[targetIdx]
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
          imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = "Назад",
          tint = HudCyan
        )
      }
      Spacer(Modifier.width(4.dp))
      Column {
        Text(
          "ГРАФИК // СВЕЧИ BINANCE",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          letterSpacing = 1.sp
        )
        Text(
          "БУФЕР 150 СВЕЧЕЙ // ТЕКУЩИЙ ТАЙМФРЕЙМ",
          color = HudNeonPink,
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    // Неоновый индикатор WebSocket
    Box(
      modifier = Modifier
        .background(HudGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
        .border(1.dp, HudGreen.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
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
          "WS LIVE",
          color = HudGreen,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace
        )
      }
    }
  }
}

/**
 * Универсальное форматирование цены инструмента
 */
fun formatPrice(price: Double): String {
  return when {
    price >= 1000.0 -> String.format(Locale.US, "%.2f", price)
    price >= 1.0 -> String.format(Locale.US, "%.3f", price)
    price >= 0.001 -> String.format(Locale.US, "%.5f", price)
    price > 0.0 -> String.format(Locale.US, "%.7f", price)
    else -> "--.--"
  }
}
