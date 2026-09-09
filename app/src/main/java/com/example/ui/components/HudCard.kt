package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.TradeFlashType
import com.example.ui.theme.*

/**
 * Неоновая карточка со стеклянным эффектом (Glassmorphism):
 * - Глянцевая поверхность с CutCornerShape
 * - Градиентная неоновая заливка поверх тёмной подложки HudCardBg
 * - Неоновая обводка (HudNeonPink -> HudNeonPurple -> HudNeonBlue)
 * - Мягкое внешнее свечение вокруг карточки (HudNeonPurple)
 * - Глянцевый блик (glossy highlight) в верхних ~15% высоты
 * - Поддержка яркой импульсной вспышки рамки при открытии / закрытии сделок
 */
@Composable
fun HudCard(
  modifier: Modifier = Modifier,
  title: String,
  icon: ImageVector,
  borderColor: Color? = null,
  customBorderBrush: Brush? = null,
  flashTriggerId: String? = null,
  flashType: TradeFlashType = TradeFlashType.NONE,
  content: @Composable ColumnScope.() -> Unit
) {
  val cardShape = remember { CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp) }

  // Анимация вспышки рамки при событии сделки (толщина и яркость)
  val pulseAnim = remember { Animatable(0f) }

  LaunchedEffect(flashTriggerId) {
    if (!flashTriggerId.isNullOrBlank() && flashType != TradeFlashType.NONE) {
      // 4 цикла pulse (пульсация туда-обратно)
      pulseAnim.snapTo(0f)
      pulseAnim.animateTo(
        targetValue = 1f,
        animationSpec = repeatable(
          iterations = 4,
          animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse
        )
      )
      // Плавный возврат к обычному состоянию
      pulseAnim.animateTo(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing)
      )
    }
  }

  val pulseProgress = pulseAnim.value // 0f..1f

  // Динамическая толщина обводки: от обычной 1.8dp до 4.5dp в пике вспышки
  val borderWidth: Dp = (1.8f + (pulseProgress * 2.7f)).dp

  // Вычисление градиента обводки в зависимости от режима вспышки
  val borderBrush = remember(pulseProgress, flashType, borderColor, customBorderBrush) {
    if (pulseProgress > 0.02f) {
      when (flashType) {
        TradeFlashType.PROFIT -> {
          // Зелёно-неоновая вспышка (#00FF9C) с примесью неоновой розово-синей палитры и белым разрядом
          Brush.linearGradient(
            listOf(
              Color.White.copy(alpha = 0.9f * pulseProgress),
              HudGreen,
              Color(0xFF00E5FF),
              HudNeonPurple.copy(alpha = 0.8f)
            )
          )
        }
        TradeFlashType.LOSS -> {
          // Красно-неоновая вспышка (#FF3B5C) при Stop-Loss
          Brush.linearGradient(
            listOf(
              Color.White.copy(alpha = 0.9f * pulseProgress),
              HudRed,
              Color(0xFFFF1744),
              HudNeonPink.copy(alpha = 0.8f)
            )
          )
        }
        TradeFlashType.OPEN_POSITION -> {
          // Неоновая розово-фиолетовая вспышка при открытии новой сделки
          Brush.linearGradient(
            listOf(
              Color.White.copy(alpha = 0.85f * pulseProgress),
              HudNeonPink,
              HudNeonPurple,
              HudNeonBlue
            )
          )
        }
        else -> {
          customBorderBrush ?: Brush.linearGradient(listOf(HudNeonPink, HudNeonPurple, HudNeonBlue))
        }
      }
    } else {
      if (customBorderBrush != null) {
        customBorderBrush
      } else if (borderColor != null) {
        Brush.linearGradient(
          listOf(
            borderColor,
            borderColor.copy(alpha = 0.7f),
            borderColor
          )
        )
      } else {
        // Стандартная неоновая градиентная обводка карточки
        Brush.linearGradient(
          colors = listOf(
            HudNeonPink.copy(alpha = 0.9f),
            HudNeonPurple.copy(alpha = 0.95f),
            HudNeonBlue.copy(alpha = 0.85f)
          ),
          start = Offset(0f, 0f),
          end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
      }
    }
  }

  // Цвет внешнего свечения (Glow)
  val glowColor = remember(pulseProgress, flashType) {
    if (pulseProgress > 0.05f) {
      when (flashType) {
        TradeFlashType.PROFIT -> HudGreen.copy(alpha = 0.6f * pulseProgress)
        TradeFlashType.LOSS -> HudRed.copy(alpha = 0.6f * pulseProgress)
        else -> HudNeonPurple.copy(alpha = 0.6f * pulseProgress)
      }
    } else {
      HudNeonPurple.copy(alpha = 0.18f)
    }
  }

  // Внутренний полупрозрачный градиент стекла (от розового к синему)
  val glassGradient = remember {
    Brush.linearGradient(
      colors = listOf(
        HudNeonPink.copy(alpha = 0.14f),
        Color.Transparent,
        HudNeonBlue.copy(alpha = 0.16f)
      ),
      start = Offset(0f, 0f),
      end = Offset(0f, Float.POSITIVE_INFINITY)
    )
  }

  // Блик в верхних ~15% карточки (Glossy highlight)
  val highlightBrush = remember {
    Brush.verticalGradient(
      0.0f to Color.White.copy(alpha = 0.12f),
      0.15f to Color.Transparent,
      1.0f to Color.Transparent
    )
  }

  Box(
    modifier = modifier
      // 1. Внешнее мягкое неоновое свечение (Glow effect)
      .drawBehind {
        val glowPadding = if (pulseProgress > 0.05f) 8.dp.toPx() else 4.dp.toPx()
        drawRect(
          color = glowColor,
          topLeft = Offset(-glowPadding, -glowPadding),
          size = Size(size.width + glowPadding * 2, size.height + glowPadding * 2)
        )
      }
      // 2. Темная подложка для идеальной читаемости текста
      .background(HudCardBg, cardShape)
      // 3. Полупрозрачная градиентная заливка стекла (Glassmorphism)
      .background(glassGradient, cardShape)
      // 4. Тонкий глянцевый блик в верхней части
      .drawBehind {
        drawRect(
          brush = highlightBrush,
          topLeft = Offset.Zero,
          size = Size(size.width, size.height)
        )
      }
      // 5. Неоновая градиентная обводка с динамической толщиной
      .border(borderWidth, borderBrush, cardShape)
      .padding(16.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(30.dp)
            .background(
              Brush.linearGradient(listOf(HudNeonPink.copy(alpha = 0.25f), HudNeonPurple.copy(alpha = 0.25f))),
              CutCornerShape(4.dp)
            )
            .border(1.dp, HudNeonPink.copy(alpha = 0.6f), CutCornerShape(4.dp)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HudNeonPink,
            modifier = Modifier.size(16.dp)
          )
        }
        Spacer(Modifier.width(10.dp))
        Text(
          text = title,
          color = Color.White,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          letterSpacing = 1.1.sp,
          modifier = Modifier.weight(1f)
        )
      }
      Spacer(Modifier.height(14.dp))
      content()
    }
  }
}
