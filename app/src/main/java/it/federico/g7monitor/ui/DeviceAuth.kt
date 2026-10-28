package it.federico.g7monitor.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Asks for the phone's screen lock (PIN, pattern, password) or biometrics before a
 * safety-relevant change, such as turning an alarm off. Framework API, no extra library.
 */
object DeviceAuth {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** @param onResult true when confirmed; otherwise an error to show (null if the user just cancelled). */
    fun confirm(activity: Activity, title: String, subtitle: String, onResult: (ok: Boolean, error: String?) -> Unit) {
        val available = activity.getSystemService(BiometricManager::class.java).canAuthenticate(AUTHENTICATORS)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false, "Per disattivare un allarme il telefono deve avere un blocco schermo (PIN, sequenza, password o impronta).")
            return
        }
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(true)
            .build()
            .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onResult(false, if (cancelled) null else errString.toString())
                }
            })
    }
}
