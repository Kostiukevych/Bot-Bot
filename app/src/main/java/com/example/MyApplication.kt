package com.example

import android.app.Application
import com.example.service.TradingBotEngine

class MyApplication : Application() {

  val botEngine: TradingBotEngine by lazy {
    TradingBotEngine(this)
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
