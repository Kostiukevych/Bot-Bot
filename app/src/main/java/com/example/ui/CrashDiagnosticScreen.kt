package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.HudNavyDark
import com.example.ui.theme.HudCyan
import com.example.ui.theme.HudPeach
import com.example.ui.theme.HudRed
import com.example.ui.theme.HudTextMuted

data class NativeCrashInfo(
  val threadName: String,
  val exceptionClass: String,
  val message: String,
  val stackTrace: String,
  val timestamp: Long = System.currentTimeMillis()
) {
  fun toReport(): String = """
=====================================================
BINANCE TESTNET TRADER // NATIVE ANDROID CRASH REPORT
=====================================================
Время сбоя: ${java.util.Date(timestamp)}
Поток: $threadName
Класс ошибки: $exceptionClass
Сообщение: $message

--- STACK TRACE ---
$stackTrace
=====================================================
""".trimIndent()
}

@Composable
fun CrashDiagnosticScreen(
  crashInfo: NativeCrashInfo,
  onRestart: () -> Unit,
  onClearStorage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val verticalScroll = rememberScrollState()
  val horizontalScroll = rememberScrollState()

  Scaffold(
    containerColor = HudNavyDark,
    modifier = modifier.fillMaxSize()
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Заголовок с предупреждением
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF1E0A14), RoundedCornerShape(12.dp))
          .border(1.5.dp, HudRed, RoundedCornerShape(12.dp))
          .padding(14.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .background(HudRed.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = HudRed, modifier = Modifier.size(24.dp))
          }
          Spacer(Modifier.width(12.dp))
          Column {
            Text(
              "КРИТИЧЕСКИЙ СБОЙ // CRASH SHIELD",
              color = HudRed,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              fontFamily = FontFamily.Monospace
            )
            Text(
              "Сбой изолирован без краша системы Android",
              color = Color.White.copy(alpha = 0.7f),
              fontSize = 11.sp
            )
          }
        }
      }

      // Информация об ошибке
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF141F32), RoundedCornerShape(8.dp))
          .border(1.dp, HudPeach.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
          .padding(12.dp)
      ) {
        Column {
          Text(
            "ОШИБКА: ${crashInfo.exceptionClass}",
            color = HudPeach,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.height(4.dp))
          Text(
            crashInfo.message.ifBlank { "Без описания" },
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.height(4.dp))
          Text(
            "Поток: ${crashInfo.threadName}",
            color = HudTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
        }
      }

      // Поле со стектрейсом
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(Color(0xFF07101E), RoundedCornerShape(8.dp))
          .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
          .padding(12.dp)
      ) {
        Column(modifier = Modifier.fillMaxSize()) {
          Text(
            "ПОЛНЫЙ STACK TRACE ДЛЯ АНАЛИЗА:",
            color = HudTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.height(8.dp))
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .verticalScroll(verticalScroll)
              .horizontalScroll(horizontalScroll)
          ) {
            Text(
              crashInfo.stackTrace,
              color = HudCyan,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              lineHeight = 16.sp
            )
          }
        }
      }

      // Кнопка копирования
      Button(
        onClick = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val clip = ClipData.newPlainText("Crash Report", crashInfo.toReport())
          clipboard.setPrimaryClip(clip)
          Toast.makeText(context, "Отчет скопирован в буфер обмена", Toast.LENGTH_LONG).show()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HudCyan)
      ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.Black)
        Spacer(Modifier.width(8.dp))
        Text("СКОПИРОВАТЬ ПОЛНУЮ ОШИБКУ", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
      }

      // Кнопки перезапуска и сброса
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedButton(
          onClick = onRestart,
          modifier = Modifier.weight(1f).height(44.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(Icons.Outlined.Refresh, contentDescription = null, tint = HudCyan, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("ПЕРЕЗАПУСК", color = HudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
          onClick = onClearStorage,
          modifier = Modifier.weight(1f).height(44.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = HudPeach, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("СБРОС KEYSTORE", color = HudPeach, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}
