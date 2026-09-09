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

  fun drawGridOverlay(
    drawScope: DrawScope,
    gridState: GridBotState,
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
        val upY = priceToY(config.upperBound)
        val lowY = priceToY(config.lowerBound)

        val boundDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

        // Верхняя граница
        if (config.upperBound in displayMin..displayMax) {
          drawLine(
            color = HudNeonPink,
            start = Offset(0f, upY),
            end = Offset(chartWidth, upY),
            strokeWidth = 2f,
            pathEffect = boundDash
          )
          // Маркер перетаскивания (Drag Handle)
          drawCircle(
            color = HudNeonPink,
            radius = 6f,
            center = Offset(24f, upY)
          )
          drawCircle(
            color = Color.White,
            radius = 3f,
            center = Offset(24f, upY)
          )
          // Бейдж
          val label = "▲ ВЕРХ СЕТКИ: ${formatPrice(config.upperBound)}"
          drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(label, 36f, upY - 4f, gridPaint.apply { color = android.graphics.Color.argb(255, 255, 42, 133) })
          }
        }

        // Нижняя граница
        if (config.lowerBound in displayMin..displayMax) {
          drawLine(
            color = HudGreen,
            start = Offset(0f, lowY),
            end = Offset(chartWidth, lowY),
            strokeWidth = 2f,
            pathEffect = boundDash
          )
          // Маркер перетаскивания (Drag Handle)
          drawCircle(
            color = HudGreen,
            radius = 6f,
            center = Offset(24f, lowY)
          )
          drawCircle(
            color = Color.White,
            radius = 3f,
            center = Offset(24f, lowY)
          )
          // Бейдж
          val label = "▼ НИЗ СЕТКИ: ${formatPrice(config.lowerBound)}"
          drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(label, 36f, lowY + 12f, gridPaint.apply { color = android.graphics.Color.argb(255, 0, 230, 118) })
          }
        }
      }

      // 3. Активные уровни сетки (если сетка запущена)
      if (gridState.isActive && gridState.levels.isNotEmpty()) {
        val levelDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)

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
      }

      // 4. Пунктирная линия peakPrice (отслеживается для трейлинга)
      if (gridState.isActive && gridState.peakPrice > 0.0 && gridState.peakPrice in displayMin..displayMax) {
        val peakY = priceToY(gridState.peakPrice)
        val peakDash = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)

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
