package app.vault.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint / face unlock as an alternative to typing the PIN. */
object Biometrics {
    private const val KINDS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(a: FragmentActivity) = BiometricManager.from(a).canAuthenticate(KINDS) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(a: FragmentActivity, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock").setNegativeButtonText("Use PIN").setAllowedAuthenticators(KINDS).build()
        )
    }
}
