package net.muratov.intercom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.muratov.intercom.data.model.SipAccountState
import net.muratov.intercom.data.model.SipRegistrationStatus

@Composable
fun SipAccountsStrip(
    accounts: List<SipAccountState>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF09111D).copy(alpha = 0.9f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "SIP registrations",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        accounts.forEach { account ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = account.config.title, color = Color(0xFFE8EEF9))
                Text(
                    text = account.status.name,
                    color = when (account.status) {
                        SipRegistrationStatus.Ok -> Color(0xFF8DE1B1)
                        SipRegistrationStatus.Progress -> Color(0xFFF5C15D)
                        SipRegistrationStatus.Failed -> Color(0xFFFF8E8E)
                        else -> Color(0xFF9DB0C7)
                    },
                )
            }
        }
    }
}
