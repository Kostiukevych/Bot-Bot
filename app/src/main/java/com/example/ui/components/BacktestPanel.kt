package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MyApplication
import com.example.model.*
import com.example.ui.formatPrice
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

enum class BacktestPeriodPreset(val title: String, val hours: Int) {
  H24("24 ч", 24),
  D3("3 дня", 72),
  D7("7 дней", 168),
  D30("30 дней", 720),
  CUSTOM("Свой ввод", 0)
}

/**
 * Вычисляет примерное количество свечей для заданного таймфрейма и часов.
 */
fun estimateCandleCount(hours: Int, interval: String): Int {
  val intervalMinutes = when (interval) {
    "1m" -> 1
    "5m" -> 5
    "15m" -> 15
    "1h" -> 60
    "4h" -> 240
    "1d" -> 1440
    else -> 15
  }
  return if (hours > 0) (hours * 60) / intervalMinutes else 300
}

/**
 * Модальное окно конфигурации и запуска бэктеста для сигнального или сеточного бота.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestBottomSheet(
  symbol: String,
  interval: String,
  botType: BacktestBotType,
  onDismiss: () -> Unit,
  onBacktestStarted: () -> Unit = {},
  onBacktestFinished: (BacktestResult) -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val app = MyApplication.instance
  val marketService = app.botEngine.marketService
  val strategyService = app.botEngine.strategyService
  val backtestEngine = app.backtestEngine

  // Выбранный тип бота для бэктеста (можно переключать внутри шторки)
  var currentBotType by remember(botType) { mutableStateOf(botType) }

  // Период
  var selectedPreset by remember { mutableStateOf(BacktestPeriodPreset.D7) }
  var customCandlesText by remember { mutableStateOf("500") }

  // Начальный депозит
  var initialDepositText by remember { mutableStateOf("1000") }

  // Параметры для SIGNAL_BOT
  val currentBotState = app.botEngine.stateFlow.collectAsState().value
  var thresholdVal by remember {
    mutableFloatStateOf(currentBotState.strategyConfig.minScoreThreshold.toFloat())
  }
  var stopLossPctText by remember {
    mutableStateOf(currentBotState.strategyConfig.stopLossPercent.toString())
  }
  var takeProfitPctText by remember {
    mutableStateOf(currentBotState.strategyConfig.takeProfitPercent.toString())
  }

  // Параметры для GRID_BOT
  val currentGridState = app.gridBotEngine.stateFlow.collectAsState().value
  var gridRangePctText by remember {
    mutableStateOf(currentGridState.config.rangePercent.toString())
  }
  var gridLevelCount by remember {
    mutableFloatStateOf(currentGridState.config.levelCount.toFloat())
  }
  var trailingEnabled by remember {
    mutableStateOf(currentGridState.config.trailingTriggerPercent > 0)
  }

  // Состояние загрузки
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Расчет ожидаемого числа свечей
  val targetCandleCount = if (selectedPreset == BacktestPeriodPreset.CUSTOM) {
    customCandlesText.toIntOrNull() ?: 300
  } else {
    estimateCandleCount(selectedPreset.hours, interval)
  }

  val isOverLimit = targetCandleCount > 5000

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = HudCardBg,
    contentColor = Color.White,
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(vertical = 10.dp)
          .size(width = 44.dp, height = 4.dp)
          .background(Color(0x6600D4FF), RoundedCornerShape(2.dp))
      )
    }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .padding(bottom = 32.dp)
    ) {
      // Заголовок: синхронизирован с currentBotType
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Outlined.Science,
            contentDescription = null,
            tint = if (currentBotType == BacktestBotType.SIGNAL_BOT) HudCyan else HudNeonPink,
            modifier = Modifier.size(22.dp)
          )
          Spacer(Modifier.width(8.dp))
          Column {
            Text(
              if (currentBotType == BacktestBotType.SIGNAL_BOT) "БЭКТЕСТ: СИГНАЛЬНЫЙ БОТ" else "БЭКТЕСТ: СЕТОЧНЫЙ БОТ",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp,
              fontFamily = FontFamily.Monospace
            )
            Text(
              "$symbol · ТАЙМФРЕЙМ $interval",
              color = HudTextMuted,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        Box(
          modifier = Modifier
            .background(
              if (currentBotType == BacktestBotType.SIGNAL_BOT) Color(0x2200D4FF) else Color(0x22FF2A85),
              RoundedCornerShape(4.dp)
            )
            .border(
              1.dp,
              if (currentBotType == BacktestBotType.SIGNAL_BOT) HudCyan else HudNeonPink,
              RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
          Text(
            if (currentBotType == BacktestBotType.SIGNAL_BOT) "SIGNAL" else "GRID",
            color = if (currentBotType == BacktestBotType.SIGNAL_BOT) HudCyan else HudNeonPink,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      Spacer(Modifier.height(14.dp))

      // ПЕРЕКЛЮЧАТЕЛЬ ТИПА БОТА (СИГНАЛЬНЫЙ / СЕТОЧНЫЙ)
      Text("ТИП АЛГОРИТМА ДЛЯ БЭКТЕСТА:", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.height(6.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF071220), RoundedCornerShape(8.dp))
          .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(8.dp))
          .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        val isSignalSelected = currentBotType == BacktestBotType.SIGNAL_BOT
        val isGridSelected = currentBotType == BacktestBotType.GRID_BOT

        // Таб 1: Сигнальный бот
        Box(
          modifier = Modifier
            .weight(1f)
            .background(
              if (isSignalSelected) HudCyan else Color.Transparent,
              RoundedCornerShape(6.dp)
            )
            .clickable { currentBotType = BacktestBotType.SIGNAL_BOT }
            .padding(vertical = 8.dp)
            .testTag("backtest_tab_signal_bot"),
          contentAlignment = Alignment.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.AutoMirrored.Outlined.TrendingUp,
              contentDescription = null,
              tint = if (isSignalSelected) HudNavyDark else HudTextMuted,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
              "Сигнальный бот",
              color = if (isSignalSelected) HudNavyDark else Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        // Таб 2: Сеточный бот
        Box(
          modifier = Modifier
            .weight(1f)
            .background(
              if (isGridSelected) HudNeonPink else Color.Transparent,
              RoundedCornerShape(6.dp)
            )
            .clickable { currentBotType = BacktestBotType.GRID_BOT }
            .padding(vertical = 8.dp)
            .testTag("backtest_tab_grid_bot"),
          contentAlignment = Alignment.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Outlined.GridOn,
              contentDescription = null,
              tint = if (isGridSelected) Color.White else HudTextMuted,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
              "Сеточный бот",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }

      Spacer(Modifier.height(14.dp))

      // 1. Выбор периода бэктеста
      Text("ПЕРИОД ТЕСТИРОВАНИЯ:", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      Spacer(Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        BacktestPeriodPreset.values().forEach { preset ->
          val isSel = selectedPreset == preset
          Box(
            modifier = Modifier
              .weight(1f)
              .background(if (isSel) HudCyan else Color(0xFF0C1E36), RoundedCornerShape(6.dp))
              .border(1.dp, if (isSel) HudCyan else Color(0x3300D4FF), RoundedCornerShape(6.dp))
              .clickable { selectedPreset = preset }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              preset.title,
              color = if (isSel) HudNavyDark else Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }

      // Кастомный ввод количества свечей
      if (selectedPreset == BacktestPeriodPreset.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = customCandlesText,
          onValueChange = { customCandlesText = it.filter { ch -> ch.isDigit() }.take(5) },
          label = { Text("Количество свечей (до 5000)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = HudCyan,
            unfocusedBorderColor = Color(0x4400D4FF),
            focusedLabelColor = HudCyan
          )
        )
      }

      Spacer(Modifier.height(6.dp))
      Text(
        "Расчёт свечей: ~$targetCandleCount шт. (для интервала $interval)",
        color = if (isOverLimit) HudRed else HudCyan,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
      )

      // Предупреждение о превышении 5000 свечей
      if (isOverLimit) {
        Spacer(Modifier.height(6.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33FFB300), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(6.dp))
            .padding(8.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              "Слишком большой период ($targetCandleCount свечей). Рекомендуется уменьшить период или выбрать старший таймфрейм для ускорения загрузки.",
              color = Color.White,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }

      Spacer(Modifier.height(14.dp))

      // 2. Начальный депозит
      OutlinedTextField(
        value = initialDepositText,
        onValueChange = { initialDepositText = it.filter { ch -> ch.isDigit() || ch == '.' } },
        label = { Text("Начальный баланс (USDT)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = HudCyan,
          unfocusedBorderColor = Color(0x4400D4FF),
          focusedLabelColor = HudCyan
        )
      )

      Spacer(Modifier.height(14.dp))

      // 3. Параметры стратегии (отображаются в зависимости от выбранного таба currentBotType)
      if (currentBotType == BacktestBotType.SIGNAL_BOT) {
        Text("ПАРАМЕТРЫ СИГНАЛЬНОЙ СТРАТЕГИИ:", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        // Порог входа (Score Threshold)
        Text(
          "Мин. Score для входа: ${thresholdVal.toInt()}%",
          color = Color.White,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold
        )
        Slider(
          value = thresholdVal,
          onValueChange = { thresholdVal = it },
          valueRange = 30f..85f,
          steps = 10,
          colors = SliderDefaults.colors(
            thumbColor = HudCyan,
            activeTrackColor = HudCyan,
            inactiveTrackColor = Color(0x3300D4FF)
          )
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = stopLossPctText,
            onValueChange = { stopLossPctText = it },
            label = { Text("Stop-Loss %", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = HudRed,
              unfocusedBorderColor = Color(0x44FF5252),
              focusedLabelColor = HudRed
            )
          )

          OutlinedTextField(
            value = takeProfitPctText,
            onValueChange = { takeProfitPctText = it },
            label = { Text("Take-Profit %", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = HudGreen,
              unfocusedBorderColor = Color(0x4400E676),
              focusedLabelColor = HudGreen
            )
          )
        }
      } else {
        // Параметры для GRID_BOT
        Text("ПАРАМЕТРЫ СЕТОЧНОГО БОТА:", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = gridRangePctText,
            onValueChange = { gridRangePctText = it },
            label = { Text("Диапазон цены ±%", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = HudNeonPink,
              unfocusedBorderColor = Color(0x44FF2A85),
              focusedLabelColor = HudNeonPink
            )
          )

          Column(modifier = Modifier.weight(1f)) {
            Text(
              "Уровней: ${gridLevelCount.toInt()}",
              color = Color.White,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold
            )
            Slider(
              value = gridLevelCount,
              onValueChange = { gridLevelCount = it },
              valueRange = 3f..40f,
              steps = 36,
              colors = SliderDefaults.colors(
                thumbColor = HudNeonPink,
                activeTrackColor = HudNeonPink,
                inactiveTrackColor = Color(0x33FF2A85)
              )
            )
          }
        }

        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Трейлинг за пиком цены", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Switch(
            checked = trailingEnabled,
            onCheckedChange = { trailingEnabled = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = HudNeonPurple,
              checkedTrackColor = Color(0x44B026FF)
            )
          )
        }
      }

      if (errorMessage != null) {
        Spacer(Modifier.height(10.dp))
        Text(errorMessage!!, color = HudRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      }

      Spacer(Modifier.height(20.dp))

      // Кнопка запуска
      Button(
        onClick = {
          isLoading = true
          errorMessage = null
          onBacktestStarted()

          scope.launch {
            try {
              val initBal = initialDepositText.toDoubleOrNull() ?: 1000.0
              val candleCnt = targetCandleCount.coerceIn(50, 5000)

              val config = BacktestConfig(
                symbol = symbol,
                interval = interval,
                botType = currentBotType,
                candleCount = candleCnt,
                initialBalanceUsdt = initBal,
                strategyRiskConfig = StrategyRiskConfig(
                  interval = interval,
                  stopLossPercent = stopLossPctText.toDoubleOrNull() ?: 2.0,
                  takeProfitPercent = takeProfitPctText.toDoubleOrNull() ?: 4.0,
                  minScoreThreshold = thresholdVal.toInt(),
                  maxDepositRiskPercent = 25.0
                ),
                gridConfig = GridConfig(
                  symbol = symbol,
                  tradingInterval = interval,
                  levelCount = gridLevelCount.toInt(),
                  rangePercent = gridRangePctText.toDoubleOrNull() ?: 4.0,
                  totalInvestmentUsdt = initBal,
                  trailingTriggerPercent = if (trailingEnabled) 3.0 else 0.0
                )
              )

              val result = if (currentBotType == BacktestBotType.SIGNAL_BOT) {
                backtestEngine.runSignalBotBacktest(config, marketService, strategyService)
              } else {
                backtestEngine.runGridBotBacktest(config, marketService)
              }

              isLoading = false
              sheetState.hide()
              onDismiss()
              onBacktestFinished(result)
            } catch (e: Exception) {
              isLoading = false
              errorMessage = "Ошибка бэктеста: ${e.message}"
            }
          }
        },
        enabled = !isLoading,
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp)
          .testTag("run_backtest_button"),
        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = if (currentBotType == BacktestBotType.SIGNAL_BOT) HudCyan else HudNeonPink
        )
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = HudNavyDark,
            strokeWidth = 2.dp
          )
          Spacer(Modifier.width(10.dp))
          Text(
            "СИМУЛЯЦИЯ ИСТОРИИ...",
            color = HudNavyDark,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
          )
        } else {
          Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = HudNavyDark)
          Spacer(Modifier.width(6.dp))
          Text(
            "ЗАПУСТИТЬ БЭКТЕСТ",
            color = HudNavyDark,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }
  }
}

/**
 * Карточка статистических результатов бэктеста в HUD-стиле.
 */
@Composable
fun BacktestStatsCard(
  result: BacktestResult,
  onReset: () -> Unit,
  modifier: Modifier = Modifier
) {
  val stats = result.stats
  val isProfit = stats.totalPnlUsdt >= 0
  val pnlColor = if (isProfit) HudGreen else HudRed
  val vsBnH = stats.totalPnlPercent - stats.buyAndHoldPnlPercent
  val bnhColor = if (vsBnH >= 0) HudGreen else HudRed

  HudCard(
    title = "РЕЗУЛЬТАТЫ БЭКТЕСТА // СТАТИСТИКА",
    icon = Icons.Outlined.QueryStats,
    borderColor = pnlColor,
    modifier = modifier.fillMaxWidth().testTag("backtest_stats_card")
  ) {
    // Верхняя плашка PnL
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(pnlColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
        .border(1.2.dp, pnlColor, RoundedCornerShape(8.dp))
        .padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text("ИТОГОВЫЙ PNL", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
          "${if (isProfit) "+" else ""}${"%.2f".format(Locale.US, stats.totalPnlUsdt)} USDT",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          fontFamily = FontFamily.Monospace
        )
      }

      Box(
        modifier = Modifier
          .background(pnlColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
          .border(1.dp, pnlColor, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp)
      ) {
        Text(
          "${if (isProfit) "+" else ""}${"%.2f".format(Locale.US, stats.totalPnlPercent)}%",
          color = pnlColor,
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    Spacer(Modifier.height(10.dp))

    // Сетка метрик 2x3
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      MetricBox(
        title = "WIN RATE",
        value = "${"%.1f".format(Locale.US, stats.winRatePercent)}%",
        subValue = "${stats.winTrades}W / ${stats.lossTrades}L",
        color = if (stats.winRatePercent >= 50) HudGreen else HudRed,
        modifier = Modifier.weight(1f)
      )

      MetricBox(
        title = "PROFIT FACTOR",
        value = "%.2f".format(Locale.US, stats.profitFactor),
        subValue = if (stats.profitFactor >= 1.5) "ОТЛИЧНО" else "СРЕДНЕ",
        color = HudCyan,
        modifier = Modifier.weight(1f)
      )
    }

    Spacer(Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      MetricBox(
        title = "MAX ПРОСАДКА",
        value = "-${"%.2f".format(Locale.US, stats.maxDrawdownPercent)}%",
        subValue = "от пика депозита",
        color = HudRed,
        modifier = Modifier.weight(1f)
      )

      MetricBox(
        title = "BUY & HOLD",
        value = "${if (stats.buyAndHoldPnlPercent >= 0) "+" else ""}${"%.2f".format(Locale.US, stats.buyAndHoldPnlPercent)}%",
        subValue = "vs бот: ${if (vsBnH >= 0) "+" else ""}${"%.1f".format(Locale.US, vsBnH)}%",
        color = bnhColor,
        modifier = Modifier.weight(1f)
      )
    }

    Spacer(Modifier.height(10.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF071220), RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        "СВЕЧЕЙ: ${result.candles.size} · СДЕЛОК: ${stats.totalTrades}",
        color = HudTextMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
      )
      Text(
        "ДЕПОЗИТ: ${"%.0f".format(Locale.US, result.config.initialBalanceUsdt)} USDT",
        color = HudCyan,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
      )
    }

    Spacer(Modifier.height(12.dp))

    // Кнопка возврата к live-графику
    OutlinedButton(
      onClick = onReset,
      modifier = Modifier
        .fillMaxWidth()
        .height(42.dp)
        .testTag("reset_backtest_button"),
      shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
      colors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color(0x1A00D4FF),
        contentColor = HudCyan
      ),
      border = androidx.compose.foundation.BorderStroke(1.2.dp, HudCyan)
    ) {
      Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
      Text(
        "ПОКАЗАТЬ ОБЫЧНЫЙ ГРАФИК (LIVE)",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp
      )
    }
  }
}

@Composable
private fun MetricBox(
  title: String,
  value: String,
  subValue: String,
  color: Color,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
      .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
      .padding(8.dp)
  ) {
    Column {
      Text(title, color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
      Text(
        value,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace
      )
      Text(subValue, color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
  }
}

/**
 * График кривой капитала во времени (Equity Curve).
 */
@Composable
fun EquityCurveChart(
  equity: List<EquityPoint>,
  modifier: Modifier = Modifier
) {
  if (equity.size < 2) return

  val density = LocalDensity.current
  val textPaint = remember {
    android.graphics.Paint().apply {
      color = android.graphics.Color.argb(200, 126, 155, 184)
      textSize = with(density) { 8.5.sp.toPx() }
      typeface = android.graphics.Typeface.MONOSPACE
      isAntiAlias = true
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
      .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
      .padding(8.dp)
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.AutoMirrored.Outlined.TrendingUp, contentDescription = null, tint = HudCyan, modifier = Modifier.size(14.dp))
          Spacer(Modifier.width(4.dp))
          Text(
            "КРИВАЯ ДЕПОЗИТА // EQUITY CURVE",
            color = HudCyan,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        val startBal = equity.first().balanceUsdt
        val endBal = equity.last().balanceUsdt
        val diff = endBal - startBal
        val diffPct = if (startBal > 0) (diff / startBal) * 100.0 else 0.0
        val color = if (diff >= 0) HudGreen else HudRed
        Text(
          "${if (diff >= 0) "+" else ""}${"%.2f".format(Locale.US, diff)} USDT (${"%.2f".format(Locale.US, diffPct)}%)",
          color = color,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }

      Spacer(Modifier.height(6.dp))

      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(65.dp)
      ) {
        val w = size.width
        val h = size.height
        if (w <= 10f || h <= 10f) return@Canvas

        val minVal = equity.minOf { it.balanceUsdt }
        val maxVal = equity.maxOf { it.balanceUsdt }
        val rawRange = maxVal - minVal
        val pad = if (rawRange <= 0.0) maxVal * 0.02 else rawRange * 0.1
        val dispMin = minVal - pad
        val dispMax = maxVal + pad
        val dispRange = (dispMax - dispMin).coerceAtLeast(0.001)

        val linePath = Path()
        val fillPath = Path()
        var firstPt: Offset? = null
        var lastPt: Offset? = null

        val stepX = w / (equity.size - 1).coerceAtLeast(1)

        for (i in equity.indices) {
          val ptX = i * stepX
          val normY = ((equity[i].balanceUsdt - dispMin) / dispRange).toFloat().coerceIn(0f, 1f)
          val ptY = (h - normY * h).coerceIn(2f, h - 2f)
          val pt = Offset(ptX, ptY)

          if (i == 0) {
            linePath.moveTo(pt.x, pt.y)
            firstPt = pt
          } else {
            linePath.lineTo(pt.x, pt.y)
          }
          lastPt = pt
        }

        if (firstPt != null && lastPt != null) {
          fillPath.addPath(linePath)
          fillPath.lineTo(lastPt.x, h)
          fillPath.lineTo(firstPt.x, h)
          fillPath.close()

          drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
              colors = listOf(HudCyan.copy(alpha = 0.35f), Color.Transparent),
              startY = 0f,
              endY = h
            )
          )

          drawPath(
            path = linePath,
            color = HudCyan,
            style = Stroke(width = 2.dp.toPx())
          )

          // Отрисовка мин/макс меток
          drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText("Max: %.2f".format(Locale.US, maxVal), 4f, 12f, textPaint)
            canvas.nativeCanvas.drawText("Min: %.2f".format(Locale.US, minVal), 4f, h - 3f, textPaint)
          }
        }
      }
    }
  }
}
