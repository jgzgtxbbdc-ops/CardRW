package com.cardrw.app.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secrets at-rest via AES-GCM (MasterKey Android Keystore) — CDC §6.2 / coffre K1.
 * Alias → blob Base64 dans EncryptedSharedPreferences (fichier exclu du backup app).
 */
@Singleton
class EncryptedPrefsSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecretStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun put(alias: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(plaintext, Base64.NO_WRAP)
        prefs.edit().putString(alias, encoded).apply()
        // Wipe local copy intent: caller owns plaintext
    }

    override suspend fun get(alias: String): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(alias, null) ?: return@withContext null
        Base64.decode(encoded, Base64.NO_WRAP)
    }

    override suspend fun delete(alias: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(alias).apply()
    }

    override suspend fun contains(alias: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(alias)
    }

    override suspend fun listAliases(): List<String> = withContext(Dispatchers.IO) {
        prefs.all.keys.filterNotNull().sorted()
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "cardrw_secrets"
    }
}
