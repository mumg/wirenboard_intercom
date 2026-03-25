package net.muratov.intercom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.muratov.intercom.provider.myhome.MyHomeAuthStatus
import net.muratov.intercom.provider.myhome.MyHomeProviderState

@Composable
fun ProptechRegistrationWizardScreen(
    providerState: MyHomeProviderState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
) {
    val statusText = when (providerState.status) {
        MyHomeAuthStatus.Disabled -> "Proptech не настроен. Проверьте секцию providers и phone в конфиге."
        MyHomeAuthStatus.Idle -> "Для продолжения нужно пройти регистрацию у провайдера Proptech."
        MyHomeAuthStatus.SelectingContext -> "Выберите адрес в открывшемся окне."
        MyHomeAuthStatus.RequestingCode -> "Отправляем код подтверждения."
        MyHomeAuthStatus.WaitingForCode -> "Введите код подтверждения в открывшемся окне."
        MyHomeAuthStatus.Authorizing -> "Проверяем код и получаем токен."
        MyHomeAuthStatus.Error -> providerState.message.ifBlank { "Не удалось пройти регистрацию Proptech." }
        MyHomeAuthStatus.Authorized -> "Авторизация завершена."
    }

    val showProgress = providerState.status in setOf(
        MyHomeAuthStatus.RequestingCode,
        MyHomeAuthStatus.WaitingForCode,
        MyHomeAuthStatus.Authorizing,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF08131F), Color(0xFF11283B), Color(0xFF0D2C25)),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF0D1624).copy(alpha = 0.94f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Регистрация Proptech",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Перед запуском главного экрана нужно получить доступ к данным домофона, SIP и камерам.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                )
                if (showProgress) {
                    CircularProgressIndicator(color = Color.White)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (providerState.message.isNotBlank() &&
                    providerState.status !in setOf(MyHomeAuthStatus.Error, MyHomeAuthStatus.Authorized)
                ) {
                    Text(
                        text = providerState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                when (providerState.status) {
                    MyHomeAuthStatus.Idle, MyHomeAuthStatus.Disabled -> {
                        Button(onClick = onStart) {
                            Text("Начать регистрацию")
                        }
                    }

                    MyHomeAuthStatus.Error -> {
                        Button(onClick = onRetry) {
                            Text("Повторить")
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}
