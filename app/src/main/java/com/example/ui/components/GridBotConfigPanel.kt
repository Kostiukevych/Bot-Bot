package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.service.GridBotEngine
import com.example.ui.formatPrice
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GridBotConfigPanel(
  gridBotEngine: GridBotEngine,
  currentPrice: Double,
  selectedSymbol: String,
  modifier: Modifier = Modifier
) {
  val state by gridBotEngine.stateFlow.collectAsState()
  val config = state.config

  var direction by remember(config.direction) { mutableStateOf(config.direction) }
  var rangePercentText by remember { mutableStateOf("5.0") }
  var levelCount by remember { mutableFloatStateOf(config.levelCount.toFloat()) }
  var isAutoCapital by remember { mutableStateOf(config.isAutoCapitalPerLevel) }
  var capitalPercentText by remember { mutableStateOf("12.5") }
  var totalInvestmentText by remember { mutableStateOf("100.0") }
  var trailingTriggerText by remember { mutableStateOf("3.0") }
  var trailingOffsetText by remember { mutableStateOf("6.0") }

  var showStartConfirmDialog by remember { mutableStateOf(false) }
  var showStopConfirmDialog by remember { mutableStateOf(false) }
  var showStopAndCloseConfirmDialog by remember { mutableStateOf(false) }
  var showLogsDialog by remember { mutableStateOf(false) }

  // Расчет границ от процента, если они не выставлены вручную
  val parsedRange = rangePercentText.toDoubleOrNull() ?: 5.0
  val calculatedBounds = remember(currentPrice, parsedRange) {
    gridBotEngine.calculateBoundsFromPercent(parsedRange, currentPrice)
  }

  val activeLowerBound = if (config.lowerBound > 0.0) config.lowerBound else calculatedBounds.first
  val activeUpperBound = if (config.upperBound > 0.0) config.upperBound else calculatedBounds.second

  // Валидация капитала
  val parsedInvestment = totalInvestmentText.toDoubleOrNull() ?: 100.0
  val parsedCapitalPercent = capitalPercentText.toDoubleOrNull() ?: (100.0 / levelCount.roundToInt())
  val totalPercentAllocated = if (isAutoCapital) 100.0 else parsedCapitalPercent * levelCount.roundToInt()
  val isCapitalValid = totalPercentAllocated <= 100.01

  HudCard(
    title = if (state.isActive) "GRID BOT // АКТИВЕН [${direction.name}]" else "GRID BOT // НАСТРОЙКА СЕТКИ",
    icon = Icons.Outlined.GridOn,
    borderColor = if (state.isActive) HudGreen else HudNeonPink,
    modifier = modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // 1. НАПРАВЛЕНИЕ СЕТКИ: LONG / SHORT (Взаимоисключающие, неоновая подсветка)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Кнопка LONG
        val isLong = direction == GridDirection.LONG
        val longBg = if (isLong) Brush.linearGradient(listOf(Color(0xFF003820), Color(0xFF005A32))) else Brush.linearGradient(listOf(Color(0xFF071220), Color(0xFF071220)))
        val longBorder = if (isLong) HudGreen else Color(0x3300D4FF)

        Box(
          modifier = Modifier
            .weight(1f)
            .background(longBg, RoundedCornerShape(6.dp))
            .border(1.5.dp, longBorder, RoundedCornerShape(6.dp))
            .clickable(enabled = !state.isActive) {
              direction = GridDirection.LONG
              gridBotEngine.updateConfig(config.copy(direction = GridDirection.LONG))
            }
            .padding(vertical = 8.dp)
            .testTag("grid_direction_long"),
          contentAlignment = Alignment.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Outlined.TrendingUp,
              contentDescription = null,
              tint = if (isLong) HudGreen else HudTextMuted,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
              "LONG (ПОКУПКА СНИЗУ)",
              color = if (isLong) HudGreen else HudTextMuted,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        // Кнопка SHORT
        val isShort = direction == GridDirection.SHORT
        val shortBg = if (isShort) Brush.linearGradient(listOf(Color(0xFF380D1A), Color(0xFF5A142A))) else Brush.linearGradient(listOf(Color(0xFF071220), Color(0xFF071220)))
        val shortBorder = if (isShort) HudRed else Color(0x3300D4FF)

        Box(
          modifier = Modifier
            .weight(1f)
            .background(shortBg, RoundedCornerShape(6.dp))
            .border(1.5.dp, shortBorder, RoundedCornerShape(6.dp))
            .clickable(enabled = !state.isActive) {
              direction = GridDirection.SHORT
              gridBotEngine.updateConfig(config.copy(direction = GridDirection.SHORT))
            }
            .padding(vertical = 8.dp)
            .testTag("grid_direction_short"),
          contentAlignment = Alignment.Center
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Outlined.TrendingDown,
              contentDescription = null,
              tint = if (isShort) HudRed else HudTextMuted,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
              "SHORT (ПРОДАЖА СВЕРХУ)",
              color = if (isShort) HudRed else HudTextMuted,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      // ТЕКУЩИЕ ГРАНИЦЫ СЕТКИ (ИНФОРМАЦИОННАЯ ПАНЕЛЬ С ПОДСКАЗКОЙ О DRAG)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF05101E), RoundedCornerShape(6.dp))
          .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
          .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("НИЖНЯЯ ГРАНИЦА (BUY)", color = HudGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
          Text(formatPrice(activeLowerBound), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text("ШАГ СЕТКИ", color = HudCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
          val step = if (levelCount.roundToInt() > 1) (activeUpperBound - activeLowerBound) / (levelCount.roundToInt() - 1) else 0.0
          Text("+${formatPrice(step)}", color = HudCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Column(horizontalAlignment = Alignment.End) {
          Text("ВЕРХНЯЯ ГРАНИЦА (SELL)", color = HudNeonPink, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
          Text(formatPrice(activeUpperBound), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
      }

      Spacer(Modifier.height(8.dp))

      // 2. ДИАПАЗОН СЕТКИ, % + ИНВЕСТИЦИИ USDT
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = rangePercentText,
          onValueChange = {
            rangePercentText = it
            val p = it.toDoubleOrNull() ?: 5.0
            val bounds = gridBotEngine.calculateBoundsFromPercent(p, currentPrice)
            gridBotEngine.updateConfig(config.copy(rangePercent = p, lowerBound = bounds.first, upperBound = bounds.second))
          },
          label = { Text("Диапазон сетки, %", fontSize = 10.sp, color = HudTextMuted) },
          enabled = !state.isActive,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f).testTag("grid_range_percent"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = HudNeonPink,
            unfocusedBorderColor = Color(0x3300D4FF),
            focusedContainerColor = Color(0xFF071220),
            unfocusedContainerColor = Color(0xFF071220)
          )
        )

        OutlinedTextField(
          value = totalInvestmentText,
          onValueChange = {
            totalInvestmentText = it
            val inv = it.toDoubleOrNull() ?: 100.0
            gridBotEngine.updateConfig(config.copy(totalInvestmentUsdt = inv))
          },
          label = { Text("Капитал, USDT", fontSize = 10.sp, color = HudTextMuted) },
          enabled = !state.isActive,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f).testTag("grid_total_investment"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = HudNeonPink,
            unfocusedBorderColor = Color(0x3300D4FF),
            focusedContainerColor = Color(0xFF071220),
            unfocusedContainerColor = Color(0xFF071220)
          )
        )
      }

      Spacer(Modifier.height(10.dp))

      // 3. СЛАЙДЕР "КОЛИЧЕСТВО УРОВНЕЙ СЕТКИ" (от 3 до 20, по умолчанию 8)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "УРОВНЕЙ СЕТКИ: ${levelCount.roundToInt()}",
          color = Color.White,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace
        )
        Text(
          "Капитал/уровень: ${"%.2f".format(Locale.US, parsedInvestment / levelCount.roundToInt())} USDT",
          color = HudCyan,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }

      Slider(
        value = levelCount,
        onValueChange = {
          levelCount = it
          val n = it.roundToInt()
          gridBotEngine.updateConfig(config.copy(levelCount = n, capitalPercentPerLevel = 100.0 / n))
          if (isAutoCapital) {
            capitalPercentText = "%.1f".format(Locale.US, 100.0 / n)
          }
        },
        valueRange = 3f..20f,
        steps = 16,
        enabled = !state.isActive,
        colors = SliderDefaults.colors(
          thumbColor = HudNeonPink,
          activeTrackColor = HudNeonPink,
          inactiveTrackColor = Color(0x3300D4FF)
        ),
        modifier = Modifier.fillMaxWidth().testTag("grid_level_slider")
      )

      Spacer(Modifier.height(6.dp))

      // 4. % КАПИТАЛА НА УРОВЕНЬ
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = isAutoCapital,
            onCheckedChange = {
              isAutoCapital = it
              gridBotEngine.updateConfig(config.copy(isAutoCapitalPerLevel = it))
              if (it) {
                capitalPercentText = "%.1f".format(Locale.US, 100.0 / levelCount.roundToInt())
              }
            },
            enabled = !state.isActive,
            colors = CheckboxDefaults.colors(checkedColor = HudCyan)
          )
          Text(
            "Равномерно (100% / N)",
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        if (!isAutoCapital) {
          OutlinedTextField(
            value = capitalPercentText,
            onValueChange = {
              capitalPercentText = it
              val pct = it.toDoubleOrNull() ?: (100.0 / levelCount.roundToInt())
              gridBotEngine.updateConfig(config.copy(capitalPercentPerLevel = pct))
            },
            label = { Text("% на уровень", fontSize = 9.sp) },
            enabled = !state.isActive,
            singleLine = true,
            isError = !isCapitalValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(130.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = HudNeonPink,
              unfocusedBorderColor = Color(0x3300D4FF),
              focusedContainerColor = Color(0xFF071220),
              unfocusedContainerColor = Color(0xFF071220)
            )
          )
        }
      }

      if (!isCapitalValid) {
        Text(
          "Внимание: сумма долей (${"%.1f".format(Locale.US, totalPercentAllocated)}%) превышает 100% доступного баланса!",
          color = HudRed,
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace
        )
      }

      Spacer(Modifier.height(10.dp))

      // 5 & 6. НАСТРОЙКИ ТРЕЙЛИНГА (ТРИГГЕР % И ОТСТУП ОТ ПИКА %)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = trailingTriggerText,
          onValueChange = {
            trailingTriggerText = it
            val trig = it.toDoubleOrNull() ?: 3.0
            gridBotEngine.updateConfig(config.copy(trailingTriggerPercent = trig))
          },
          label = { Text("Триггер трейлинга, %", fontSize = 10.sp, color = HudTextMuted) },
          enabled = !state.isActive,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f).testTag("grid_trailing_trigger"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = HudCyan,
            unfocusedBorderColor = Color(0x3300D4FF),
            focusedContainerColor = Color(0xFF071220),
            unfocusedContainerColor = Color(0xFF071220)
          )
        )

        OutlinedTextField(
          value = trailingOffsetText,
          onValueChange = {
            trailingOffsetText = it
            val off = it.toDoubleOrNull() ?: 6.0
            gridBotEngine.updateConfig(config.copy(trailingOffsetPercent = off))
          },
          label = { Text("Отступ от пика, %", fontSize = 10.sp, color = HudTextMuted) },
          enabled = !state.isActive,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f).testTag("grid_trailing_offset"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = HudCyan,
            unfocusedBorderColor = Color(0x3300D4FF),
            focusedContainerColor = Color(0xFF071220),
            unfocusedContainerColor = Color(0xFF071220)
          )
        )
      }

      Spacer(Modifier.height(12.dp))

      // ПАНЕЛЬ СТАТИСТИКИ АКТИВНОЙ СЕТКИ (ЕСЛИ ЗАПУЩЕНА)
      if (state.isActive) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF071E14), RoundedCornerShape(6.dp))
            .border(1.dp, HudGreen.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text("ПРОФИТ СЕТКИ", color = HudGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
              "+${"%.2f".format(Locale.US, state.totalProfitUsdt)} USDT",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              fontFamily = FontFamily.Monospace
            )
          }

          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ПЕРЕКУПОК", color = HudCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
              "${state.completedGrids} циклов",
              color = HudCyan,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace
            )
          }

          Column(horizontalAlignment = Alignment.End) {
            Text("PEAK PRICE", color = HudNeonPurple, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
              formatPrice(state.peakPrice),
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        Spacer(Modifier.height(10.dp))
      }

      // КНОПКИ УПРАВЛЕНИЯ (АКТИВИРОВАТЬ / ОСТАНОВИТЬ / ОСТАНОВИТЬ И ЗАКРЫТЬ ВСЁ / ЛОГИ)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (!state.isActive) {
          Button(
            onClick = { showStartConfirmDialog = true },
            enabled = isCapitalValid && currentPrice > 0.0,
            modifier = Modifier.weight(1f).height(46.dp).testTag("grid_start_button"),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = HudGreen,
              contentColor = Color.Black
            )
          ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
              "АКТИВИРОВАТЬ СЕТКУ",
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        } else {
          // Кнопка: Остановить сетку (отмена ордеров, купленный актив остаётся)
          Button(
            onClick = { showStopConfirmDialog = true },
            modifier = Modifier.weight(1f).height(46.dp).testTag("grid_stop_button"),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF332000),
              contentColor = Color(0xFFFFB300)
            )
          ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
              "ОСТАНОВИТЬ",
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }

          // Кнопка: Остановить и закрыть всё
          Button(
            onClick = { showStopAndCloseConfirmDialog = true },
            modifier = Modifier.weight(1f).height(46.dp).testTag("grid_stop_and_close_button"),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF4A0E18),
              contentColor = HudRed
            )
          ) {
            Icon(Icons.Filled.Close, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
              "ЗАКРЫТЬ ВСЁ",
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        // Кнопка логов
        IconButton(
          onClick = { showLogsDialog = true },
          modifier = Modifier
            .size(46.dp)
            .background(Color(0xFF0C1E36), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
            .testTag("grid_logs_button")
        ) {
          Icon(Icons.Outlined.ReceiptLong, contentDescription = "Логи сетки", tint = HudCyan)
        }
      }

      // ПОСЛЕДНЯЯ ЗАПИСЬ ЛОГА (БЫСТРЫЙ ПРОСМОТР ПРЯМО НА ПАНЕЛИ)
      val latestLog = state.logs.firstOrNull()
      if (latestLog != null) {
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF060B12), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            "[${latestLog.formattedTime}] ",
            color = HudTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
          )
          Text(
            latestLog.message,
            color = if (latestLog.isError) HudRed else if (latestLog.isHighlight) HudGreen else Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
          )
        }
      }
    }
  }

  // ==========================================
  // ДИАЛОГИ ПОДТВЕРЖДЕНИЯ
  // ==========================================

  // 1. Диалог подтверждения активации сетки
  if (showStartConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showStartConfirmDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.GridOn, contentDescription = null, tint = HudGreen)
          Spacer(Modifier.width(8.dp))
          Text(
            "АКТИВАЦИЯ GRID BOT",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text("Вы подтверждаете запуск сеточной торговли на Binance Spot Testnet?", color = Color.White, fontSize = 12.sp)
          Spacer(Modifier.height(10.dp))
          Text("• Инструмент: $selectedSymbol", color = HudCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Направление: ${direction.name}", color = if (direction == GridDirection.LONG) HudGreen else HudRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Капитал сетки: ${"%.2f".format(Locale.US, parsedInvestment)} USDT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
          Text("• Уровней: ${levelCount.roundToInt()} (по ${"%.2f".format(Locale.US, parsedInvestment / levelCount.roundToInt())} USDT/ур.)", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Диапазон: [${formatPrice(activeLowerBound)} - ${formatPrice(activeUpperBound)}]", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Трейлинг: триггер +${trailingTriggerText}%, отступ от пика -${trailingOffsetText}%", color = HudNeonPurple, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showStartConfirmDialog = false
            gridBotEngine.startGrid(
              config.copy(
                symbol = selectedSymbol,
                direction = direction,
                rangePercent = parsedRange,
                lowerBound = activeLowerBound,
                upperBound = activeUpperBound,
                levelCount = levelCount.roundToInt(),
                isAutoCapitalPerLevel = isAutoCapital,
                capitalPercentPerLevel = parsedCapitalPercent,
                totalInvestmentUsdt = parsedInvestment,
                trailingTriggerPercent = trailingTriggerText.toDoubleOrNull() ?: 3.0,
                trailingOffsetPercent = trailingOffsetText.toDoubleOrNull() ?: 6.0
              )
            )
          },
          colors = ButtonDefaults.buttonColors(containerColor = HudGreen, contentColor = Color.Black)
        ) {
          Text("ПОДТВЕРДИТЬ И ЗАПУСТИТЬ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showStartConfirmDialog = false }) {
          Text("ОТМЕНА", color = HudTextMuted, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }

  // 2. Диалог подтверждения остановки сетки
  if (showStopConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showStopConfirmDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Text("ОСТАНОВИТЬ СЕТКУ?", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      },
      text = {
        Text(
          "Все активные лимитные ордера сетки будут отменены (DELETE /api/v3/order). Купленные на исполненных уровнях активы останутся на вашем спотовом балансе без принудительной продажи.",
          color = Color.White,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showStopConfirmDialog = false
            gridBotEngine.stopGrid(marketClose = false)
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black)
        ) {
          Text("ОСТАНОВИТЬ СЕТКУ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showStopConfirmDialog = false }) {
          Text("НАЗАД", color = HudTextMuted, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }

  // 3. Диалог подтверждения остановки и закрытия по рынку
  if (showStopAndCloseConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showStopAndCloseConfirmDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Text("ОСТАНОВИТЬ И ЗАКРЫТЬ ПО РЫНКУ?", color = HudRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      },
      text = {
        Text(
          "Все неисполненные ордера будут отменены, а весь объём актива, накопленный на исполненных уровнях сетки, будет мгновенно продан по рыночной цене (MARKET SELL).",
          color = Color.White,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showStopAndCloseConfirmDialog = false
            gridBotEngine.stopGrid(marketClose = true)
          },
          colors = ButtonDefaults.buttonColors(containerColor = HudRed, contentColor = Color.White)
        ) {
          Text("ЗАКРЫТЬ ВСЁ ПО РЫНКУ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showStopAndCloseConfirmDialog = false }) {
          Text("ОТМЕНА", color = HudTextMuted, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }

  // 4. Модальное окно журнала действий Grid Bot
  if (showLogsDialog) {
    AlertDialog(
      onDismissRequest = { showLogsDialog = false },
      containerColor = Color(0xFF08162A),
      title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = HudCyan)
          Spacer(Modifier.width(8.dp))
          Text(
            "ЖУРНАЛ ДЕЙСТВИЙ GRID BOT",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text("Фиксация исполнения уровней, трейлинга и ордеров:", color = HudTextMuted, fontSize = 10.sp)
          Spacer(Modifier.height(8.dp))
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .height(300.dp)
          ) {
            if (state.logs.isEmpty()) {
              item {
                Text("Журнал пуст. Запустите сетку для начала фиксации.", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
              }
            } else {
              items(state.logs) { log ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                  verticalAlignment = Alignment.Top
                ) {
                  Text(
                    "[${log.formattedTime}] ",
                    color = HudTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                  )
                  Text(
                    log.message,
                    color = if (log.isError) HudRed else if (log.isHighlight) HudGreen else Color.White,
                    fontSize = 10.sp,
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
        TextButton(onClick = { showLogsDialog = false }) {
          Text("ЗАКРЫТЬ", color = HudCyan, fontFamily = FontFamily.Monospace)
        }
      }
    )
  }
}
