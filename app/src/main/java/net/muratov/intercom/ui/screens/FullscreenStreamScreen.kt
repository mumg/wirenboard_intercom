package net.muratov.intercom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.video.RtspPlayer

@Composable
fun FullscreenStreamScreen(
    stream: RtspStream,
    onOpen: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black, Color(0xFF02131D)),
                ),
            ),
    ) {
        RtspPlayer(
            url = stream.rtspUrl,
            playbackEngine = stream.playbackEngine,
            headers = stream.rtspExtras,
            muted = false,
            modifier = Modifier.fillMaxSize(),
        )

        if (onOpen != null && stream.openAction != null) {
            FloatingActionButton(
                onClick = onOpen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(28.dp),
                shape = CircleShape,
                containerColor = Color(0xFF2C8A5B),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Open",
                    tint = Color.White,
                )
            }
        }
    }
}
