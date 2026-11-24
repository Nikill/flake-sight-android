package com.tanukee.flakesight

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnapshotFreshness
import com.tanukee.flakesight.core.SnapshotSize
import com.tanukee.flakesight.core.Webcam
import com.tanukee.flakesight.core.WebcamError
import com.tanukee.flakesight.core.WebcamService
import com.tanukee.flakesight.core.WebcamSnapshot
import com.tanukee.flakesight.core.sortedByFreshness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One per process, so its memory cache and throttle outlive the screens. */
private val webcamService = WebcamService()

/** Longest side, in pixels, of a card image: `ImageDownsampler.thumbnailPixelSize` on iOS. */
private const val THUMBNAIL_PIXEL_SIZE = 1200

/** What a card shows: the last image loaded, and why the latest load failed if it did. */
private data class CardState(
    val snapshot: WebcamSnapshot? = null,
    val image: Bitmap? = null,
    val error: Throwable? = null,
) {
    val isSettled get() = image != null || error != null
}

/**
 * A resort and its webcams, the most recent images first. Port of
 * `ResortDetailView`; favourites, season, snow and forecast come with their areas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResortScreen(resort: Resort, onBack: () -> Unit) {
    val cards = remember(resort.id) { mutableStateMapOf<String, CardState>() }
    // Reordered only once every card has settled, so cards do not shuffle while the screen fills up.
    var orderedWebcams by remember(resort.id) { mutableStateOf(resort.webcams) }
    val allSettled = resort.webcams.all { cards[it.id]?.isSettled == true }

    LaunchedEffect(resort.id) {
        for (webcam in resort.webcams) launch { load(webcam, resort) { cards[webcam.id] = it(cards[webcam.id] ?: CardState()) } }
    }
    LaunchedEffect(allSettled) {
        if (allSettled) {
            orderedWebcams = resort.webcams.sortedByFreshness { webcam ->
                cards[webcam.id]?.snapshot?.let { SnapshotFreshness.of(it) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(resort.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        // One column on a phone, more on a wide window.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { ResortHeader(resort) }
            if (resort.webcams.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { NoWebcams() }
            }
            items(orderedWebcams, key = { it.id }) { webcam ->
                WebcamCard(webcam, resort, cards[webcam.id] ?: CardState(), Modifier.animateItem())
            }
        }
    }
}

/**
 * Shows the in-memory image straight away, then the latest one. A failed
 * refresh keeps the image already shown, as on iOS.
 */
private suspend fun load(webcam: Webcam, resort: Resort, update: ((CardState) -> CardState) -> Unit) {
    webcamService.cachedSnapshot(webcam, SnapshotSize.THUMBNAIL)?.let { cached ->
        decode(cached)?.let { image -> update { it.copy(snapshot = cached, image = image) } }
    }
    try {
        val latest = webcamService.latestSnapshot(webcam, resort, SnapshotSize.THUMBNAIL)
        val image = decode(latest) ?: throw WebcamError.NotAnImage()
        update { CardState(latest, image) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        update { it.copy(error = e) }
    }
}

/** Decodes straight to card size: a full panorama decoded as-is costs tens of MB. */
private suspend fun decode(snapshot: WebcamSnapshot): Bitmap? = withContext(Dispatchers.Default) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(snapshot.imageData))) { decoder, info, _ ->
            val scale = THUMBNAIL_PIXEL_SIZE.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
        }
    }.getOrNull()
}

@Composable
private fun ResortHeader(resort: Resort) {
    val country = Locale.Builder().setRegion(resort.country).build().displayCountry
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${resort.region} · $country", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (resort.webcams.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.webcam_count, resort.webcams.size, resort.webcams.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(resort.websiteURL) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.website))
        }
    }
}

@Composable
private fun NoWebcams() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        Text(stringResource(R.string.no_webcams), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.no_webcams_description),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Port of `WebcamCard`: cropped latest image, name, update time and provider. */
@Composable
private fun WebcamCard(webcam: Webcam, resort: Resort, state: CardState, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            when {
                state.image != null -> Image(
                    state.image.asImageBitmap(),
                    contentDescription = null, // The webcam's name is right below.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
                state.error != null -> Text(
                    errorMessage(state.error, webcam),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> CircularProgressIndicator()
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(webcam.name, style = MaterialTheme.typography.titleMedium)
                state.snapshot?.let { FreshnessLabel(SnapshotFreshness.of(it), resort) }
            }
            Text(
                webcam.attribution.providerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Updated 14:30", "Last image 11 Apr 2026" or "Time unknown", in the resort's time zone. */
@Composable
private fun FreshnessLabel(freshness: SnapshotFreshness, resort: Resort) {
    val text = freshness.shortLabel(resort.timeZone)
    Text(
        when (freshness) {
            is SnapshotFreshness.Recent -> stringResource(R.string.freshness_updated, text.orEmpty())
            is SnapshotFreshness.Stale -> stringResource(R.string.freshness_last_image, text.orEmpty())
            SnapshotFreshness.Unknown -> stringResource(R.string.freshness_unknown)
        },
        style = MaterialTheme.typography.bodySmall,
        // An old image must not read as current.
        color = if (freshness.isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun errorMessage(error: Throwable, webcam: Webcam): String = when (error) {
    is WebcamError.NoSnapshotAvailable -> stringResource(R.string.error_no_snapshot)
    is WebcamError.UnsupportedProvider -> stringResource(R.string.error_unsupported_provider, webcam.attribution.providerName)
    is WebcamError.LatestImageNotFound -> stringResource(R.string.error_latest_image_not_found)
    is WebcamError.BadStatusCode -> stringResource(R.string.error_bad_status_code, error.code)
    is WebcamError.NotAnImage -> stringResource(R.string.error_not_an_image)
    else -> stringResource(R.string.error_unreachable)
}
