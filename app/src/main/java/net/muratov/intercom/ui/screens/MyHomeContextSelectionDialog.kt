package net.muratov.intercom.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.muratov.intercom.provider.myhome.MyHomeContextSelectionPrompt
import net.muratov.intercom.provider.myhome.MyHomeLoginContext

@Composable
fun MyHomeContextSelectionDialog(
    prompt: MyHomeContextSelectionPrompt,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (MyHomeLoginContext) -> Unit,
) {
    var selectedKey by rememberSaveable(prompt.contexts) {
        mutableStateOf(prompt.contexts.firstOrNull()?.selectionKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбор адреса") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.message)
                prompt.contexts.forEach { context ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedKey == context.selectionKey,
                                onClick = { selectedKey = context.selectionKey },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedKey == context.selectionKey,
                            onClick = { selectedKey = context.selectionKey },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(context.address.ifBlank { "Адрес не указан" })
                            Text("placeId=${context.placeId}, profileId=${context.profileId}")
                        }
                    }
                }
                if (message.isNotBlank()) {
                    Text(message)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prompt.contexts.firstOrNull { it.selectionKey == selectedKey }?.let(onConfirm)
                },
                enabled = selectedKey != null,
            ) {
                Text("Продолжить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

private val MyHomeLoginContext.selectionKey: String
    get() = "${operatorId}_${accountId}_${placeId}_${profileId}"
