package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.model.ApiCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorageService(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences("binance_hud_secure_prefs", Context.MODE_PRIVATE)

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "binance_hud_aes_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private const val PREF_PROFILE_NAME = "pref_profile_name"
    private const val PREF_API_KEY_ENC = "pref_api_key_enc"
    private const val PREF_API_KEY_IV = "pref_api_key_iv"
    private const val PREF_SECRET_KEY_ENC = "pref_secret_key_enc"
    private const val PREF_SECRET_KEY_IV = "pref_secret_key_iv"
    private const val PREF_IS_TESTNET = "pref_is_testnet"
  }

  init {
    getOrCreateSecretKey()
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (!keyStore.containsAlias(KEY_ALIAS)) {
      val keyGenerator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      val parameterSpec =
        KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
      keyGenerator.init(parameterSpec)
      return keyGenerator.generateKey()
    }
    return keyStore.getKey(KEY_ALIAS, null) as SecretKey
  }

  private fun encrypt(plainText: String): Pair<String, String> {
    if (plainText.isEmpty()) return Pair("", "")
    val secretKey = getOrCreateSecretKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val iv = cipher.iv
    val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    return Pair(
      Base64.encodeToString(encrypted, Base64.NO_WRAP),
      Base64.encodeToString(iv, Base64.NO_WRAP),
    )
  }

  private fun decrypt(encryptedBase64: String, ivBase64: String): String {
    if (encryptedBase64.isEmpty() || ivBase64.isEmpty()) return ""
    return try {
      val secretKey = getOrCreateSecretKey()
      val cipher = Cipher.getInstance(TRANSFORMATION)
      val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
      val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
      val decryptedBytes =
        cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
      String(decryptedBytes, Charsets.UTF_8)
    } catch (_: Exception) {
      ""
    }
  }

  fun saveCredentials(credentials: ApiCredentials) {
    val (apiKeyEnc, apiKeyIv) = encrypt(credentials.apiKey)
    val (secretKeyEnc, secretKeyIv) = encrypt(credentials.secretKey)

    prefs.edit()
      .putString(PREF_PROFILE_NAME, credentials.profileName)
      .putString(PREF_API_KEY_ENC, apiKeyEnc)
      .putString(PREF_API_KEY_IV, apiKeyIv)
      .putString(PREF_SECRET_KEY_ENC, secretKeyEnc)
      .putString(PREF_SECRET_KEY_IV, secretKeyIv)
      .putBoolean(PREF_IS_TESTNET, credentials.isTestnet)
      .apply()
  }

  fun getCredentials(): ApiCredentials? {
    val profileName = prefs.getString(PREF_PROFILE_NAME, null) ?: return null
    val apiKeyEnc = prefs.getString(PREF_API_KEY_ENC, "") ?: ""
    val apiKeyIv = prefs.getString(PREF_API_KEY_IV, "") ?: ""
    val secretKeyEnc = prefs.getString(PREF_SECRET_KEY_ENC, "") ?: ""
    val secretKeyIv = prefs.getString(PREF_SECRET_KEY_IV, "") ?: ""
    val isTestnet = prefs.getBoolean(PREF_IS_TESTNET, true)

    val apiKey = decrypt(apiKeyEnc, apiKeyIv)
    val secretKey = decrypt(secretKeyEnc, secretKeyIv)

    if (apiKey.isEmpty() && secretKey.isEmpty()) return null

    return ApiCredentials(
      profileName = profileName,
      apiKey = apiKey,
      secretKey = secretKey,
      isTestnet = isTestnet,
    )
  }

  fun deleteCredentials() {
    prefs.edit()
      .remove(PREF_PROFILE_NAME)
      .remove(PREF_API_KEY_ENC)
      .remove(PREF_API_KEY_IV)
      .remove(PREF_SECRET_KEY_ENC)
      .remove(PREF_SECRET_KEY_IV)
      .apply()
  }

  fun clearAllData() {
    prefs.edit().clear().apply()
  }
}
