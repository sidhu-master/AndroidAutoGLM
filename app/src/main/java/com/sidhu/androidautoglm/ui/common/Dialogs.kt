package com.sidhu.androidautoglm.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sidhu.androidautoglm.R

/**
 * Generic confirmation dialog with customizable title, message, and button labels.
 *
 * @param title The dialog title
 * @param message The dialog message content
 * @param confirmText Text for the confirm button (defaults to "确定")
 * @param dismissText Text for the dismiss button (defaults to "取消")
 * @param onConfirm Callback when confirm button is clicked
 * @param onDismiss Callback when dialog is dismissed
 * @param isDangerous If true, confirm button uses error color styling
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String? = null,
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDangerous: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (isDangerous) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                }
            ) {
                Text(confirmText ?: stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText ?: stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Generic input dialog with validation support.
 *
 * @param title The dialog title
 * @param initialValue The initial value for the input field
 * @param label Label for the input field
 * @param onConfirm Callback with the input value when confirmed
 * @param onDismiss Callback when dialog is dismissed
 * @param validator Function to validate input (defaults to not-blank check)
 */
@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validator: (String) -> Boolean = { it.isNotBlank() }
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                isError = text.isNotEmpty() && !validator(text),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (validator(text)) onConfirm(text) },
                enabled = validator(text)
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
