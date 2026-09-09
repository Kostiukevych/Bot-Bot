package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import com.example.MyApplication
import com.example.model.*
import com.example.service.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
  modifier: Modifier = Modifier,
  onOpenSettings: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  onOpenChart: () -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val app = context.applicationContext as MyApplication
  val botEngine = app.botEngine

  // Подписка на централизованный StateFlow движка бота
  val botState by botEngine.stateFlow.collectAsState()

  // Локальные состояния только для UI-инпутов и модальных окон
  var pairs by remember { mutableStateOf<List<TradingPair>>(emptyList()) }
  var pairsError by remember { mutableStateOf<String?>(null) }
  var showPairDialog by remember { mutableStateOf(false) }
  var showEmergencyDialog by remember { mutableStateOf(false) }
  var showBatteryOptDialog by remember { mutableStateOf(false) }

  // Открытые лимитные ордера на бирже (загружаются отдельно через marketService)
  var openOrders by remember { mutableStateOf<List<OpenOrder>>(emptyList()) }
  var ordersError by remember { mutableStateOf<String?>(null) }

  // Проверка оптимизации батареи
  var isBatteryOptimizationIgnored by remember {
    mutableStateOf(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
      } else {
        true
      }
    )
  }

  // Загрузка пар и открытых ордеров
  LaunchedEffect(Unit) {
    try {
      val pList = botEngine.marketService.getTradingPairs()
      pairs = pList
    } catch (e: Exception) {
      pairsError = e.message ?: "Сбой загрузки пар"
    }

    val creds = botEngine.storageService.getCredentials()
    if (creds != null && creds.apiKey.isNotEmpty()) {
      try {
        openOrders = botEngine.marketService.getOpenOrders(creds.apiKey, creds.secretKey)
      } catch (e: Exception) {
        ordersError = e.message
      }
    }
  }

  // Тактильная отдача (вибрация) при сделках (если включена пользователем)
  val flashEvent = botState.lastSuccessEvent
  LaunchedEffect(flashEvent?.id) {
    if (flashEvent != null && flashEvent.type != TradeFlashType.NONE) {
      if (botEngine.storageService.isVibrationEnabled()) {
        try {
          val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
          } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
          }
          if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              // Двойной тактильный импульс: 70мс вибро, 60мс пауза, 120мс вибро
              val timings = longArrayOf(0, 70, 60, 120)
              val amplitudes = intArrayOf(0, 200, 0, 255)
              vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
              @Suppress("DEPRECATION")
              vibrator.vibrate(longArrayOf(0, 70, 60, 120), -1)
            }
          }
        } catch (_: Exception) {}
      }
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
    containerColor = HudNavyDark,
    snackbarHost = { SnackbarHost(snackbarHostState) }
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
            color = Color(0x0A00D4FF),
            start = Offset(x * step, 0f),
            end = Offset(x * step, size.height),
            strokeWidth = 1f
          )
        }
        for (y in 0..(size.height / step).toInt()) {
          drawLine(
            color = Color(0x0A00D4FF),
            start = Offset(0f, y * step),
            end = Offset(size.width, y * step),
            strokeWidth = 1f
          )
        }
      }

      Column(modifier = Modifier.fillMaxSize()) {
        // TOP HUD BAR
        DashboardTopBar(
          onOpenSettings = onOpenSettings,
          onOpenHistory = onOpenHistory,
          onOpenChart = onOpenChart
        )

        // Предупреждение об оптимизации батареи (если не отключена)
        if (!isBatteryOptimizationIgnored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 4.dp)
              .background(Color(0x33FFB300), RoundedCornerShape(6.dp))
              .border(1.dp, Color(0xFFFFB300), RoundedCornerShape(6.dp))
              .clickable {
                try {
                  val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                  }
                  context.startActivity(intent)
                } catch (_: Exception) {
                  val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                  context.startActivity(intent)
                }
              }
              .padding(horizontal = 10.dp, vertical = 6.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.BatteryAlert, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(
                "Фоновая работа: нажмите, чтобы отключить оптимизацию батареи для непрерывной торговли",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp
              )
            }
          }
        }

        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          // 1. КАРТОЧКА ОБЩЕГО БАЛАНСА С PNL ЗА СЕССИЮ (ЧАСТЬ B п.3)
          item {
            val sessionPnl = botState.sessionRealizedPnlUsdt
            val pnlSign = if (sessionPnl >= 0) "+" else ""
            val pnlColor = if (sessionPnl >= 0) HudGreen else HudRed

            HudCard(
              title = "ДЕПОЗИТ SPOT TESTNET",
              icon = Icons.Outlined.AccountBalanceWallet,
              modifier = Modifier.fillMaxWidth().testTag("balance_card")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
              ) {
                Column {
                  val totalText = botState.totalUsdt?.let { "%.2f USDT".format(Locale.US, it) } ?: "ЗАГРУЗКА..."
                  Text(
                    totalText,
                    color = HudPeach,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                  )
                  Text("ОБЩИЙ ДЕПОЗИТ", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                IconButton(
                  onClick = {
                    scope.launch {
                      botEngine.refreshBalance()
                      snackbarHostState.showSnackbar("Баланс обновлен")
                    }
                  },
                  modifier = Modifier.size(32.dp)
                ) {
                  Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = HudCyan, modifier = Modifier.size(18.dp))
                }
              }

              Spacer(Modifier.height(10.dp))

              // Строка "PnL за сессию"
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                  .border(1.dp, pnlColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                  .padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text("PnL ЗА СЕССИЮ БОТА:", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  Text(
                    "${pnlSign}${"%.2f".format(Locale.US, sessionPnl)} USDT",
                    color = pnlColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }

              Spacer(Modifier.height(10.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                // Доступно USDT
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("ДОСТУПНО", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    val freeText = botState.freeUsdt?.let { "%.2f USDT".format(Locale.US, it) } ?: "--.--"
                    Text(freeText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                  }
                }

                // В ордерах USDT
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("В ОРДЕРАХ", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    val lockedText = botState.lockedUsdt?.let { "%.2f USDT".format(Locale.US, it) } ?: "--.--"
                    Text(lockedText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                  }
                }
              }
            }
          }

          // 2. КАРТОЧКА ОТКРЫТОЙ ПОЗИЦИИ (ЧАСТЬ B п.2)
          val currentPairSymbol = botState.selectedPair?.symbol ?: "BTCUSDT"
          val openPos = botState.openPositions[currentPairSymbol]
          if (openPos != null) {
            item {
              val curPrice = botState.tickerData?.lastPrice ?: openPos.entryPrice
              val unRealizedPnlUsdt = (curPrice - openPos.entryPrice) * openPos.quantity
              val unRealizedPnlPct = if (openPos.entryPrice > 0) ((curPrice - openPos.entryPrice) / openPos.entryPrice) * 100.0 else 0.0
              val isProfit = unRealizedPnlUsdt >= 0
              val posColor = if (isProfit) HudGreen else HudRed
              val pnlSign = if (isProfit) "+" else ""

              HudCard(
                title = "ОТКРЫТАЯ ПОЗИЦИЯ // ${openPos.symbol}",
                icon = Icons.Outlined.TrendingUp,
                borderColor = posColor,
                flashTriggerId = flashEvent?.id,
                flashType = flashEvent?.type ?: TradeFlashType.NONE,
                modifier = Modifier.fillMaxWidth().testTag("open_position_card")
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .background(HudGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .border(1.dp, HudGreen, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                      Text("LONG", color = HudGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                      Text(openPos.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                      Text("Объем: ${"%.4f".format(Locale.US, openPos.quantity)}", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                  }

                  // Нереализованный PnL в USDT и %
                  Column(horizontalAlignment = Alignment.End) {
                    Text(
                      "${pnlSign}${"%.2f".format(Locale.US, unRealizedPnlUsdt)} USDT",
                      color = posColor,
                      fontWeight = FontWeight.Bold,
                      fontSize = 15.sp,
                      fontFamily = FontFamily.Monospace
                    )
                    Text(
                      "${pnlSign}${"%.2f".format(Locale.US, unRealizedPnlPct)}%",
                      color = posColor,
                      fontWeight = FontWeight.Bold,
                      fontSize = 12.sp,
                      fontFamily = FontFamily.Monospace
                    )
                  }
                }

                Spacer(Modifier.height(10.dp))

                // Цены: Вход / Текущая
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("ЦЕНА ВХОДА", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("${"%.2f".format(Locale.US, openPos.entryPrice)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                  }
                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("ТЕКУЩАЯ ЦЕНА (WS)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("${"%.2f".format(Locale.US, curPrice)}", color = HudCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                  }
                }

                Spacer(Modifier.height(8.dp))

                // Уровни SL / TP
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1A00D4FF), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text("SL: ${"%.2f".format(Locale.US, openPos.stopLossPrice)} USDT", color = HudRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                  Text("TP: ${"%.2f".format(Locale.US, openPos.takeProfitPrice)} USDT", color = HudGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
              }
            }
          }

          // 3. КАРТОЧКА ВЫБОРА ПАРЫ И ТЕКУЩЕЙ ЦЕНЫ
          item {
            val tickerData = botState.tickerData
            val isPositive = (tickerData?.priceChangePercent ?: 0.0) >= 0
            val changeColor = if (isPositive) HudGreen else HudRed

            HudCard(
              title = "РЫНОЧНЫЙ ТИКЕР // LIVE STREAM",
              icon = Icons.Outlined.ShowChart,
              modifier = Modifier.fillMaxWidth().testTag("pair_selector_card")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                  .clickable { showPairDialog = true }
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.CurrencyExchange, contentDescription = null, tint = HudCyan, modifier = Modifier.size(20.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(
                    botState.selectedPair?.symbol ?: "BTCUSDT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text("ВЫБРАТЬ ПАРУ", color = HudCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                  Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = HudCyan)
                }
              }

              Spacer(Modifier.height(10.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column {
                  Text("ТЕКУЩАЯ ЦЕНА (WS)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  val priceStr = tickerData?.lastPrice?.let {
                    if (it < 1.0) "%.4f".format(Locale.US, it) else "%.2f".format(Locale.US, it)
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
                  val pct = tickerData?.priceChangePercent ?: 0.0
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

          // 4. КАРТОЧКА АНАЛИЗА РЫНКА // СТРАТЕГИЯ (ЧАСТЬ B п.5: явный индикатор Score/Threshold)
          item {
            val sig = botState.currentSignal
            val ind = sig?.indicators
            val strategyConfig = botState.strategyConfig
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

            val currentScore = sig?.score ?: 0
            val threshold = strategyConfig.minScoreThreshold
            val scoreStatusText = if (currentScore >= threshold) {
              "Score: $currentScore / $threshold — сигнал преодолел порог!"
            } else {
              "Score: $currentScore / $threshold — жду более сильного сигнала"
            }

            HudCard(
              title = "АНАЛИЗ РЫНКА // СТРАТЕГИЯ",
              icon = Icons.Outlined.Analytics,
              borderColor = actionColor,
              modifier = Modifier.fillMaxWidth().testTag("market_analysis_card")
            ) {
              // Таймфрейм свечей
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
                        .clickable { botEngine.updateStrategyConfig(strategyConfig.copy(interval = intvl)) }
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
                Column {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(actionText, color = actionColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Box(
                      modifier = Modifier
                        .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                        .border(1.dp, actionColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                      Text(
                        "SCORE: $currentScore%",
                        color = actionColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                      )
                    }
                  }
                  Spacer(Modifier.height(4.dp))
                  // Явное текстовое пояснение состояния алгоритма
                  Text(
                    scoreStatusText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                  )
                  Text(
                    "Порог входа: $threshold% | SL: ${strategyConfig.stopLossPercent}% | TP: ${strategyConfig.takeProfitPercent}%",
                    color = HudTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }

              Spacer(Modifier.height(12.dp))

              // Индикаторы RSI, EMA, MACD, Volume
              if (ind != null) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("RSI (14)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("%.1f".format(Locale.US, ind.rsi), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                      val rsiStatus = if (ind.rsi < 30) "ПЕРЕПРОДАН" else if (ind.rsi > 70) "ПЕРЕКУПЛЕН" else "НЕЙТРАЛЬНО"
                      Text(rsiStatus, color = if (ind.rsi < 30) HudGreen else if (ind.rsi > 70) HudRed else HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                  }

                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("EMA 9 / 21", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("%.0f / %.0f".format(Locale.US, ind.emaFast, ind.emaSlow), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                      Text(if (ind.isEmaBullish) "BULLISH (9>21)" else "BEARISH (9<21)", color = if (ind.isEmaBullish) HudGreen else HudRed, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                  }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("MACD (12,26,9)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("H: %.2f".format(Locale.US, ind.macdHist), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                      Text(if (ind.isMacdRising) "ИМПУЛЬС ВВЕРХ" else "ИМПУЛЬС ВНИЗ", color = if (ind.isMacdRising) HudGreen else HudRed, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                  }

                  Box(
                    modifier = Modifier
                      .weight(1f)
                      .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                      .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                      .padding(8.dp)
                  ) {
                    Column {
                      Text("VOLUME vs AVG(20)", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      Text("%.1f / %.1f".format(Locale.US, ind.currentVolume, ind.avgVolume), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                      Text(if (ind.isVolumeAboveAvg) "ВЫШЕ СРЕДНЕГО" else "НИЖЕ СРЕДНЕГО", color = if (ind.isVolumeAboveAvg) HudGreen else Color(0xFFFFB300), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                  }
                }

                Spacer(Modifier.height(8.dp))

                // Bollinger Bands
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text("BOLLINGER (20,2)", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  Text("L: %.1f | M: %.1f | U: %.1f".format(Locale.US, ind.bbLower, ind.bbMiddle, ind.bbUpper), color = HudPeach, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
              }
            }
          }

          // 5. КАРТОЧКА РАЗМЕРА ПОЗИЦИИ
          item {
            val currentPrice = botState.tickerData?.lastPrice ?: 0.0
            val enteredAmount = botState.positionAmountUsdt
            val baseQty = if (currentPrice > 0) enteredAmount / currentPrice else 0.0
            val minNotional = botState.selectedPair?.minNotional ?: 10.0
            val minLot = botState.selectedPair?.minQty ?: 0.00001
            val isBelowMin = enteredAmount > 0 && enteredAmount < minNotional

            HudCard(
              title = "РАЗМЕР ПОЗИЦИИ // УПРАВЛЕНИЕ РИСКОМ",
              icon = Icons.Outlined.Tune,
              modifier = Modifier.fillMaxWidth().testTag("position_size_card")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                OutlinedTextField(
                  value = "%.1f".format(Locale.US, botState.positionAmountUsdt),
                  onValueChange = {
                    val v = it.toDoubleOrNull() ?: 0.0
                    botEngine.updatePositionAmount(v, botState.positionPercent)
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
                    Text("КОЛИЧЕСТВО (${botState.selectedPair?.baseAsset ?: "QTY"})", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Text(
                      if (baseQty < 1.0) "%.6f".format(Locale.US, baseQty) else "%.4f".format(Locale.US, baseQty),
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
                Text("${botState.positionPercent.toInt()}%", color = HudCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
              }

              Slider(
                value = botState.positionPercent,
                onValueChange = {
                  val available = botState.freeUsdt ?: 100.0
                  val calc = (available * (it / 100f)).coerceAtLeast(10.0)
                  botEngine.updatePositionAmount(calc, it)
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
                  val selected = (botState.positionPercent.toInt() == p)
                  Box(
                    modifier = Modifier
                      .background(if (selected) HudCyan else Color(0x1A00D4FF), RoundedCornerShape(4.dp))
                      .border(1.dp, if (selected) HudCyan else Color(0x3300D4FF), RoundedCornerShape(4.dp))
                      .clickable {
                        val available = botState.freeUsdt ?: 100.0
                        val calc = (available * (p / 100.0)).coerceAtLeast(10.0)
                        botEngine.updatePositionAmount(calc, p.toFloat())
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

          // 6. КАРТОЧКА УПРАВЛЕНИЯ БОТОМ (ЧАСТЬ A п.4: StartForegroundService / StopService)
          item {
            val isBotActive = botState.isBotActive
            val botStatus = botState.botStatus
            val statusColor = Color(botStatus.colorHex)

            HudCard(
              title = "УПРАВЛЕНИЕ АЛГО-БОТОМ",
              icon = Icons.Outlined.SmartToy,
              borderColor = if (isBotActive) HudCyan else Color(0x66FF5252),
              flashTriggerId = flashEvent?.id,
              flashType = flashEvent?.type ?: TradeFlashType.NONE,
              modifier = Modifier.fillMaxWidth().testTag("bot_control_card")
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(if (isBotActive) Color(0x2600D4FF) else Color(0x1AFF5252), RoundedCornerShape(8.dp))
                  .border(1.5.dp, if (isBotActive) HudCyan else HudRed, RoundedCornerShape(8.dp))
                  .clickable {
                    if (isBotActive) {
                      botEngine.stop()
                      context.stopService(Intent(context, TradingForegroundService::class.java))
                    } else {
                      val intent = Intent(context, TradingForegroundService::class.java)
                      ContextCompat.startForegroundService(context, intent)
                    }
                  }
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
                        if (isBotActive) "БОТ АКТИВЕН (В ФОНЕ)" else "БОТ ОСТАНОВЛЕН",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                      )
                      Text(
                        if (isBotActive) "ForegroundService активен" else "Нажмите для запуска Foreground Service",
                        color = HudTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                      )
                    }
                  }

                  Switch(
                    checked = isBotActive,
                    onCheckedChange = { active ->
                      if (active) {
                        val intent = Intent(context, TradingForegroundService::class.java)
                        ContextCompat.startForegroundService(context, intent)
                      } else {
                        botEngine.stop()
                        context.stopService(Intent(context, TradingForegroundService::class.java))
                      }
                    },
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

          // 7. КАРТОЧКА "ЖУРНАЛ БОТА" (ЧАСТЬ B п.1)
          item {
            HudCard(
              title = "ЖУРНАЛ БОТА // LIVE LOG",
              icon = Icons.Outlined.ReceiptLong,
              borderColor = HudCyan,
              modifier = Modifier.fillMaxWidth().testTag("bot_logs_card")
            ) {
              if (botState.logs.isEmpty()) {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("Журнал пуст. Запустите бота для начала анализа.", color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
              } else {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    reverseLayout = false
                  ) {
                    items(botState.logs) { logItem ->
                      val logColor = when (logItem.type) {
                        LogType.ORDER_SUCCESS, LogType.PROFIT -> HudGreen
                        LogType.ORDER_ERROR, LogType.LOSS -> HudRed
                        LogType.SIGNAL -> HudCyan
                        LogType.INFO -> Color(0xFFB0C4DE)
                      }

                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                      ) {
                        Text(
                          "[${logItem.formattedTime}]",
                          color = HudTextMuted,
                          fontSize = 9.sp,
                          fontFamily = FontFamily.Monospace,
                          modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                          logItem.message,
                          color = logColor,
                          fontSize = 10.sp,
                          fontFamily = FontFamily.Monospace,
                          lineHeight = 14.sp
                        )
                      }
                      HorizontalDivider(color = Color(0x0D00D4FF), thickness = 0.5.dp)
                    }
                  }
                }
              }
            }
          }

          // 8. КАРТОЧКА АКТИВНЫХ ОРДЕРОВ БИРЖИ
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
                    .padding(vertical = 14.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("Нет открытых лимитных ордеров", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                        "%.2f".format(Locale.US, order.price),
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
                            val creds = botEngine.storageService.getCredentials()
                            if (creds != null && creds.apiKey.isNotEmpty()) {
                              botEngine.marketService.cancelOrder(creds.apiKey, creds.secretKey, order.symbol, order.orderId)
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
                  botEngine.selectPair(pair)
                  showPairDialog = false
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
      message = "Немедленно закрыть все открытые позиции бота по текущей цене и остановить алгоритм?",
      confirmButtonText = "ЗАКРЫТЬ ВСЕ",
      confirmButtonColor = HudRed,
      onDismiss = { showEmergencyDialog = false },
      onConfirm = {
        showEmergencyDialog = false
        botEngine.emergencyCloseAll()
        botEngine.stop()
        context.stopService(Intent(context, TradingForegroundService::class.java))
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
  onOpenChart: () -> Unit = {},
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
          .size(8.dp)
          .background(HudGreen, RoundedCornerShape(4.dp))
      )
      Spacer(Modifier.width(8.dp))
      Column {
        Text(
          "BINANCE TERMINAL // SPOT TESTNET",
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          letterSpacing = 1.sp
        )
        Text(
          "ALGO ENGINE ACTIVE // SECURE RSA",
          color = HudCyan,
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    Row {
      IconButton(
        onClick = onOpenChart,
        modifier = Modifier.size(34.dp).testTag("chart_nav_button")
      ) {
        Icon(Icons.Outlined.ShowChart, contentDescription = "График", tint = HudNeonPink)
      }
      IconButton(
        onClick = onOpenHistory,
        modifier = Modifier.size(34.dp).testTag("history_nav_button")
      ) {
        Icon(Icons.Outlined.ReceiptLong, contentDescription = "History", tint = HudCyan)
      }
      IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.size(34.dp).testTag("settings_button")
      ) {
        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = HudCyan)
      }
    }
  }
}
