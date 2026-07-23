package com.cardrw.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * K3 — verrou optionnel du coffre (biométrie / PIN appareil).
 *
 * OFF par défaut (atelier). ON → [KeyVaultRepository.material] exige une session
 * déverrouillée (timeout [UNLOCK_TTL_MS] après prompt réussi).
 */
@Singleton
class VaultLockController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var unlockedUntilEpochMs: Long = 0L

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()
            if (!value) {
                // Désactiver le verrou = rester « ouvert »
                unlockedUntilEpochMs = Long.MAX_VALUE
            } else {
                // Réactiver = re-verrouiller tout de suite
                unlockedUntilEpochMs = 0L
            }
        }

    fun isUnlocked(): Boolean {
        if (!lockEnabled) return true
        return System.currentTimeMillis() < unlockedUntilEpochMs
    }

    fun markUnlocked(ttlMs: Long = UNLOCK_TTL_MS) {
        unlockedUntilEpochMs = System.currentTimeMillis() + ttlMs
    }

    fun lockNow() {
        unlockedUntilEpochMs = 0L
    }

    fun requireUnlocked() {
        if (!isUnlocked()) {
            throw VaultLockedException()
        }
    }

    companion object {
        private const val PREFS = "cardrw_vault_lock"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        /** Session déverrouillée 5 min après biométrie / PIN. */
        const val UNLOCK_TTL_MS: Long = 5 * 60 * 1000L
    }
}

class VaultLockedException :
    IllegalStateException("Coffre verrouillé — déverrouille avec biométrie / PIN appareil.")
