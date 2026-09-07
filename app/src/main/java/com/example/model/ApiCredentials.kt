package com.example.model

data class ApiCredentials(
  val profileName: String,
  val apiKey: String,
  val secretKey: String,
  val isTestnet: Boolean = true,
  val updatedAt: Long = System.currentTimeMillis(),
)

data class AssetBalance(
  val asset: String,
  val free: Double,
  val locked: Double,
) {
  val total: Double get() = free + locked
  val isNonZero: Boolean get() = total > 0.00000001
}

data class BinanceAccountInfo(
  val makerCommission: Int,
  val takerCommission: Int,
  val canTrade: Boolean,
  val canWithdraw: Boolean,
  val canDeposit: Boolean,
  val accountType: String,
  val updateTime: Long,
  val balances: List<AssetBalance>,
  val permissions: List<String>,
) {
  val nonZeroBalances: List<AssetBalance>
    get() = balances.filter { it.isNonZero }.sortedByDescending { it.total }
}

sealed class ConnectionStatus {
  object Idle : ConnectionStatus()
  object Checking : ConnectionStatus()
  data class Success(val info: BinanceAccountInfo, val timestamp: Long = System.currentTimeMillis()) : ConnectionStatus()
  data class Error(val message: String, val timestamp: Long = System.currentTimeMillis()) : ConnectionStatus()
}
