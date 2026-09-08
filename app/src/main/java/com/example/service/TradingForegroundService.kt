package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.MyApplication
import kotlinx.coroutines.*
import java.util.Locale

class TradingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "trading_bot_foreground_channel"
        const val NOTIFICATION_ID = 10101
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObservationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Инициализация алгоритмического бота...", "Запуск анализа рынка")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val botEngine = (application as MyApplication).botEngine
        botEngine.start()

        observeBotState(botEngine)

        return START_STICKY
    }

    private fun observeBotState(botEngine: TradingBotEngine) {
        stateObservationJob?.cancel()
        stateObservationJob = serviceScope.launch {
            botEngine.stateFlow.collect { state ->
                val notificationManager = getSystemService(NotificationManager::class.java) ?: return@collect
                val pairStr = state.selectedPair?.symbol ?: "BTCUSDT"
                val activePos = state.openPositions[pairStr]

                val title: String
                val body: String

                if (activePos != null) {
                    val curPrice = state.tickerData?.lastPrice ?: activePos.entryPrice
                    val pnlPct = ((curPrice - activePos.entryPrice) / activePos.entryPrice) * 100.0
                    val sign = if (pnlPct >= 0) "+" else ""
                    val pnlFormatted = "${sign}${"%.2f".format(Locale.US, pnlPct)}%"
                    val score = state.currentSignal?.score ?: activePos.score
                    title = "🟢 LONG $pairStr | PnL: $pnlFormatted"
                    body = "Вход: ${"%.2f".format(Locale.US, activePos.entryPrice)} | Тек: ${"%.2f".format(Locale.US, curPrice)} | Score: $score%"
                } else {
                    val score = state.currentSignal?.score ?: 0
                    val threshold = state.strategyConfig.minScoreThreshold
                    title = "⚡ Binance Testnet Bot: $pairStr"
                    body = "Поиск сигнала: Score $score% (Порог $threshold%) | Статус: ${state.botStatus.title}"
                }

                val notification = buildNotification(title, body)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trading Bot Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал фонового сервиса торгового терминала"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stateObservationJob?.cancel()
        serviceScope.cancel()
        val botEngine = (application as? MyApplication)?.botEngine
        botEngine?.stop()
        super.onDestroy()
    }
}
