package com.cnumed.mlms.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurePrefs @Inject constructor(@ApplicationContext context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "mlms_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(id: String, password: String) {
        prefs.edit()
            .putString(KEY_ID, id)
            .putString(KEY_PWD, password)
            .apply()
    }

    fun getId(): String? = prefs.getString(KEY_ID, null)

    fun getPassword(): String? = prefs.getString(KEY_PWD, null)

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_ID)
            .remove(KEY_PWD)
            .apply()
    }

    fun isAutoLoginEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOGIN, false)

    fun setAutoLogin(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply()
    }

    companion object {
        private const val KEY_ID = "user_id"
        private const val KEY_PWD = "user_pwd"
        private const val KEY_AUTO_LOGIN = "auto_login"
    }
}
