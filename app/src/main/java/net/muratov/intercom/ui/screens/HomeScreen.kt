package net.muratov.intercom.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.muratov.intercom.browser.GeckoRuntimeHolder
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.video.RtspPlayer
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

@Composable
fun HomeScreen(
    webViewUrl: String,
    streams: List<RtspStream>,
    browserVisible: Boolean,
    onStreamSelected: (RtspStream) -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val tileColumnWidth = when {
        screenWidth > 1400.dp -> 272.dp
        screenWidth > 1000.dp -> 240.dp
        else -> 208.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0A1220), Color(0xFF132A3B), Color(0xFF102D25)),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.End))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GeckoPane(
                webViewUrl = webViewUrl,
                browserVisible = browserVisible,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            StreamsColumn(
                streams = streams,
                width = tileColumnWidth,
                onStreamSelected = onStreamSelected,
            )
        }
    }
}

@Composable
private fun GeckoPane(
    webViewUrl: String,
    browserVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF0D1624),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp)),
                factory = { context ->
                    GeckoBrowserView(context).apply {
                        bindUrl(webViewUrl)
                        setBrowserVisible(browserVisible)
                    }
                },
                update = { view ->
                    view.bindUrl(webViewUrl)
                    view.setBrowserVisible(browserVisible)
                },
            )
        }
    }
}

private class GeckoBrowserView(context: Context) : FrameLayout(context) {
    companion object {
        private const val RETRY_DELAY_MS = 15_000L
    }

    private val geckoView = GeckoView(context)
    private val geckoSession = GeckoSession()
    private val retryLoadRunnable = Runnable { retryLoadIfNeeded() }
    private var currentUrl: String? = null
    private var opened = false
    private var pageLoadedSuccessfully = false
    private var browserVisible = true

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(AndroidColor.WHITE)
        geckoView.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        geckoView.setBackgroundColor(AndroidColor.WHITE)
        addView(geckoView)

        geckoSession.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError,
                ) = null.also {
                    pageLoadedSuccessfully = false
                    scheduleRetry()
                }
            },
        )
        geckoSession.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    pageLoadedSuccessfully = success
                    if (success) {
                        cancelRetry()
                    } else {
                        scheduleRetry()
                    }
                }
            },
        )
        geckoSession.open(GeckoRuntimeHolder.getOrCreate(context))
        geckoView.setSession(geckoSession)
        opened = true
    }

    fun bindUrl(url: String) {
        val targetUrl = url.toBrowserUrl()
        if (currentUrl == targetUrl) return
        currentUrl = targetUrl
        pageLoadedSuccessfully = targetUrl == "about:blank"
        cancelRetry()
        geckoSession.loadUri(targetUrl)
        if (!pageLoadedSuccessfully) {
            scheduleRetry()
        }
    }

    fun setBrowserVisible(visible: Boolean) {
        browserVisible = visible
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        this.visibility = visibility
        geckoView.visibility = visibility
        if (visible && !pageLoadedSuccessfully && !currentUrl.isNullOrBlank()) {
            scheduleRetry()
        } else if (!visible) {
            cancelRetry()
        }
    }

    override fun onDetachedFromWindow() {
        cancelRetry()
        if (opened && geckoSession.isOpen) {
            geckoSession.close()
            opened = false
        }
        super.onDetachedFromWindow()
    }

    private fun retryLoadIfNeeded() {
        val targetUrl = currentUrl
        if (!opened || !browserVisible || pageLoadedSuccessfully || targetUrl.isNullOrBlank() || targetUrl == "about:blank") {
            return
        }
        geckoSession.loadUri(targetUrl)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!browserVisible || pageLoadedSuccessfully || currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
            return
        }
        removeCallbacks(retryLoadRunnable)
        postDelayed(retryLoadRunnable, RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        removeCallbacks(retryLoadRunnable)
    }
}

private fun String.toBrowserUrl(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return "about:blank"
    return if ("://" in trimmed) trimmed else "http://$trimmed"
}

@Composable
private fun StreamsColumn(
    streams: List<RtspStream>,
    width: Dp,
    onStreamSelected: (RtspStream) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 220.dp)
            .width(width),
    ) {
        val tileSpacing = 5.dp
        val visibleTileCount = 3
        val tileHeight = (maxHeight - (tileSpacing * (visibleTileCount - 1))) / visibleTileCount

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(tileSpacing),
        ) {
            items(streams, key = { it.id }) { stream ->
                StreamTile(
                    stream = stream,
                    tileHeight = tileHeight,
                    onClick = { onStreamSelected(stream) },
                )
            }
        }
    }
}

@Composable
private fun StreamTile(
    stream: RtspStream,
    tileHeight: Dp,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B).copy(alpha = 0.78f)),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tileHeight)
                    .clip(RoundedCornerShape(18.dp)),
            ) {
                when {
                    !stream.previewUrl.isNullOrBlank() -> {
                        ReloadingPreviewImage(
                            url = stream.previewUrl,
                            headers = stream.previewExtras,
                            reloadPeriodMs = stream.previewReloadPeriodMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        RtspPlayer(
                            url = stream.rtspUrl,
                            muted = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        )
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stream.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
