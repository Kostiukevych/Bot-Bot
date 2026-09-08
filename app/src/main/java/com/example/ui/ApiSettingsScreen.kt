package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ApiCredentials
import com.example.model.BinanceAccountInfo
import com.example.model.ConnectionStatus
import com.example.service.BinanceAuthService
import com.example.service.SecureStorageService
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
  modifier: Modifier = Modifier,
  onBackToDashboard: () -> Unit = {},
  storageService: SecureStorageService = SecureStorageService(LocalContext.current),
  authService: BinanceAuthService = remember { BinanceAuthService() },
) {
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  var profileName by remember { mutableStateOf("Spot Testnet Master") }
  var apiKey by remember { mutableStateOf("") }
  var secretKey by remember { mutableStateOf("") }
  var isTestnet by remember { mutableStateOf(true) }

  var obscureApiKey by remember { mutableStateOf(true) }
  var obscureSecretKey by remember { mutableStateOf(true) }

  var isLoading by remember { mutableStateOf(false) }
  var loadingMessage by remember { mutableStateOf("") }

  var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle) }
  var accountInfo by remember { mutableStateOf<BinanceAccountInfo?>(null) }
  var lastUpdatedTime by remember { mutableStateOf<Long?>(null) }

  var showResetKeysDialog by remember { mutableStateOf(false) }
  var showResetAllDialog by remember { mutableStateOf(false) }

  // Загрузка сохраненных ключей при запуске
  LaunchedEffect(Unit) {
    val saved = storageService.getCredentials()
    if (saved != null) {
      profileName = saved.profileName
      apiKey = saved.apiKey
      secretKey = saved.secretKey
      isTestnet = saved.isTestnet
    }
  }

  val backgroundBrush = remember {
    Brush.verticalGradient(
      colors = listOf(HudNavyDark, HudNavySurface, Color(0xFF071220))
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = HudNavyDark,
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { data ->
        Snackbar(
          snackbarData = data,
          containerColor = Color(0xFF072733),
          contentColor = Color.White,
          shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
          modifier = Modifier
            .border(1.dp, HudCyan, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(12.dp)
        )
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(backgroundBrush)
        .drawBehind {
          // Кибернетическая сетка HUD
          val step = 40.dp.toPx()
          val gridColor = Color(0x0A00D4FF)
          var x = 0f
          while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
          }
          var y = 0f
          while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
          }
        }
        .padding(innerPadding)
        .imePadding()
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
      ) {
        // Заголовок HUD терминала
        item {
          HudHeader(onBack = onBackToDashboard)
        }

        // Профиль ключей
        item {
          HudCard(
            modifier = Modifier.fillMaxWidth(),
            title = "ПРОФИЛЬ УЧЕТНЫХ ДАННЫХ",
            icon = Icons.Outlined.Badge
          ) {
            OutlinedTextField(
              value = profileName,
              onValueChange = { profileName = it },
              label = { Text("Название профиля", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
              placeholder = { Text("Spot Testnet Master", color = HudTextMuted) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_name_input"),
              colors = hudTextFieldColors(),
              singleLine = true,
              shape = RoundedCornerShape(6.dp)
            )
          }
        }

        // Переключатель Сети: Testnet / Live
        item {
          HudCard(
            modifier = Modifier.fillMaxWidth(),
            title = "РЕЖИМ ПОДКЛЮЧЕНИЯ (СЕТЬ)",
            icon = Icons.Outlined.AltRoute
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              // Testnet Активен
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(Color(0x2600D4FF), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                  .border(1.5.dp, HudCyan, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                  .padding(vertical = 12.dp, horizontal = 8.dp)
                  .testTag("testnet_button"),
                contentAlignment = Alignment.Center
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(Icons.Outlined.Science, contentDescription = null, tint = HudCyan)
                  Spacer(Modifier.height(4.dp))
                  Text(
                    "SPOT TESTNET",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                  )
                  Text("testnet.binance.vision", color = HudTextMuted, fontSize = 10.sp)
                }
              }

              // Live Заблокирован
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(Color(0x0DFFFFFF), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                  .border(1.dp, Color(0x33FFFFFF), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                  .padding(vertical = 12.dp, horizontal = 8.dp)
                  .testTag("live_disabled_button"),
                contentAlignment = Alignment.Center
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(Icons.Outlined.Lock, contentDescription = null, tint = HudTextMuted)
                  Spacer(Modifier.height(4.dp))
                  Text(
                    "LIVE (MAINNET)",
                    color = HudTextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                  )
                  Text("Доступно после подтверждения", color = HudPeach, fontSize = 9.sp)
                }
              }
            }

            Spacer(Modifier.height(8.dp))
            Text(
              "⚠️ Защита депозита: Mainnet физически заблокирован, пока режим Testnet включен.",
              color = HudTextMuted,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
            )
          }
        }

        // Поля ввода API Key и Secret Key
        item {
          HudCard(
            modifier = Modifier.fillMaxWidth(),
            title = "API КЛЮЧИ (KEYSTORE ЗАЩИТА)",
            icon = Icons.Outlined.VpnKey
          ) {
            // API Key
            OutlinedTextField(
              value = apiKey,
              onValueChange = { apiKey = it },
              label = { Text("API Key", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
              placeholder = { Text("Публичный ключ Testnet API", color = HudTextMuted) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_input"),
              visualTransformation = if (obscureApiKey) PasswordVisualTransformation() else VisualTransformation.None,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(
                  onClick = { obscureApiKey = !obscureApiKey },
                  modifier = Modifier.testTag("toggle_api_key_visibility")
                ) {
                  Icon(
                    if (obscureApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = "Toggle API Key",
                    tint = HudCyan
                  )
                }
              },
              colors = hudTextFieldColors(),
              shape = RoundedCornerShape(6.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Secret Key
            OutlinedTextField(
              value = secretKey,
              onValueChange = { secretKey = it },
              label = { Text("Secret Key", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
              placeholder = { Text("Секретный ключ для HMAC SHA256", color = HudTextMuted) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("secret_key_input"),
              visualTransformation = if (obscureSecretKey) PasswordVisualTransformation() else VisualTransformation.None,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(
                  onClick = { obscureSecretKey = !obscureSecretKey },
                  modifier = Modifier.testTag("toggle_secret_key_visibility")
                ) {
                  Icon(
                    if (obscureSecretKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = "Toggle Secret Key",
                    tint = HudCyan
                  )
                }
              },
              colors = hudTextFieldColors(),
              shape = RoundedCornerShape(6.dp)
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Security, contentDescription = null, tint = HudGreen, modifier = Modifier.size(14.dp))
              Spacer(Modifier.width(6.dp))
              Text(
                "Ключи хранятся в зашифрованном виде (Android KeyStore AES/GCM).",
                color = HudTextMuted,
                fontSize = 11.sp
              )
            }
          }
        }

        // Кнопки действий: Проверить и Сохранить
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            // Проверить подключение
            OutlinedButton(
              onClick = {
                if (apiKey.isBlank() || secretKey.isBlank()) {
                  scope.launch { snackbarHostState.showSnackbar("Заполните API Key и Secret Key перед проверкой") }
                  return@OutlinedButton
                }
                scope.launch {
                  isLoading = true
                  loadingMessage = "PING & SIGNATURE VERIFY..."
                  connectionStatus = ConnectionStatus.Checking
                  val pingOk = authService.ping(isTestnet)
                  if (!pingOk) {
                    connectionStatus = ConnectionStatus.Error("Сервер Binance Testnet недоступен (Ping failed).")
                    isLoading = false
                    return@launch
                  }
                  try {
                    val info = authService.getAccountInfo(apiKey, secretKey, isTestnet)
                    accountInfo = info
                    lastUpdatedTime = System.currentTimeMillis()
                    connectionStatus = ConnectionStatus.Success(info)
                    snackbarHostState.showSnackbar("✅ Успешное подключение к Binance Testnet!")
                  } catch (e: Exception) {
                    val msg = e.message ?: "Ошибка проверки ключей"
                    connectionStatus = ConnectionStatus.Error(msg)
                    snackbarHostState.showSnackbar("❌ $msg")
                  } finally {
                    isLoading = false
                  }
                }
              },
              modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("check_connection_button"),
              shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
              border = androidx.compose.foundation.BorderStroke(1.5.dp, HudCyan),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = HudCyan)
            ) {
              Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("ПРОВЕРИТЬ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            // Сохранить ключи
            Button(
              onClick = {
                if (apiKey.isBlank() || secretKey.isBlank()) {
                  scope.launch { snackbarHostState.showSnackbar("Заполните API Key и Secret Key") }
                  return@Button
                }
                scope.launch {
                  isLoading = true
                  loadingMessage = "ENCRYPTING & VALIDATING..."
                  try {
                    val info = authService.getAccountInfo(apiKey, secretKey, isTestnet)
                    val creds = ApiCredentials(
                      profileName = profileName.ifBlank { "Spot Testnet Master" },
                      apiKey = apiKey.trim(),
                      secretKey = secretKey.trim(),
                      isTestnet = isTestnet,
                    )
                    storageService.saveCredentials(creds)
                    accountInfo = info
                    lastUpdatedTime = System.currentTimeMillis()
                    connectionStatus = ConnectionStatus.Success(info)
                    snackbarHostState.showSnackbar("Ключи проверены и сохранены в Android KeyStore!")
                  } catch (e: Exception) {
                    val msg = e.message ?: "Ошибка валидации ключей"
                    connectionStatus = ConnectionStatus.Error(msg)
                    snackbarHostState.showSnackbar("Ошибка валидации: $msg")
                  } finally {
                    isLoading = false
                  }
                }
              },
              modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("save_keys_button"),
              shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = HudCyan,
                contentColor = HudNavyDark
              )
            ) {
              Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("СОХРАНИТЬ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }

        // Баннер статуса последней проверки
        item {
          AnimatedVisibility(visible = connectionStatus !is ConnectionStatus.Idle) {
            when (val status = connectionStatus) {
              is ConnectionStatus.Success -> {
                StatusBanner(
                  isSuccess = true,
                  title = "✅ ПОДКЛЮЧЕНО К BINANCE",
                  description = "Сервер доступен (Ping OK). Права API и HMAC SHA256 подпись валидированы.",
                  timestamp = status.timestamp
                )
              }
              is ConnectionStatus.Error -> {
                StatusBanner(
                  isSuccess = false,
                  title = "❌ ОШИБКА ПОДКЛЮЧЕНИЯ",
                  description = status.message,
                  timestamp = status.timestamp
                )
              }
              is ConnectionStatus.Checking -> {
                StatusBanner(
                  isSuccess = true,
                  title = "📡 ПРОВЕРКА ПОДКЛЮЧЕНИЯ...",
                  description = "Выполняется ping и подписанный запрос к /api/v3/account",
                  timestamp = System.currentTimeMillis()
                )
              }
              else -> {}
            }
          }
        }

        // Карточка информации об аккаунте (после успешного подключения)
        item {
          if (accountInfo != null) {
            AccountInfoCard(info = accountInfo!!, lastUpdated = lastUpdatedTime)
          }
        }

        // Зона сброса
        item {
          HudCard(
            modifier = Modifier.fillMaxWidth(),
            title = "ОПЕРАЦИИ ОЧИСТКИ (DANGER ZONE)",
            icon = Icons.Outlined.WarningAmber,
            borderColor = Color(0x66FF5252)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              OutlinedButton(
                onClick = { showResetKeysDialog = true },
                modifier = Modifier
                  .weight(1f)
                  .testTag("reset_keys_button"),
                shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HudRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HudRed)
              ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("СБРОСИТЬ КЛЮЧИ", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }

              Button(
                onClick = { showResetAllDialog = true },
                modifier = Modifier
                  .weight(1f)
                  .testTag("reset_all_button"),
                shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HudRed, contentColor = Color.White)
              ) {
                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("СБРОСИТЬ ВСЁ", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
              }
            }
          }
        }
      }

      // Оверлей загрузки
      if (isLoading) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
          contentAlignment = Alignment.Center
        ) {
          HudCard(
            title = "PROCESSING",
            icon = Icons.Outlined.Memory,
            modifier = Modifier.padding(24.dp)
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(16.dp)
            ) {
              CircularProgressIndicator(color = HudCyan, strokeWidth = 3.dp)
              Spacer(Modifier.height(16.dp))
              Text(
                loadingMessage,
                color = HudCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
              )
            }
          }
        }
      }
    }
  }

  // Диалог сброса ключей
  if (showResetKeysDialog) {
    HudAlertDialog(
      title = "СБРОС КЛЮЧЕЙ ПРОФИЛЯ",
      message = "Удалить сохраненные API Key и Secret Key для текущего профиля из защищенного хранилища?",
      confirmButtonText = "УДАЛИТЬ",
      confirmButtonColor = HudRed,
      onDismiss = { showResetKeysDialog = false },
      onConfirm = {
        showResetKeysDialog = false
        storageService.deleteCredentials()
        apiKey = ""
        secretKey = ""
        connectionStatus = ConnectionStatus.Idle
        accountInfo = null
        scope.launch { snackbarHostState.showSnackbar("API-ключи профиля успешно удалены") }
      }
    )
  }

  // Диалог полного сброса
  if (showResetAllDialog) {
    HudAlertDialog(
      title = "ПОЛНЫЙ СБРОС СИСТЕМЫ (PURGE)",
      message = "ВНИМАНИЕ! Это действие сотрет ВСЕ профили ключей, торговые настройки стратегий и локальную историю. Действие необратимо.",
      confirmButtonText = "СБРОСИТЬ ВСЁ",
      confirmButtonColor = HudRed,
      onDismiss = { showResetAllDialog = false },
      onConfirm = {
        showResetAllDialog = false
        storageService.clearAllData()
        profileName = "Spot Testnet Master"
        apiKey = ""
        secretKey = ""
        isTestnet = true
        connectionStatus = ConnectionStatus.Idle
        accountInfo = null
        scope.launch { snackbarHostState.showSnackbar("Система полностью очищена: ключи, настройки, история стёрты") }
      }
    )
  }
}

@Composable
fun HudHeader(onBack: () -> Unit = {}) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0x990A1628), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
      .border(1.dp, Color(0x4400D4FF), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
      .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(
      onClick = onBack,
      modifier = Modifier.size(38.dp).testTag("back_to_dashboard_button")
    ) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .background(Color(0x2600D4FF), CutCornerShape(4.dp))
          .border(1.2.dp, HudCyan, CutCornerShape(4.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад в Dashboard", tint = HudCyan, modifier = Modifier.size(20.dp))
      }
    }
    Spacer(Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        "BINANCE TESTNET TRADER",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp
      )
      Text(
        "SECURE HUD TERMINAL // SPOT API",
        color = HudTextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
      )
    }
    Box(
      modifier = Modifier
        .background(Color(0x26FF8A65), RoundedCornerShape(4.dp))
        .border(1.dp, HudPeach, RoundedCornerShape(4.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
      Text(
        "TESTNET",
        color = HudPeach,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
      )
    }
  }
}

@Composable
fun HudCard(
  modifier: Modifier = Modifier,
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  borderColor: Color = Color(0x4400D4FF),
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier = modifier
      .background(Color(0xEE0A182C), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
      .border(1.2.dp, borderColor, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
      .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = HudCyan, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        title,
        color = HudCyan,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp
      )
    }
    Spacer(Modifier.height(12.dp))
    content()
  }
}

@Composable
fun StatusBanner(
  isSuccess: Boolean,
  title: String,
  description: String,
  timestamp: Long
) {
  val accentColor = if (isSuccess) HudGreen else HudRed
  val timeStr = remember(timestamp) {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(accentColor.copy(alpha = 0.12f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
      .border(1.2.dp, accentColor, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
      .padding(14.dp)
      .testTag("connection_status_banner")
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        title,
        color = accentColor,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace
      )
      Text(timeStr, color = HudTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(4.dp))
    Text(
      description,
      color = Color.White.copy(alpha = 0.9f),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace
    )
  }
}

@Composable
fun AccountInfoCard(info: BinanceAccountInfo, lastUpdated: Long?) {
  val balances = info.nonZeroBalances
  val updateTimeStr = remember(lastUpdated) {
    if (lastUpdated != null) {
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastUpdated))
    } else ""
  }

  HudCard(
    modifier = Modifier.fillMaxWidth().testTag("account_info_card"),
    title = "ДАННЫЕ АККАУНТА (HUD REPORT)",
    icon = Icons.Outlined.AccountBalanceWallet
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        "Тип: ${info.accountType}",
        color = HudCyan,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PermissionChip("Trade", info.canTrade)
        PermissionChip("Withdraw", info.canWithdraw)
        PermissionChip("Deposit", info.canDeposit)
      }
    }

    Spacer(Modifier.height(14.dp))

    // Комиссии
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0x2600D4FF), RoundedCornerShape(6.dp))
        .border(1.dp, Color(0x3300D4FF), RoundedCornerShape(6.dp))
        .padding(8.dp),
      horizontalArrangement = Arrangement.SpaceAround
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MAKER FEE", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
          "%.2f%%".format(info.makerCommission / 100.0),
          color = HudPeach,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
          fontFamily = FontFamily.Monospace
        )
      }
      Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0x3300D4FF)))
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TAKER FEE", color = HudTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
          "%.2f%%".format(info.takerCommission / 100.0),
          color = HudPeach,
          fontWeight = FontWeight.Bold,
          fontSize = 15.sp,
          fontFamily = FontFamily.Monospace
        )
      }
    }

    Spacer(Modifier.height(14.dp))

    // Балансы
    Text(
      "НЕНУЛЕВЫЕ БАЛАНСЫ (${balances.size})",
      color = HudTextMuted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))

    if (balances.isEmpty()) {
      Text(
        "Все балансы равны нулю.",
        color = HudTextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp)
      )
    } else {
      balances.forEach { b ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .background(Color(0x3300D4FF), RoundedCornerShape(4.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              b.asset,
              color = HudCyan,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
            )
          }

          Column(horizontalAlignment = Alignment.End) {
            Text(
              "%.6f".format(b.free),
              color = HudPeach,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace
            )
            if (b.locked > 0) {
              Text(
                "Locked: %.4f".format(b.locked),
                color = HudTextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
        HorizontalDivider(color = Color(0x1A00D4FF), thickness = 1.dp)
      }
    }

    if (updateTimeStr.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text(
        "Обновлено: $updateTimeStr",
        color = HudTextMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.align(Alignment.End)
      )
    }
  }
}

@Composable
fun PermissionChip(label: String, isGranted: Boolean) {
  val color = if (isGranted) HudGreen else HudRed
  Box(
    modifier = Modifier
      .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
      .border(0.8.dp, color, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      label,
      color = color,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
fun HudAlertDialog(
  title: String,
  message: String,
  confirmButtonText: String,
  confirmButtonColor: Color,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF0D2340),
    title = {
      Text(
        title,
        color = confirmButtonColor,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
      )
    },
    text = {
      Text(
        message,
        color = Color(0xFFB0C4DE),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp
      )
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = ButtonDefaults.buttonColors(containerColor = confirmButtonColor)
      ) {
        Text(confirmButtonText, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("ОТМЕНА", color = HudTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
    }
  )
}

@Composable
fun hudTextFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedTextColor = Color.White,
  unfocusedTextColor = Color.White,
  focusedBorderColor = HudCyan,
  unfocusedBorderColor = Color(0x4400D4FF),
  focusedLabelColor = HudCyan,
  unfocusedLabelColor = HudTextMuted,
  focusedContainerColor = Color(0xFF071220),
  unfocusedContainerColor = Color(0xFF071220),
)
