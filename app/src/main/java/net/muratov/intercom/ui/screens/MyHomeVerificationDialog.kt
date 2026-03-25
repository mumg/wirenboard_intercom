package net.muratov.intercom.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.muratov.intercom.provider.myhome.MyHomeVerificationPrompt

@Composable
fun MyHomeVerificationDialog(
    prompt: MyHomeVerificationPrompt,
    message: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтверждение входа") },
        text = {
            Column {
                Text(text = prompt.address)
                Text(
                    text = prompt.message,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    singleLine = true,
                    label = { Text("Код") },
                )
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(code.trim(), "") },
                enabled = code.isNotBlank(),
            ) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}
