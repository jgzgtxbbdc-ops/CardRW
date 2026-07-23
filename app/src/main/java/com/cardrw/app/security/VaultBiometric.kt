package com.cardrw.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Prompt biométrie / credential appareil pour K3.
 * Utilise [BIOMETRIC_WEAK] | [DEVICE_CREDENTIAL] (PIN / schéma OK sans capteur).
 */
object VaultBiometric {

    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun canAuthenticate(activity: FragmentActivity): Int =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)

    fun isAvailable(activity: FragmentActivity): Boolean =
        canAuthenticate(activity) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (message: String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val status = canAuthenticate(activity)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            onError(
                when (status) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                        "Aucun PIN / empreinte configuré sur l’appareil."
                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                    -> "Biométrie / credential indisponible sur cet appareil."
                    else -> "Authentification appareil impossible (code $status)."
                },
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancel()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // Essai refusé — le prompt reste ouvert ; pas d’erreur fatale.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
