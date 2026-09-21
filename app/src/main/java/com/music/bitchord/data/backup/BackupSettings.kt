package com.music.bitchord.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Optional local-first backup configuration. Empty URL/token means disabled. */
object BackupSettings {
    val serverUrl = MutableStateFlow("")
    val token = MutableStateFlow("")
    val deviceName = MutableStateFlow("")
    val enabled = MutableStateFlow(false)
    val autoSync = MutableStateFlow(true)
    val deviceId = MutableStateFlow("")

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "backup_settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        serverUrl.value = prefs.getString(KEY_URL, "").orEmpty().trimEnd('/')
        token.value = prefs.getString(KEY_TOKEN, "").orEmpty()
        deviceName.value = prefs.getString(KEY_DEVICE, "").orEmpty()
            .ifBlank { android.os.Build.MODEL }
        enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        autoSync.value = prefs.getBoolean(KEY_AUTO_SYNC, true)
        deviceId.value = prefs.getString(KEY_DEVICE_ID, null).orEmpty().ifBlank {
            UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        }
    }

    fun setServerUrl(value: String) = update(KEY_URL, value.trim().trimEnd('/')) {
        serverUrl.value = value.trim().trimEnd('/')
    }

    fun setToken(value: String) = update(KEY_TOKEN, value.trim()) { token.value = value.trim() }
    fun setDeviceName(value: String) = update(KEY_DEVICE, value.trim()) { deviceName.value = value.trim() }
    fun setEnabled(value: Boolean) = update(KEY_ENABLED, value) { enabled.value = value }
    fun setAutoSync(value: Boolean) = update(KEY_AUTO_SYNC, value) { autoSync.value = value }

    val configured: Boolean
        get() = serverUrl.value.isNotBlank() && token.value.isNotBlank() && enabled.value

    private fun <T> update(key: String, value: T, state: () -> Unit) {
        if (!this::prefs.isInitialized) return
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
        state()
    }

    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE = "device_name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_DEVICE_ID = "device_id"
}
