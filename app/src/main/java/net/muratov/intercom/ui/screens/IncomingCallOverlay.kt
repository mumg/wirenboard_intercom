package net.muratov.intercom.ui.screens

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
fun IncomingCallOverlay(
    callSession: CallSession,
    sipService: SipService,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val textureViewHolder = remember { arrayOfNulls<TextureView>(1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black, Color(0xFF101928)),
                ),
            ),
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).also {
                    textureViewHolder[0] = it
                    it.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            sipService.bindRemoteVideo(it)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            sipService.unbindRemoteVideo(it)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    if (it.isAvailable) {
                        sipService.bindRemoteVideo(it)
                    }
                }
            },
            update = {
                textureViewHolder[0] = it
                if (it.isAvailable) {
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

        callSession.providerTitle?.takeIf { it.isNotBlank() }?.let { providerTitle ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.45f),
            ) {
                Text(
                    text = providerTitle,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        ) {
            FloatingActionButton(
                onClick = onReject,
                shape = CircleShape,
                containerColor = Color(0xFFC73A3A),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
            }
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
                onClick = onAccept,
                shape = CircleShape,
                containerColor = Color(0xFF2AAE5C),
            ) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
            }
        }
    }
}
