package com.example.service

import com.example.model.AssetBalance
import com.example.model.BinanceAccountInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceAuthService(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .build()
) {
  companion object {
    const val TESTNET_BASE_URL = "https://testnet.binance.vision"
    const val MAINNET_BASE_URL = "https://api.binance.com"
  }

  private fun hmacSha256(data: String, key: String): String {
    val sha256Hmac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
    sha256Hmac.init(secretKey)
    val signedBytes = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
    return signedBytes.joinToString("") { "%02x".format(it) }
  }

  suspend fun ping(isTestnet: Boolean = true): Boolean = withContext(Dispatchers.IO) {
    val baseUrl = if (isTestnet) TESTNET_BASE_URL else MAINNET_BASE_URL
    val request = Request.Builder()
      .url("$baseUrl/api/v3/ping")
      .get()
      .build()

    try {
      client.newCall(request).execute().use { response ->
        response.isSuccessful
      }
    } catch (_: Exception) {
      false
    }
  }

  suspend fun getAccountInfo(
    apiKey: String,
    secretKey: String,
    isTestnet: Boolean = true,
  ): BinanceAccountInfo = withContext(Dispatchers.IO) {
    if (!isTestnet) {
      throw IllegalStateException("Mainnet физически заблокирован для безопасности.")
    }
    if (apiKey.isBlank() || secretKey.isBlank()) {
      throw IllegalArgumentException("API Key и Secret Key не могут быть пустыми.")
    }

    val baseUrl = TESTNET_BASE_URL
    val timestamp = System.currentTimeMillis()
    val queryString = "timestamp=$timestamp&recvWindow=5000"
    val signature = hmacSha256(queryString, secretKey.trim())
    val fullUrl = "$baseUrl/api/v3/account?$queryString&signature=$signature"

    val request = Request.Builder()
      .url(fullUrl)
      .header("X-MBX-APIKEY", apiKey.trim())
      .header("Accept", "application/json")
      .get()
      .build()

    client.newCall(request).execute().use { response ->
      val bodyString = response.body?.string().orEmpty()
      if (response.isSuccessful) {
        val root = JSONObject(bodyString)
        val balancesArray = root.optJSONArray("balances")
        val balances = mutableListOf<AssetBalance>()
        if (balancesArray != null) {
          for (i in 0 until balancesArray.length()) {
            val item = balancesArray.getJSONObject(i)
            balances.add(
              AssetBalance(
                asset = item.optString("asset", ""),
                free = item.optString("free", "0").toDoubleOrNull() ?: 0.0,
                locked = item.optString("locked", "0").toDoubleOrNull() ?: 0.0,
              )
            )
          }
        }

        val permissionsArray = root.optJSONArray("permissions")
        val permissions = mutableListOf<String>()
        if (permissionsArray != null) {
          for (i in 0 until permissionsArray.length()) {
            permissions.add(permissionsArray.getString(i))
          }
        }

        BinanceAccountInfo(
          makerCommission = root.optInt("makerCommission", 0),
          takerCommission = root.optInt("takerCommission", 0),
          canTrade = root.optBoolean("canTrade", false),
          canWithdraw = root.optBoolean("canWithdraw", false),
          canDeposit = root.optBoolean("canDeposit", false),
          accountType = root.optString("accountType", "SPOT"),
          updateTime = root.optLong("updateTime", System.currentTimeMillis()),
          balances = balances,
          permissions = permissions,
        )
      } else {
        val errorMessage = try {
          val errorJson = JSONObject(bodyString)
          val code = errorJson.optInt("code")
          val msg = errorJson.optString("msg")
          if (msg.isNotEmpty()) "[$code] $msg" else bodyString
        } catch (_: Exception) {
          bodyString.ifEmpty { "HTTP ${response.code}" }
        }
        throw Exception(errorMessage)
      }
    }
  }
}
