package com.orion.store.ui

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.orion.store.R
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PREFS = "orion_app_lock"
private const val HASH = "pin_hash"
private const val SALT = "pin_salt"

object AppLock {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun hasPin(context: Context) = prefs(context).contains(HASH) && prefs(context).contains(SALT)
    fun setPin(context: Context, pin: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        pin.fill('\u0000')
        prefs(context).edit().putString(HASH, Base64.encodeToString(hash, Base64.NO_WRAP)).putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
    }
    fun verify(context: Context, pin: CharArray): Boolean {
        val p = prefs(context); val salt = p.getString(SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = p.getString(HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val actual = derive(pin, salt); pin.fill('\u0000')
        return MessageDigest.isEqual(expected, actual)
    }
    fun clearPin(context: Context) = prefs(context).edit().remove(HASH).remove(SALT).apply()
    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun biometricAvailable(activity: FragmentActivity): Boolean = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}

@Composable
fun LockScreen(method: String, biometricAvailable: Boolean, onBiometric: () -> Unit, onUnlock: (CharArray) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(method, biometricAvailable) { if (method == "biometric" && biometricAvailable) onBiometric() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lock_subtitle), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        if (method == "pin" || !biometricAvailable) {
            OutlinedTextField(pin, { pin = it; error = false }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lock_pin)) }, singleLine = true,
                isError = error, supportingText = if (error) ({ Text(stringResource(R.string.lock_incorrect_pin)) }) else null,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { checking = true; error = !onUnlock(pin.toCharArray()); checking = false },
                enabled = pin.isNotEmpty() && !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checking) CircularProgressIndicator() else Text(stringResource(R.string.lock_unlock))
            }
        }
        if (method == "biometric" && biometricAvailable) TextButton(onBiometric) { Text(stringResource(R.string.lock_use_biometric)) }
    }
}

fun showBiometricPrompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val prompt = BiometricPrompt(activity, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
        override fun onAuthenticationFailed() { onResult(false) }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.lock_biometric_title)).setSubtitle(activity.getString(R.string.lock_biometric_subtitle)).setNegativeButtonText(activity.getString(R.string.lock_use_pin)).build())
}
