package com.tanukee.flakesight

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.layout.Spacer
import com.tanukee.flakesight.core.FavoriteResort
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnowDepthReading
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
internal val webcamService = WebcamService()

/** What a webcam view shows: the last image loaded, and why the latest load failed if it did. */
internal data class ImageState(
    val snapshot: WebcamSnapshot? = null,
    val image: Bitmap? = null,
    val error: Throwable? = null,
    val isLoading: Boolean = false,
) {
    val isSettled get() = image != null || error != null
}

/**
 * A resort and its webcams, the most recent images first. Port of
 * `ResortDetailView`; favourites, season, snow and forecast come with their areas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResortScreen(
    resort: Resort,
    favorite: FavoriteResort?,
    onToggleFavorite: () -> Unit,
    onSetDefault: () -> Unit,
    onBack: (() -> Unit)?,
    onSelectWebcam: (Webcam) -> Unit,
    /** The massif's avalanche bulletin read at this resort, when recent. */
    snowDepth: SnowDepthReading? = null,
    /** Opens the advanced weather: from the forecast strip, the snow card and the avalanche badge. */
    onOpenForecast: (() -> Unit)? = null,
) {
    val cards = remember(resort.id) { mutableStateMapOf<String, ImageState>() }
    // Reordered only once every card has settled, so cards do not shuffle while the screen fills up.
    var orderedWebcams by remember(resort.id) { mutableStateOf(resort.webcams) }
    val allSettled = resort.webcams.all { cards[it.id]?.isSettled == true }

    LaunchedEffect(resort.id) {
        for (webcam in resort.webcams) {
            launch {
                loadImage(webcam, resort, SnapshotSize.THUMBNAIL) { cards[webcam.id] = it(cards[webcam.id] ?: ImageState()) }
            }
        }
    }
    LaunchedEffect(allSettled) {
        if (allSettled) {
            orderedWebcams = resort.webcams.sortedByFreshness { webcam ->
                cards[webcam.id]?.snapshot?.let { SnapshotFreshness.of(it) }
            }
        }
    }

    // A large title that shrinks into the bar as the page scrolls, as iOS's large navigation title.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(resort.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    // No Back beside the list, where the resort is the pane's root.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        if (favorite == null) {
                            Icon(painterResource(R.drawable.ic_star_border), stringResource(R.string.add_to_favourites))
                        } else {
                            Icon(painterResource(R.drawable.ic_star), stringResource(R.string.remove_from_favourites), tint = FavoriteYellow)
                        }
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
            item(span = { GridItemSpan(maxLineSpan) }) { ResortHeader(resort, favorite, onSetDefault, snowDepth, onOpenForecast) }
            if (resort.webcams.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { NoWebcams() }
            }
            items(orderedWebcams, key = { it.id }) { webcam ->
                WebcamCard(
                    webcam, resort, cards[webcam.id] ?: ImageState(),
                    Modifier.animateItem().clickable { onSelectWebcam(webcam) },
                )
            }
        }
    }
}

/**
 * Port of `SnapshotLoader.load`. Shows the in-memory image straight away (a
 * full-size view can start from the card's thumbnail), then the latest one.
 * A failed refresh keeps the image already shown.
 */
internal suspend fun loadImage(
    webcam: Webcam,
    resort: Resort,
    size: SnapshotSize,
    forceRefresh: Boolean = false,
    update: ((ImageState) -> ImageState) -> Unit,
) {
    // Longest decoded side: `ImageDownsampler.thumbnailPixelSize` and `detailPixelSize` on iOS.
    val maxPixelSize = if (size == SnapshotSize.THUMBNAIL) 1200 else 4096
    if (!forceRefresh) {
        val cached = webcamService.cachedSnapshot(webcam, size) ?: webcamService.cachedSnapshot(webcam, SnapshotSize.THUMBNAIL)
        cached?.let { decode(it, maxPixelSize) }?.let { image -> update { it.copy(snapshot = cached, image = image) } }
    }
    update { it.copy(isLoading = true, error = null) }
    try {
        val latest = webcamService.latestSnapshot(webcam, resort, size, forceRefresh)
        val image = decode(latest, maxPixelSize) ?: throw WebcamError.NotAnImage()
        update { ImageState(latest, image) }
    } catch (e: CancellationException) {
        update { it.copy(isLoading = false) }
        throw e
    } catch (e: Exception) {
        update { it.copy(error = e, isLoading = false) }
    }
}

/** Decodes straight to display size: a full panorama decoded as-is costs tens of MB. */
private suspend fun decode(snapshot: WebcamSnapshot, maxPixelSize: Int): Bitmap? = withContext(Dispatchers.Default) {
    runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(snapshot.imageData))) { decoder, info, _ ->
            val scale = maxPixelSize.toFloat() / max(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
        }
    }.getOrNull()
}

@Composable
private fun ResortHeader(
    resort: Resort,
    favorite: FavoriteResort?,
    onSetDefault: () -> Unit,
    snowDepth: SnowDepthReading?,
    onOpenForecast: (() -> Unit)?,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { uriHandler.openUri(resort.websiteURL) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.website))
            }
            TextButton(onClick = { uriHandler.openUri(directionsURL(resort)) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.directions))
            }
            Spacer(Modifier.weight(1f))
            if (favorite?.isDefault == true) {
                Text(stringResource(R.string.default_resort), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (resort.webcams.isNotEmpty()) {
                // The default resort is what the widgets will show.
                TextButton(onClick = onSetDefault) { Text(stringResource(R.string.set_as_default)) }
            }
        }
        if (snowDepth != null) {
            SnowyMountainCard(snowDepth, onOpenForecast)
            Spacer(Modifier.height(4.dp))
            AvalancheRiskBadge(snowDepth, onOpenForecast)
            Spacer(Modifier.height(8.dp))
        }
        ForecastStrip(resort, showModelSnowDepth = snowDepth == null, onOpenAdvanced = onOpenForecast)
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
private fun WebcamCard(webcam: Webcam, resort: Resort, state: ImageState, modifier: Modifier = Modifier) {
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
internal fun FreshnessLabel(freshness: SnapshotFreshness, resort: Resort) {
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
internal fun errorMessage(error: Throwable, webcam: Webcam): String = when (error) {
    is WebcamError.NoSnapshotAvailable -> stringResource(R.string.error_no_snapshot)
    is WebcamError.UnsupportedProvider -> stringResource(R.string.error_unsupported_provider, webcam.attribution.providerName)
    is WebcamError.LatestImageNotFound -> stringResource(R.string.error_latest_image_not_found)
    is WebcamError.BadStatusCode -> stringResource(R.string.error_bad_status_code, error.code)
    is WebcamError.NotAnImage -> stringResource(R.string.error_not_an_image)
    else -> stringResource(R.string.error_unreachable)
}
