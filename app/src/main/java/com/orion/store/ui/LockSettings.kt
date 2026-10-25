package com.orion.store.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.orion.store.R
import com.orion.store.data.Settings

@Composable
fun LockSettings(vm: MainViewModel, settings: Settings) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Section(stringResource(R.string.lock_settings)) {
        Toggle(Sym.Lock, stringResource(R.string.lock_enable), stringResource(R.string.lock_enable_desc), settings.lockEnabled) {
            if (settings.lockEnabled) vm.set { it.copy(lockEnabled = false) }
            else if (AppLock.hasPin(ctx)) vm.set { it.copy(lockEnabled = true) } else { first = ""; confirmation = ""; error = false; dialog = true }
        }
        if (settings.lockEnabled || AppLock.hasPin(ctx)) {
            SRow(Sym.Lock, stringResource(R.string.lock_change_pin), stringResource(R.string.lock_change_pin_desc), onClick = { first = ""; confirmation = ""; error = false; dialog = true }) { Chevron() }
            SRow(Sym.Lock, stringResource(R.string.lock_method), if (settings.lockMethod == "biometric") stringResource(R.string.lock_method_biometric) else stringResource(R.string.lock_method_pin), onClick = {
                val next = if (settings.lockMethod == "biometric") "pin" else "biometric"; vm.set { it.copy(lockMethod = next) }
            }) { Chevron() }
            SRow(Sym.Timer, stringResource(R.string.lock_policy), when (settings.lockPolicy) {
                "screen_off" -> stringResource(R.string.lock_policy_screen)
                "leave_app" -> stringResource(R.string.lock_policy_leave)
                "timeout" -> stringResource(R.string.lock_policy_timeout, settings.lockTimeoutMinutes)
                else -> stringResource(R.string.lock_policy_close)
            }, onClick = {
                val choices = listOf("app_close", "screen_off", "leave_app", "timeout"); val next = choices[(choices.indexOf(settings.lockPolicy) + 1) % choices.size]; vm.set { it.copy(lockPolicy = next) }
            }) { Chevron() }
            if (settings.lockPolicy == "timeout") {
                SRow(Sym.Add, stringResource(R.string.lock_timeout), stringResource(R.string.lock_timeout_desc), onClick = { vm.set { it.copy(lockTimeoutMinutes = if (it.lockTimeoutMinutes >= 60) 1 else it.lockTimeoutMinutes + 1) } }) { Text(stringResource(R.string.lock_minutes, settings.lockTimeoutMinutes)) }
            }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(stringResource(R.string.lock_set_pin)) }, text = {
        androidx.compose.foundation.layout.Column {
            OutlinedTextField(first, { first = it.filter(Char::isDigit).take(12); error = false }, label = { Text(stringResource(R.string.lock_new_pin)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(12); error = false }, label = { Text(stringResource(R.string.lock_confirm_pin)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            if (error) Text(stringResource(R.string.lock_pin_mismatch), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = first.length >= 4 && confirmation.isNotEmpty(), onClick = {
        if (first != confirmation) error = true else { AppLock.setPin(ctx, first.toCharArray()); vm.set { it.copy(lockEnabled = true, lockMethod = "pin") }; dialog = false }
    }) { Text(stringResource(R.string.lock_save_pin)) } }, dismissButton = { TextButton({ dialog = false }) { Text(stringResource(R.string.lock_cancel)) } })
}
