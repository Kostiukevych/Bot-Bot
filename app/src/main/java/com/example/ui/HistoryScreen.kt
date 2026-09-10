package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MyApplication
import com.example.model.TradeRecord
import com.example.service.BinanceMarketService
import com.example.service.SecureStorageService
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
  modifier: Modifier = Modifier,
  onBackToDashboard: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val storageService = remember { SecureStorageService(context) }
  val marketService = remember { BinanceMarketService() }
  val app = context.applicationContext as? MyApplication
  val botEngine = app?.botEngine

  var allTrades by remember { mutableStateOf<List<TradeRecord>>(emptyList()) }
  var filteredTrades by remember { mutableStateOf<List<TradeRecord>>(emptyList()) }
  var selectedSymbol by remember { mutableStateOf("ALL") }
  var isLoading by remember { mutableStateOf(true) }
  var isSyncing by remember { mutableStateOf(false) }
  var showCsvDialog by remember { mutableStateOf(false) }
  var csvContent by remember { mutableStateOf("") }

  val symbols = listOf("ALL", "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")

  fun applyFilters(trades: List<TradeRecord>, symbol: String) {
    filteredTrades = if (symbol == "ALL") {
      trades
    } else {
      trades.filter { it.symbol.equals(symbol, ignoreCase = true) }
    }
  }

  suspend fun loadTrades() {
    isLoading = true
    try {
      val loaded = withContext(Dispatchers.IO) {
        val botHistory = botEngine?.stateFlow?.value?.tradeHistory ?: emptyList()
        val combined = mutableListOf<TradeRecord>()
        combined.addAll(botHistory)

        val creds = storageService.getCredentials()
        if (creds != null && creds.apiKey.isNotBlank() && creds.secretKey.isNotBlank()) {
          // Запрос реальных исполнений через API биржи
          val syms = if (selectedSymbol == "ALL") listOf("BTCUSDT", "ETHUSDT", "BNBUSDT") else listOf(selectedSymbol)
          for (s in syms) {
            try {
              val raw = marketService.getMyTrades(creds.apiKey, creds.secretKey, s, limit = 30)
              raw.forEach { m: Map<String, Any> ->
                val price = m["price"]?.toString()?.toDoubleOrNull() ?: 0.0
                val qty = m["qty"]?.toString()?.toDoubleOrNull() ?: 0.0
                val isBuyer = m["isBuyer"] == true
                val time = (m["time"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val quoteQty = m["quoteQty"]?.toString()?.toDoubleOrNull() ?: (price * qty)
                val orderId = m["orderId"]?.toString() ?: ""
                val idStr = "API_${m["id"]}"

                // Добавляем только если нет дубликата
                if (combined.none { it.id == idStr }) {
                  combined.add(
                    TradeRecord(
                      id = idStr,
                      symbol = s,
                      side = if (isBuyer) "BUY" else "SELL",
                      entryPrice = price,
                      quantity = qty,
                      usdtAmount = quoteQty,
                      stopLossPrice = if (isBuyer) price * 0.98 else price * 1.02,
                      takeProfitPrice = if (isBuyer) price * 1.04 else price * 0.96,
                      score = 75,
                      entryTime = time,
                      exitPrice = if (!isBuyer) price else null,
                      exitTime = if (!isBuyer) time else null,
                      status = if (isBuyer) "OPEN" else "CLOSED_MANUAL",
                      exitReason = if (isBuyer) null else "Биржевое исполнение (Order #$orderId)",
                      realizedPnlUsdt = null,
                      realizedPnlPercent = null
                    )
                  )
                }
              }
            } catch (_: Exception) {}
          }
        }
        // Сортировка от новых к старым (Desc by entryTime)
        combined.sortedByDescending { it.entryTime }
      }
      allTrades = loaded
      applyFilters(loaded, selectedSymbol)
    } finally {
      isLoading = false
    }
  }

  LaunchedEffect(Unit) {
    loadTrades()
  }

  // Расчет расширенной сводной статистики
  val closedTrades = filteredTrades.filter { it.status != "OPEN" }
  val winningTrades = closedTrades.count { (it.realizedPnlUsdt ?: 0.0) > 0 }
  val losingTrades = closedTrades.count { (it.realizedPnlUsdt ?: 0.0) < 0 }
  val totalPnlUsdt = closedTrades.sumOf { it.realizedPnlUsdt ?: 0.0 }
  val winrate = if (closedTrades.isNotEmpty()) (winningTrades.toDouble() / closedTrades.size) * 100.0 else 0.0
  val avgGain = if (winningTrades > 0) closedTrades.filter { (it.realizedPnlUsdt ?: 0.0) > 0 }.sumOf { it.realizedPnlUsdt ?: 0.0 } / winningTrades else 0.0
  val avgLoss = if (losingTrades > 0) closedTrades.filter { (it.realizedPnlUsdt ?: 0.0) < 0 }.sumOf { Math.abs(it.realizedPnlUsdt ?: 0.0) } / losingTrades else 0.0

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(Color(0xFF0A1628), Color(0xFF0D2340), Color(0xFF081220))
        )
      )
  ) {
    // HUD Фоновая сетка
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
      // 1. TopBar
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
          IconButton(
            onClick = onBackToDashboard,
            modifier = Modifier.size(34.dp).testTag("back_button")
          ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = HudCyan)
          }
          Spacer(Modifier.width(8.dp))
          Column {
            Text(
              "ИСТОРИЯ СДЕЛОК // ЛОГ",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              letterSpacing = 1.sp
            )
            Text(
              "BINANCE TESTNET REAL TRADES",
              color = HudTextMuted,
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        Row {
          // Кнопка синхронизации
          IconButton(
            onClick = {
              scope.launch {
                isSyncing = true
                loadTrades()
                isSyncing = false
                Toast.makeText(context, "Синхронизировано с Binance Testnet", Toast.LENGTH_SHORT).show()
              }
            },
            enabled = !isSyncing,
            modifier = Modifier.size(34.dp).testTag("sync_button")
          ) {
            if (isSyncing) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = HudCyan)
            } else {
              Icon(Icons.Outlined.Sync, contentDescription = "Sync", tint = HudCyan)
            }
          }

          // Кнопка экспорта CSV
          IconButton(
            onClick = {
              if (filteredTrades.isEmpty()) {
                Toast.makeText(context, "Нет сделок для экспорта", Toast.LENGTH_SHORT).show()
              } else {
                val sb = StringBuilder()
                sb.append("ID,Symbol,Side,EntryTime,ExitTime,EntryPrice,ExitPrice,Qty,UsdtAmount,Status,RealizedPnlUsdt,RealizedPnlPct,ExitReason\n")
                filteredTrades.forEach { t ->
                  sb.append("${t.id},${t.symbol},${t.side},${t.entryTime},${t.exitTime ?: ""},${t.entryPrice},${t.exitPrice ?: ""},${t.quantity},${t.usdtAmount},${t.status},${t.realizedPnlUsdt ?: ""},${t.realizedPnlPercent ?: ""},\"${t.exitReason ?: ""}\"\n")
                }
                csvContent = sb.toString()
                showCsvDialog = true
              }
            },
            modifier = Modifier.size(34.dp).testTag("export_csv_button")
          ) {
            Icon(Icons.Outlined.FileDownload, contentDescription = "Export CSV", tint = HudCyan)
          }
        }
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 14.dp, vertical = 12.dp)
      ) {
        // 2. Сводная статистика (ЧАСТЬ B п.4)
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(HudCardBg, RoundedCornerShape(8.dp))
              .border(1.2.dp, HudCyan, RoundedCornerShape(8.dp))
              .padding(14.dp)
          ) {
            Column {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.Insights, contentDescription = null, tint = HudCyan, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("СВОДНАЯ СТАТИСТИКА", color = HudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Text("ВСЕГО: ${filteredTrades.size}", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
              Spacer(Modifier.height(8.dp))

              // Текстовая сводка по запросу:
              // "Всего сделок: N | Прибыльных: X | Убыточных: Y | Винрейт: Z% | Суммарный PnL: ±USDT"
              val pnlPrefix = if (totalPnlUsdt >= 0) "+" else ""
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                  .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                  .padding(8.dp)
              ) {
                Text(
                  "Всего сделок: ${filteredTrades.size} | Прибыльных: $winningTrades | Убыточных: $losingTrades | Винрейт: ${"%.1f".format(Locale.US, winrate)}% | Суммарный PnL: ${pnlPrefix}${"%.2f".format(Locale.US, totalPnlUsdt)} USDT",
                  color = Color.White,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium,
                  fontFamily = FontFamily.Monospace,
                  lineHeight = 16.sp
                )
              }

              Spacer(Modifier.height(10.dp))

              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Winrate
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                    .padding(10.dp)
                ) {
                  Column {
                    Text("ВИНРЕЙТ", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                    Text(
                      "${String.format(Locale.US, "%.1f", winrate)}%",
                      color = if (winrate >= 50) HudGreen else HudPeach,
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = FontFamily.Monospace
                    )
                    Text("W: $winningTrades / L: $losingTrades", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  }
                }

                // Total PnL
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, if (totalPnlUsdt >= 0) HudGreen.copy(alpha = 0.4f) else HudRed.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(10.dp)
                ) {
                  Column {
                    Text("СУММАРНЫЙ PnL", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                    Text(
                      String.format(Locale.US, "%s%.2f $", if (totalPnlUsdt >= 0) "+" else "", totalPnlUsdt),
                      color = if (totalPnlUsdt >= 0) HudGreen else HudRed,
                      fontSize = 17.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = FontFamily.Monospace
                    )
                    Text("SPOT TESTNET", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }
              }

              Spacer(Modifier.height(10.dp))
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x2200D4FF), RoundedCornerShape(6.dp))
                    .padding(8.dp)
                ) {
                  Column {
                    Text("СРЕД. ПРИБЫЛЬ", color = HudTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "+%.2f $", avgGain), color = HudGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
                    Text("СРЕД. УБЫТОК", color = HudTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(String.format(Locale.US, "-%.2f $", avgLoss), color = HudRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                  }
                }
              }
            }
          }
        }

        // 3. Equity Curve
        item {
          Spacer(Modifier.height(14.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(HudCardBg, RoundedCornerShape(8.dp))
              .border(1.2.dp, HudCyan, RoundedCornerShape(8.dp))
              .padding(14.dp)
          ) {
            Column {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, tint = HudCyan, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("EQUITY CURVE // PnL", color = HudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Text(
                  String.format(Locale.US, "%s%.2f USDT", if (totalPnlUsdt >= 0) "+" else "", totalPnlUsdt),
                  color = if (totalPnlUsdt >= 0) HudGreen else HudRed,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace
                )
              }
              Spacer(Modifier.height(10.dp))

              Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                if (filteredTrades.isEmpty()) {
                  drawLine(
                    color = Color(0x3300D4FF),
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 2f
                  )
                  return@Canvas
                }

                val pnlValues = mutableListOf(0f)
                var acc = 0f
                closedTrades.reversed().forEach { t ->
                  acc += (t.realizedPnlUsdt ?: 0.0).toFloat()
                  pnlValues.add(acc)
                }

                val minV = pnlValues.minOrNull() ?: 0f
                val maxV = pnlValues.maxOrNull() ?: 1f
                val range = (maxV - minV).coerceAtLeast(1f)
                val stepX = size.width / (pnlValues.size - 1).coerceAtLeast(1)

                val path = Path()
                pnlValues.forEachIndexed { idx, v ->
                  val x = idx * stepX
                  val y = size.height - ((v - minV) / range * size.height * 0.8f) - (size.height * 0.1f)
                  if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                  path = path,
                  color = if (acc >= 0) HudGreen else HudRed,
                  style = Stroke(width = 3.dp.toPx())
                )
              }
            }
          }
        }

        // 4. Фильтры по символам
        item {
          Spacer(Modifier.height(14.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            symbols.forEach { sym ->
              val isSel = (sym == selectedSymbol)
              Box(
                modifier = Modifier
                  .background(if (isSel) HudCyan else Color(0x1A00D4FF), RoundedCornerShape(4.dp))
                  .border(1.dp, if (isSel) HudCyan else Color(0x3300D4FF), RoundedCornerShape(4.dp))
                  .clickable {
                    selectedSymbol = sym
                    applyFilters(allTrades, sym)
                  }
                  .padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                Text(
                  sym,
                  color = if (isSel) HudNavyDark else Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
          Spacer(Modifier.height(10.dp))
        }

        // 5. Список сделок
        if (isLoading) {
          item {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(color = HudCyan)
            }
          }
        } else if (filteredTrades.isEmpty()) {
          item {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
              Text("Сделок не найдено", color = HudTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
          }
        } else {
          items(filteredTrades, key = { it.id }) { t ->
            val isBuy = t.side.equals("BUY", ignoreCase = true)
            val sideColor = if (isBuy) HudGreen else HudRed
            val isClosed = t.status != "OPEN"
            val pnl = t.realizedPnlUsdt
            val pnlPct = t.realizedPnlPercent
            val dateFormat = SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.US)
            val entryTimeStr = dateFormat.format(Date(t.entryTime))
            val exitTimeStr = t.exitTime?.let { dateFormat.format(Date(it)) }

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .border(1.dp, if (isClosed) Color(0x2200D4FF) else Color(0x5500D4FF), RoundedCornerShape(6.dp))
                .padding(10.dp)
            ) {
              Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .background(sideColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .border(1.dp, sideColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                      Text(t.side, color = sideColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(t.symbol, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Box(
                      modifier = Modifier
                        .background(Color(0x2200D4FF), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0x4400D4FF), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                      Text(t.status, color = HudCyan, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
                  }

                  if (pnl != null) {
                    val pnlColor = if (pnl >= 0) HudGreen else HudRed
                    val pnlPctStr = pnlPct?.let { " (${if (it >= 0) "+" else ""}${"%.2f".format(Locale.US, it)}%)" } ?: ""
                    Text(
                      "${if (pnl >= 0) "+" else ""}${"%.2f".format(Locale.US, pnl)} USDT$pnlPctStr",
                      color = pnlColor,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = FontFamily.Monospace
                    )
                  } else {
                    Text("В РЫНКЕ", color = HudCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                  }
                }

                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("ВХОД: ${"%.2f".format(Locale.US, t.entryPrice)}", color = Color(0xFFB0C4DE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  Text(if (t.exitPrice != null) "ВЫХОД: ${"%.2f".format(Locale.US, t.exitPrice!!)}" else "ВЫХОД: --", color = Color(0xFFB0C4DE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  Text("ОБЪЕМ: ${"%.4f".format(Locale.US, t.quantity)}", color = HudPeach, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Время входа: $entryTimeStr", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  if (exitTimeStr != null) {
                    Text("Выход: $exitTimeStr", color = HudTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                  }
                }

                if (t.exitReason != null) {
                  Spacer(Modifier.height(4.dp))
                  Text("Причина: ${t.exitReason}", color = Color(0xFF536D88), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
              }
            }
          }
        }

        item {
          Spacer(Modifier.height(24.dp))
        }
      }
    }

    // CSV Экспорт Диалог
    if (showCsvDialog) {
      AlertDialog(
        onDismissRequest = { showCsvDialog = false },
        title = {
          Text("ЭКСПОРТ CSV", color = HudCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        },
        text = {
          Column {
            Text("Сформирован CSV отчет по ${filteredTrades.size} сделкам:", color = Color.White, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF071220), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
                .padding(8.dp)
            ) {
              Text(
                csvContent.take(500) + if (csvContent.length > 500) "\n..." else "",
                color = HudPeach,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              val clip = ClipData.newPlainText("Trade History CSV", csvContent)
              clipboard.setPrimaryClip(clip)
              Toast.makeText(context, "CSV скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
              showCsvDialog = false
            }
          ) {
            Text("КОПИРОВАТЬ", color = HudCyan, fontFamily = FontFamily.Monospace)
          }
        },
        dismissButton = {
          TextButton(onClick = { showCsvDialog = false }) {
            Text("ОТМЕНА", color = HudTextMuted, fontFamily = FontFamily.Monospace)
          }
        }
      )
    }
  }
}
