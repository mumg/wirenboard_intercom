package net.muratov.intercom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.video.RtspPlayer

@Composable
fun FullscreenStreamScreen(
    stream: RtspStream,
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
            url = stream.url,
            muted = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
