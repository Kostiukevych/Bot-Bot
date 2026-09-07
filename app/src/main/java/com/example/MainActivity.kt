package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.ApiSettingsScreen
import com.example.ui.DashboardScreen
import com.example.ui.HistoryScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = true) {
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("Android") }
}

