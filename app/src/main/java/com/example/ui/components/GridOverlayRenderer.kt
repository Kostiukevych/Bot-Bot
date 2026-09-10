package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.model.GridBotState
import com.example.model.GridLevelStatus
import com.example.ui.formatPrice
import com.example.ui.theme.*

object GridOverlayRenderer {

  private val boundDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
  private val levelDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
  private val peakDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

  fun drawGridOverlay(
    drawScope: DrawScope,
    gridState: GridBotState,
    currentPrice: Double = 0.0,
    chartWidth: Float,
    chartHeight: Float,
    priceScaleWidthPx: Float,
    displayMin: Double,
    displayMax: Double,
    priceToY: (Double) -> Float,
    gridPaint: Paint,
    badgePaint: Paint,
    flashAlpha: Float
  ) {
    with(drawScope) {
      val config = gridState.config

      // 1. Анимация-вспышка при пересчёте сетки
      if (flashAlpha > 0.01f) {
        drawRect(
          color = HudCyan.copy(alpha = flashAlpha * 0.25f),
          topLeft = Offset(0f, 0f),
          size = Size(chartWidth, chartHeight)
        )
      }

      // 2. Линии верхней и нижней границы (Drag-маркеры)
      val hasCustomBounds = config.upperBound > 0.0 && config.lowerBound > 0.0
      if (hasCustomBounds) {

        // Верхняя граница (Upper Bound)
        val isUpperAbove = config.upperBound > displayMax
        val isUpperBelow = config.upperBound < displayMin
        val rawUpY = priceToY(config.upperBound)
        val upY = when {
          isUpperAbove -> 16f
          isUpperBelow -> chartHeight - 24f
          else -> rawUpY
        }

        drawLine(
          color = if (isUpperAbove) HudNeonPink else HudNeonPink.copy(alpha = 0.9f),
          start = Offset(0f, upY),
          end = Offset(chartWidth, upY),
          strokeWidth = if (isUpperAbove) 2.5f else 2f,
          pathEffect = boundDash
        )
        // Маркер перетаскивания (Drag Handle)
        drawCircle(
          color = HudNeonPink,
          radius = if (isUpperAbove) 8f else 6f,
          center = Offset(24f, upY)
        )
        drawCircle(
          color = Color.White,
          radius = 3.5f,
          center = Offset(24f, upY)
        )
        // Бейдж
        val upLabel = if (isUpperAbove) {
          "▲ ВЕРХ [ВЫШЕ ЭКРАНА]: ${formatPrice(config.upperBound)} ↓ ТЯНИТЕ"
        } else {
          "▲ ВЕРХ СЕТКИ: ${formatPrice(config.upperBound)}"
        }
        drawIntoCanvas { canvas ->
          canvas.nativeCanvas.drawText(
            upLabel,
            38f,
            if (isUpperAbove) upY + 13f else upY - 4f,
            gridPaint.apply { color = android.graphics.Color.argb(255, 255, 42, 133) }
          )
        }

        // Нижняя граница (Lower Bound)
        val isLowerBelow = config.lowerBound < displayMin
        val isLowerAbove = config.lowerBound > displayMax
        val rawLowY = priceToY(config.lowerBound)
        val lowY = when {
          isLowerBelow -> chartHeight - 16f
          isLowerAbove -> 24f
          else -> rawLowY
        }

        drawLine(
          color = if (isLowerBelow) HudGreen else HudGreen.copy(alpha = 0.9f),
          start = Offset(0f, lowY),
          end = Offset(chartWidth, lowY),
          strokeWidth = if (isLowerBelow) 2.5f else 2f,
          pathEffect = boundDash
        )
        // Маркер перетаскивания (Drag Handle)
        drawCircle(
          color = HudGreen,
          radius = if (isLowerBelow) 8f else 6f,
          center = Offset(24f, lowY)
        )
        drawCircle(
          color = Color.White,
          radius = 3.5f,
          center = Offset(24f, lowY)
        )
        // Бейдж
        val lowLabel = if (isLowerBelow) {
          "▼ НИЗ [НИЖЕ ЭКРАНА]: ${formatPrice(config.lowerBound)} ↑ ТЯНИТЕ"
        } else {
          "▼ НИЗ СЕТКИ: ${formatPrice(config.lowerBound)}"
        }
        drawIntoCanvas { canvas ->
          canvas.nativeCanvas.drawText(
            lowLabel,
            38f,
            if (isLowerBelow) lowY - 5f else lowY + 12f,
            gridPaint.apply { color = android.graphics.Color.argb(255, 0, 230, 118) }
          )
        }
      }

      // 3. Уровни сетки (Активные или превью при настройке)
      if (gridState.isActive && gridState.levels.isNotEmpty()) {
        for (level in gridState.levels) {
          val p = level.price
          if (p in displayMin..displayMax) {
            val y = priceToY(p)

            val (lineColor, tagStr) = when (level.status) {
              GridLevelStatus.FILLED -> Pair(HudCyan, "FILLED")
              GridLevelStatus.PENDING_BUY -> Pair(HudGreen, "BUY")
              GridLevelStatus.PENDING_SELL -> Pair(HudNeonPink, "SELL")
              GridLevelStatus.CANCELED -> Pair(HudTextMuted, "CANC")
            }

            // Тонкая горизонтальная линия уровня
            drawLine(
              color = lineColor.copy(alpha = 0.85f),
              start = Offset(0f, y),
              end = Offset(chartWidth, y),
              strokeWidth = 1.2f,
              pathEffect = levelDash
            )

            // Правый бейдж уровня
            val badgeH = 13f
            val badgeW = 44f
            val badgeX = chartWidth - badgeW - 2f
            val badgeY = y - badgeH / 2

            drawRect(
              color = Color(0xDD071220),
              topLeft = Offset(badgeX, badgeY),
              size = Size(badgeW, badgeH)
            )
            drawRect(
              color = lineColor,
              topLeft = Offset(badgeX, badgeY),
              size = Size(badgeW, badgeH),
              style = Stroke(1f)
            )

            drawIntoCanvas { canvas ->
              val text = "#${level.index + 1} $tagStr"
              canvas.nativeCanvas.drawText(
                text,
                badgeX + 3f,
                badgeY + badgeH - 3f,
                badgePaint.apply {
                  color = when (level.status) {
                    GridLevelStatus.FILLED -> android.graphics.Color.argb(255, 0, 229, 255)
                    GridLevelStatus.PENDING_BUY -> android.graphics.Color.argb(255, 0, 230, 118)
                    GridLevelStatus.PENDING_SELL -> android.graphics.Color.argb(255, 255, 42, 133)
                    GridLevelStatus.CANCELED -> android.graphics.Color.argb(255, 126, 155, 184)
                  }
                }
              )
            }
          }
        }
      } else if (!gridState.isActive && hasCustomBounds && config.upperBound > config.lowerBound) {
        // Превью уровней при настройке сетки
        val n = config.levelCount.coerceIn(3, 50)
        val ratio = config.buySellRatio.coerceIn(0.1f, 0.9f)
        val buyCount = (n * ratio).toInt().coerceIn(1, n - 1)
        val sellCount = (n - buyCount).coerceAtLeast(1)

        val curP = if (currentPrice in config.lowerBound..config.upperBound) currentPrice else (config.lowerBound + config.upperBound) / 2.0
        val buyStep = (curP - config.lowerBound) / buyCount
        val sellStep = (config.upperBound - curP) / sellCount

        // Превью BUY уровней (зеленые)
        for (i in 0 until buyCount) {
          val p = config.lowerBound + i * buyStep
          if (p in displayMin..displayMax) {
            val y = priceToY(p)
            drawLine(
              color = HudGreen.copy(alpha = 0.5f),
              start = Offset(0f, y),
              end = Offset(chartWidth, y),
              strokeWidth = 1f,
              pathEffect = levelDash
            )
            drawIntoCanvas { canvas ->
              canvas.nativeCanvas.drawText(
                "BUY #${i + 1}",
                chartWidth - 48f,
                y + 3.5f,
                badgePaint.apply { color = android.graphics.Color.argb(180, 0, 230, 118) }
              )
            }
          }
        }

        // Превью SELL уровней (красные)
        for (j in 0 until sellCount) {
          val p = curP + (j + 1) * sellStep
          if (p in displayMin..displayMax) {
            val y = priceToY(p)
            drawLine(
              color = HudNeonPink.copy(alpha = 0.5f),
              start = Offset(0f, y),
              end = Offset(chartWidth, y),
              strokeWidth = 1f,
              pathEffect = levelDash
            )
            drawIntoCanvas { canvas ->
              canvas.nativeCanvas.drawText(
                "SELL #${buyCount + j + 1}",
                chartWidth - 52f,
                y + 3.5f,
                badgePaint.apply { color = android.graphics.Color.argb(180, 255, 42, 133) }
              )
            }
          }
        }
      }

      // 4. Пунктирная линия peakPrice (отслеживается для трейлинга)
      if (gridState.isActive && gridState.peakPrice > 0.0 && gridState.peakPrice in displayMin..displayMax) {
        val peakY = priceToY(gridState.peakPrice)

        drawLine(
          color = HudNeonPurple,
          start = Offset(0f, peakY),
          end = Offset(chartWidth, peakY),
          strokeWidth = 1.8f,
          pathEffect = peakDash
        )

        // Неоновый бейдж PEAK
        val peakBadgeW = 86f
        val peakBadgeH = 14f
        val peakBadgeX = 6f
        val peakBadgeY = (peakY - peakBadgeH - 2f).coerceAtLeast(2f)

        drawRect(
          color = Color(0xEE2A0845),
          topLeft = Offset(peakBadgeX, peakBadgeY),
          size = Size(peakBadgeW, peakBadgeH)
        )
        drawRect(
          color = HudNeonPurple,
          topLeft = Offset(peakBadgeX, peakBadgeY),
          size = Size(peakBadgeW, peakBadgeH),
          style = Stroke(1.2f)
        )

        drawIntoCanvas { canvas ->
          val peakText = "PEAK: ${formatPrice(gridState.peakPrice)}"
          canvas.nativeCanvas.drawText(
            peakText,
            peakBadgeX + 4f,
            peakBadgeY + peakBadgeH - 3.5f,
            badgePaint.apply { color = android.graphics.Color.argb(255, 176, 38, 255) }
          )
        }
      }
    }
  }
}
