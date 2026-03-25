package net.muratov.intercom.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ReloadingPreviewImage(
    url: String,
    headers: Map<String, String>,
    reloadPeriodMs: Long?,
    modifier: Modifier = Modifier,
) {
    val bitmapState = produceState<Bitmap?>(initialValue = null, url, headers, reloadPeriodMs) {
        do {
            value = loadBitmap(url, headers)
            val period = reloadPeriodMs
            if (period == null || period <= 0L) break
            delay(period)
        } while (true)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        bitmapState.value?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun loadBitmap(
    url: String,
    headers: Map<String, String>,
): Bitmap? = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
        connection.inputStream.use { input ->
            BitmapFactory.decodeStream(input)
        }
    } finally {
        connection.disconnect()
    }
}
