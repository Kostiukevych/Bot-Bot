package com.example

import android.app.Application
import com.example.service.BacktestEngine
import com.example.service.GridBotEngine
import com.example.service.TradingBotEngine

class MyApplication : Application() {

  val botEngine: TradingBotEngine by lazy {
    TradingBotEngine(this)
  }

  val gridBotEngine: GridBotEngine by lazy {
    GridBotEngine(this)
  }

  val backtestEngine: BacktestEngine by lazy {
    BacktestEngine()
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  companion object {
    lateinit var instance: MyApplication
      private set
  }
}
