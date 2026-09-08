package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.service.SecureStorageService
import com.example.ui.ApiSettingsScreen
import com.example.ui.CrashDiagnosticScreen
import com.example.ui.DashboardScreen
import com.example.ui.HistoryScreen
import com.example.ui.NativeCrashInfo
import com.example.ui.theme.MyApplicationTheme
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

  companion object {
    val globalCrashState = androidx.compose.runtime.mutableStateOf<NativeCrashInfo?>(null)
  }

  private val requestNotificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }

    // Перехват всех необработанных исключений на уровне JVM / Android процесса
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      val sw = StringWriter()
      throwable.printStackTrace(PrintWriter(sw))
      val crashInfo = NativeCrashInfo(
        threadName = thread.name,
        exceptionClass = throwable.javaClass.name,
        message = throwable.message ?: "Без описания",
        stackTrace = sw.toString()
      )
      runOnUiThread {
        globalCrashState.value = crashInfo
      }
    }

    setContent {
      MyApplicationTheme(darkTheme = true) {
        val crashInfo = globalCrashState.value

        if (crashInfo != null) {
          CrashDiagnosticScreen(
            crashInfo = crashInfo,
            onRestart = {
              globalCrashState.value = null
            },
            onClearStorage = {
              try {
                val storage = SecureStorageService(this@MainActivity)
                storage.clearAllData()
              } catch (_: Exception) {}
              globalCrashState.value = null
            },
            modifier = Modifier.fillMaxSize()
          )
        } else {
          var currentScreen by remember { mutableStateOf("dashboard") }

          when (currentScreen) {
            "dashboard" -> {
              DashboardScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenSettings = { currentScreen = "settings" },
                onOpenHistory = { currentScreen = "history" }
              )
            }
            "history" -> {
              HistoryScreen(
                modifier = Modifier.fillMaxSize(),
                onBackToDashboard = { currentScreen = "dashboard" }
              )
            }
            else -> {
              ApiSettingsScreen(
                modifier = Modifier.fillMaxSize(),
                onBackToDashboard = { currentScreen = "dashboard" }
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("Android") }
}

