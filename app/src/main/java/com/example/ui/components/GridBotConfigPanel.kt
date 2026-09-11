package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.example.MyApplication
import com.example.model.*
import com.example.service.GridBotEngine
import com.example.ui.formatPrice
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridBotConfigPanel(
  gridBotEngine: GridBotEngine,
  currentPrice: Double,
  selectedSymbol: String,
  selectedInterval: String = "15m",
  modifier: Modifier = Modifier
) {
  val state by gridBotEngine.stateFlow.collectAsState()
  val config = state.config

  // Доступ к балансу через botEngine (тот же источник, что и DashboardScreen)
  val botEngine = remember { MyApplication.instance.botEngine }
  val botState by botEngine.stateFlow.collectAsState()
  val scope = rememberCoroutineScope()

  // Состояние модального окна настроек (ModalBottomSheet)
  var showSettingsSheet by remember { mutableStateOf(false) }

  // Диалоги подтверждения
  var showStartConfirmDialog by remember { mutableStateOf(false) }
  var showStopConfirmDialog by remember { mutableStateOf(false) }
  var showStopAndCloseConfirmDialog by remember { mutableStateOf(false) }
  var showLogsDialog by remember { mutableStateOf(false) }

  // Локальные параметры для диалога запуска
  var direction by remember(config.direction) { mutableStateOf(config.direction) }

  // Расчет отображаемого баланса с учетом ордеров сетки
  val rawFree = botState.freeUsdt ?: 0.0
  val rawLocked = botState.lockedUsdt ?: 0.0
  val totalUsdt = botState.totalUsdt ?: (rawFree + rawLocked)
  val gridReserved = state.reservedInOrdersUsdt
  val displayedFree = max(0.0, rawFree - gridReserved)
  val displayedLocked = rawLocked + gridReserved

  // Если границы еще не выставлены, рассчитаем их симметрично от текущей цены
  LaunchedEffect(currentPrice) {
    if (currentPrice > 0.0 && (config.lowerBound <= 0.0 || config.upperBound <= 0.0)) {
      val (low, up) = gridBotEngine.calculateBoundsFromPercent(config.rangePercent, currentPrice)
      gridBotEngine.updateConfig(
        config.copy(
          symbol = selectedSymbol,
          lowerBound = low,
          upperBound = up,
          tradingInterval = selectedInterval
        )
      )
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    // ==========================================
    // 1. КАРТОЧКА БАЛАНСА (testTag = "balance_card")
    // ==========================================
    HudCard(
      title = "БАЛАНС СЧЕТА (USDT)",
      icon = Icons.Outlined.AccountBalanceWallet,
      borderColor = HudCyan.copy(alpha = 0.7f),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("balance_card")
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            "ДОСТУПНО",
            color = HudTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
          )
          Text(
            "${"%.2f".format(Locale.US, displayedFree)} USDT",
            color = HudGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            "В ОРДЕРАХ",
            color = HudTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
          )
          Text(
            "${"%.2f".format(Locale.US, displayedLocked)} USDT",
            color = HudNeonPink,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            "ОБЩИЙ БАЛАНС",
            color = HudTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
          )
          Text(
            "${"%.2f".format(Locale.US, totalUsdt)} USDT",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace
          )
        }

        IconButton(
          onClick = { scope.launch { botEngine.refreshBalance() } },
          modifier = Modifier
            .size(34.dp)
            .background(Color(0xFF0C1E36), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
            .testTag("refresh_balance_button")
        ) {
          Icon(
            Icons.Outlined.Refresh,
            contentDescription = "Обновить баланс",
            tint = HudCyan,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    Spacer(Modifier.height(8.dp))

    // ==========================================
    // 2. КОМПАКТНАЯ ПАНЕЛЬ УПРАВЛЕНИЯ GRID BOT
    // ==========================================
    HudCard(
      title = if (state.isActive) "GRID BOT // АКТИВЕН" else "GRID BOT // ПАНЕЛЬ УПРАВЛЕНИЯ",
      icon = Icons.Outlined.GridOn,
      borderColor = if (state.isActive) HudGreen else HudNeonPink,
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Строка выбора направления + Кнопка настроек
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Направление LONG
          val isLong = direction == GridDirection.LONG
          val longBg = if (isLong) Brush.linearGradient(listOf(Color(0xFF003820), Color(0xFF005A32))) else Brush.linearGradient(listOf(Color(0xFF071220), Color(0xFF071220)))
          Box(
            modifier = Modifier
              .weight(1f)
              .background(longBg, RoundedCornerShape(6.dp))
              .border(1.5.dp, if (isLong) HudGreen else Color(0x3300D4FF), RoundedCornerShape(6.dp))
              .clickable(enabled = !state.isActive) {
                direction = GridDirection.LONG
                gridBotEngine.updateConfig(config.copy(direction = GridDirection.LONG))
              }
              .padding(vertical = 8.dp)
              .testTag("grid_direction_long"),
            contentAlignment = Alignment.Center
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = if (isLong) HudGreen else HudTextMuted, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("LONG", color = if (isLong) HudGreen else HudTextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
          }

          // Направление SHORT
          val isShort = direction == GridDirection.SHORT
          val shortBg = if (isShort) Brush.linearGradient(listOf(Color(0xFF380D1A), Color(0xFF5A142A))) else Brush.linearGradient(listOf(Color(0xFF071220), Color(0xFF071220)))
          Box(
            modifier = Modifier
              .weight(1f)
              .background(shortBg, RoundedCornerShape(6.dp))
              .border(1.5.dp, if (isShort) HudRed else Color(0x3300D4FF), RoundedCornerShape(6.dp))
              .clickable(enabled = !state.isActive) {
                direction = GridDirection.SHORT
                gridBotEngine.updateConfig(config.copy(direction = GridDirection.SHORT))
              }
              .padding(vertical = 8.dp)
              .testTag("grid_direction_short"),
            contentAlignment = Alignment.Center
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.TrendingDown, contentDescription = null, tint = if (isShort) HudRed else HudTextMuted, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("SHORT", color = if (isShort) HudRed else HudTextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
          }

          // Кнопка открытия всех настроек (Шестерёнка)
          Button(
            onClick = { showSettingsSheet = true },
            modifier = Modifier
              .height(38.dp)
              .testTag("grid_settings_button"),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF0C2442),
              contentColor = HudCyan
            ),
            contentPadding = PaddingValues(horizontal = 10.dp)
          ) {
            Icon(Icons.Outlined.Settings, contentDescription = "Настройки", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("НАСТРОЙКИ", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
          }
        }

        Spacer(Modifier.height(8.dp))

        // Кнопки запуска / остановки
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (!state.isActive) {
            // Кнопка ЗАПУСК СЕТКИ
            Button(
              onClick = { showStartConfirmDialog = true },
              modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("grid_start_button"),
              shape = RoundedCornerShape(6.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = HudGreen,
                contentColor = Color.Black
              )
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(
                "ЗАПУСК СЕТКИ (${config.levelCount} УРОВНЕЙ)",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          } else {
            // Кнопка СТОП СЕТКИ
            Button(
              onClick = { showStopConfirmDialog = true },
              modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("grid_stop_button"),
              shape = RoundedCornerShape(6.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFB300),
                contentColor = Color.Black
              )
            ) {
              Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text(
                "СТОП",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
              )
            }

            // Кнопка ЗАКРЫТЬ ВСЁ
            Button(
              onClick = { showStopAndCloseConfirmDialog = true },
              modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("grid_stop_and_close_button"),
              shape = RoundedCornerShape(6.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4A0E18),
                contentColor = HudRed
              )
            ) {
              Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
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
              .size(44.dp)
              .background(Color(0xFF0C1E36), RoundedCornerShape(6.dp))
              .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
              .testTag("grid_logs_button")
          ) {
            Icon(Icons.Outlined.ReceiptLong, contentDescription = "Логи сетки", tint = HudCyan, modifier = Modifier.size(20.dp))
          }
        }

        // Информационная плашка статуса (если бот активен)
        if (state.isActive) {
          Spacer(Modifier.height(8.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF061424), RoundedCornerShape(6.dp))
              .border(1.dp, HudGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
              .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Бейдж таймфрейма (Requirement 5)
            Box(
              modifier = Modifier
                .background(Color(0xFF003820), RoundedCornerShape(4.dp))
                .border(1.dp, HudGreen, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                "GRID BOT [ON · ${config.tradingInterval}]",
                color = HudGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
            }

            Text(
              "PnL: +${"%.2f".format(Locale.US, state.totalProfitUsdt)} USDT",
              color = HudCyan,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )

            Text(
              "Кругов: ${state.completedGrids}",
              color = Color.White,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )

            Text(
              "Ордеров: ${state.activeOrderCount}",
              color = HudTextMuted,
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        // Последняя запись лога
        val latestLog = state.logs.firstOrNull()
        if (latestLog != null) {
          Spacer(Modifier.height(6.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color(0xFF060B12), RoundedCornerShape(4.dp))
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("[${latestLog.formattedTime}] ", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
              latestLog.message,
              color = if (latestLog.isError) HudRed else if (latestLog.isHighlight) HudGreen else Color.White,
              fontSize = 9.5.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1
            )
          }
        }
      }
    }
  }

  // =========================================================================
  // 3. ВСЕ НАСТРОЙКИ СЕТКИ — В МОДАЛЬНОМ ОКНЕ (ModalBottomSheet) (Requirement 7)
  // =========================================================================
  if (showSettingsSheet) {
    ModalBottomSheet(
      onDismissRequest = { showSettingsSheet = false },
      containerColor = Color(0xFF071424),
      dragHandle = { BottomSheetDefaults.DragHandle(color = HudCyan) }
    ) {
      GridBotSettingsContent(
        config = config,
        currentPrice = currentPrice,
        selectedSymbol = selectedSymbol,
        selectedInterval = selectedInterval,
        isActive = state.isActive,
        onApply = { newConfig ->
          gridBotEngine.updateConfig(newConfig)
          showSettingsSheet = false
        },
        onClose = { showSettingsSheet = false }
      )
    }
  }

  // ==========================================
  // 4. ДИАЛОГИ ПОДТВЕРЖДЕНИЯ
  // ==========================================

  // Диалог подтверждения активации
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
          Text("Вы подтверждаете запуск сеточной торговли на спотовом рынке?", color = Color.White, fontSize = 12.sp)
          Spacer(Modifier.height(10.dp))
          Text("• Инструмент: $selectedSymbol", color = HudCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Таймфрейм: $selectedInterval (зафиксирован)", color = HudNeonPurple, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Направление: ${direction.name}", color = if (direction == GridDirection.LONG) HudGreen else HudRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Капитал сетки: ${"%.2f".format(Locale.US, config.totalInvestmentUsdt)} USDT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
          Text("• Уровней: ${config.levelCount} (от 3 до 50)", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          val buyRatio = config.buySellRatio
          val buyCount = (config.levelCount * buyRatio).roundToInt().coerceIn(1, config.levelCount - 1)
          val sellCount = config.levelCount - buyCount
          Text("• Соотношение: $buyCount BUY / $sellCount SELL", color = HudGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Диапазон: [${formatPrice(config.lowerBound)} - ${formatPrice(config.upperBound)}]", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
          Text("• Трейлинг: ${if (config.trailingTriggerPercent > 0.0) "ВКЛ (+${config.trailingTriggerPercent}%)" else "ВЫКЛ"}", color = HudNeonPurple, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
                tradingInterval = selectedInterval
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

  // Диалог остановки сетки
  if (showStopConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showStopConfirmDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Text("ОСТАНОВИТЬ СЕТКУ?", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      },
      text = {
        Text(
          "Все активные лимитные ордера сетки будут отменены. Купленный на исполненных уровнях актив останется на вашем балансе.",
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

  // Диалог остановки и закрытия по рынку
  if (showStopAndCloseConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showStopAndCloseConfirmDialog = false },
      containerColor = Color(0xFF0D2340),
      title = {
        Text("ОСТАНОВИТЬ И ЗАКРЫТЬ ПО РЫНКУ?", color = HudRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      },
      text = {
        Text(
          "Все ордера будут отменены, а накопленный объём актива будет продан по рыночной цене (MARKET SELL).",
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

  // Модальное окно журнала действий Grid Bot
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
                  Text("[${log.formattedTime}] ", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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

/**
 * Внутреннее содержимое модального окна настроек (ModalBottomSheet).
 * Содержит все параметры: диапазон, капитал, ползунок уровней (3..50), ползунок BUY/SELL, трейлинг.
 */
@Composable
private fun GridBotSettingsContent(
  config: GridConfig,
  currentPrice: Double,
  selectedSymbol: String,
  selectedInterval: String,
  isActive: Boolean,
  onApply: (GridConfig) -> Unit,
  onClose: () -> Unit
) {
  var totalInvestmentText by remember { mutableStateOf(config.totalInvestmentUsdt.toString()) }
  var lowerBoundText by remember { mutableStateOf(if (config.lowerBound > 0.0) config.lowerBound.toString() else "") }
  var upperBoundText by remember { mutableStateOf(if (config.upperBound > 0.0) config.upperBound.toString() else "") }
  var levelCount by remember { mutableFloatStateOf(config.levelCount.toFloat()) }
  var buySellRatio by remember { mutableFloatStateOf(config.buySellRatio) }
  var isTrailingEnabled by remember { mutableStateOf(config.trailingTriggerPercent > 0.0) }
  var trailingTriggerText by remember { mutableStateOf(config.trailingTriggerPercent.toString()) }
  var trailingOffsetText by remember { mutableStateOf(config.trailingOffsetPercent.toString()) }

  val scrollState = rememberScrollState()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .padding(bottom = 24.dp)
      .verticalScroll(scrollState)
  ) {
    // Заголовок модального окна
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = HudCyan)
        Spacer(Modifier.width(8.dp))
        Text(
          "ПАРАМЕТРЫ GRID BOT // $selectedSymbol",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          fontFamily = FontFamily.Monospace
        )
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = HudTextMuted)
      }
    }

    HorizontalDivider(color = Color(0x3300D4FF), modifier = Modifier.padding(vertical = 8.dp))

    // 1. ДЕПОЗИТ (USDT)
    Text(
      "КАПИТАЛ СЕТКИ (USDT):",
      color = HudTextMuted,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
      value = totalInvestmentText,
      onValueChange = { totalInvestmentText = it },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("grid_total_investment_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = HudCyan,
        unfocusedBorderColor = Color(0x3300D4FF),
        focusedContainerColor = Color(0xFF071220),
        unfocusedContainerColor = Color(0xFF071220)
      )
    )

    Spacer(Modifier.height(14.dp))

    // 2. ДИАПАЗОН ЦЕН (СИММЕТРИЧНЫЕ БЫСТРЫЕ КНОПКИ) (Requirement 4)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "ДИАПАЗОН ЦЕН [СИММЕТРИЧНО ОТ $${formatPrice(currentPrice)}]:",
        color = HudTextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
      )
    }

    Spacer(Modifier.height(6.dp))

    // Быстрые кнопки процентов симметрично от текущей цены
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      listOf(2.0, 4.0, 8.0, 15.0).forEach { pct ->
        Box(
          modifier = Modifier
            .weight(1f)
            .background(Color(0xFF0A1C30), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0x4400D4FF), RoundedCornerShape(4.dp))
            .clickable {
              if (currentPrice > 0.0) {
                val low = currentPrice * (1.0 - pct / 100.0)
                val up = currentPrice * (1.0 + pct / 100.0)
                lowerBoundText = "%.4f".format(Locale.US, low)
                upperBoundText = "%.4f".format(Locale.US, up)
              }
            }
            .padding(vertical = 6.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            "±$pct%",
            color = HudCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
        }
      }
    }

    Spacer(Modifier.height(8.dp))

    // Поля ввода границ
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("НИЗ СЕТКИ (BUY)", color = HudGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
          value = lowerBoundText,
          onValueChange = { lowerBoundText = it },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("grid_lower_bound_input"),
          colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = HudGreen,
            unfocusedBorderColor = Color(0x3300D4FF),
            focusedContainerColor = Color(0xFF071220),
            unfocusedContainerColor = Color(0xFF071220)
          )
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text("ВЕРХ СЕТКИ (SELL)", color = HudNeonPink, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
          value = upperBoundText,
          onValueChange = { upperBoundText = it },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("grid_upper_bound_input"),
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

    Spacer(Modifier.height(16.dp))

    // 3. КОЛИЧЕСТВО УРОВНЕЙ: ОТ 3 ДО 50 (Requirement 8)
    val curLevels = levelCount.roundToInt().coerceIn(3, 50)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "КОЛИЧЕСТВО УРОВНЕЙ:",
        color = HudTextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
      )
      Text(
        "$curLevels УРОВНЕЙ",
        color = HudCyan,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace
      )
    }

    Slider(
      value = levelCount,
      onValueChange = { levelCount = it },
      valueRange = 3f..50f,
      steps = 46,
      colors = SliderDefaults.colors(
        thumbColor = HudCyan,
        activeTrackColor = HudCyan,
        inactiveTrackColor = Color(0xFF0B2036)
      ),
      modifier = Modifier.fillMaxWidth().testTag("grid_levels_slider")
    )

    Spacer(Modifier.height(12.dp))

    // 4. ПОЛЗУНОК СООТНОШЕНИЯ BUY / SELL УРОВНЕЙ (Requirement 4)
    val buyCount = (curLevels * buySellRatio).roundToInt().coerceIn(1, curLevels - 1)
    val sellCount = (curLevels - buyCount).coerceAtLeast(1)
    val ratioPercentBuy = ((buyCount.toDouble() / curLevels) * 100).roundToInt()
    val ratioPercentSell = 100 - ratioPercentBuy

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "БАЛАНС УРОВНЕЙ BUY / SELL:",
        color = HudTextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
      )
      Text(
        "BUY: $buyCount ($ratioPercentBuy%) | SELL: $sellCount ($ratioPercentSell%)",
        color = if (buySellRatio < 0.48f) HudNeonPink else if (buySellRatio > 0.52f) HudGreen else Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
      )
    }

    Slider(
      value = buySellRatio,
      onValueChange = { buySellRatio = it },
      valueRange = 0.10f..0.90f,
      colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = HudGreen,
        inactiveTrackColor = HudNeonPink
      ),
      modifier = Modifier.fillMaxWidth().testTag("grid_buy_sell_ratio_slider")
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text("◄ Больше SELL (сверху)", color = HudNeonPink.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
      Text("50/50", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
      Text("Больше BUY (снизу) ►", color = HudGreen.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }

    Spacer(Modifier.height(16.dp))

    // 5. ТРЕЙЛИНГ СЕТКИ (ВКЛ / ВЫКЛ)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text("ТРЕЙЛИНГ СЕТКИ ВВЕРХ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Авто-сдвиг диапазона за пиком цены", color = HudTextMuted, fontSize = 10.sp)
      }
      Switch(
        checked = isTrailingEnabled,
        onCheckedChange = { isTrailingEnabled = it },
        colors = SwitchDefaults.colors(
          checkedThumbColor = HudNeonPurple,
          checkedTrackColor = Color(0xFF380D45),
          uncheckedTrackColor = Color(0xFF071220)
        )
      )
    }

    if (isTrailingEnabled) {
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Триггер сдвига (%)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          OutlinedTextField(
            value = trailingTriggerText,
            onValueChange = { trailingTriggerText = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = HudNeonPurple,
              unfocusedBorderColor = Color(0x3300D4FF),
              focusedContainerColor = Color(0xFF071220),
              unfocusedContainerColor = Color(0xFF071220)
            )
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text("Отступ от пика (%)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          OutlinedTextField(
            value = trailingOffsetText,
            onValueChange = { trailingOffsetText = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = HudNeonPurple,
              unfocusedBorderColor = Color(0x3300D4FF),
              focusedContainerColor = Color(0xFF071220),
              unfocusedContainerColor = Color(0xFF071220)
            )
          )
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    // 6. ТАЙМФРЕЙМ ТОРГОВЛИ (Requirement 5)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF05101E), RoundedCornerShape(6.dp))
        .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = HudCyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
          "ТАЙМФРЕЙМ ТОРГОВЛИ:",
          color = HudTextMuted,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace
        )
      }
      Text(
        selectedInterval,
        color = HudCyan,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace
      )
    }

    Spacer(Modifier.height(20.dp))

    // 7. КНОПКА ПРИМЕНИТЬ НАСТРОЙКИ
    Button(
      onClick = {
        val parsedInvestment = totalInvestmentText.toDoubleOrNull() ?: 100.0
        val parsedLower = lowerBoundText.toDoubleOrNull() ?: (currentPrice * 0.96)
        val parsedUpper = upperBoundText.toDoubleOrNull() ?: (currentPrice * 1.04)
        val parsedLevels = levelCount.roundToInt().coerceIn(3, 50)
        val trig = if (isTrailingEnabled) (trailingTriggerText.toDoubleOrNull() ?: 3.0) else 0.0
        val offset = trailingOffsetText.toDoubleOrNull() ?: 6.0

        onApply(
          config.copy(
            totalInvestmentUsdt = parsedInvestment,
            lowerBound = parsedLower,
            upperBound = parsedUpper,
            levelCount = parsedLevels,
            buySellRatio = buySellRatio,
            trailingTriggerPercent = trig,
            trailingOffsetPercent = offset,
            tradingInterval = selectedInterval
          )
        )
      },
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("grid_apply_settings_button"),
      shape = RoundedCornerShape(8.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = HudCyan,
        contentColor = Color.Black
      )
    ) {
      Icon(Icons.Filled.Check, contentDescription = null)
      Spacer(Modifier.width(6.dp))
      Text(
        "ПРИМЕНИТЬ НАСТРОЙКИ",
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}
