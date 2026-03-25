package net.muratov.intercom.ui.screens

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.voip.SipService

@Composable
fun ActiveCallOverlay(
    callSession: CallSession,
    sipService: SipService,
    onHangup: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val textureViewHolder = remember { arrayOfNulls<TextureView>(1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black, Color(0xFF0C1B2D)))),
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).also {
                    textureViewHolder[0] = it
                    sipService.bindRemoteVideo(it)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(sipService) {
            onDispose {
                textureViewHolder[0]?.let(sipService::unbindRemoteVideo)
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.4f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = callSession.remoteDisplayName.ifBlank { callSession.remoteAddress },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = "Active ${if (callSession.hasVideo) "video" else "audio"} call",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD2E0FF),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (onOpen != null && callSession.openAction != null) {
                FloatingActionButton(
                    onClick = onOpen,
                    shape = CircleShape,
                    containerColor = Color(0xFF2C8A5B),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Open", tint = Color.White)
                }
            }
            FloatingActionButton(
                onClick = onHangup,
                shape = CircleShape,
                containerColor = Color(0xFFC73A3A),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
            }
        }
    }
}
