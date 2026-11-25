package com.tanukee.flakesight

import android.graphics.Bitmap
import android.icu.text.DateFormat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tanukee.flakesight.core.ResortSeason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
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
import com.tanukee.flakesight.core.featuring
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One per process, so its memory cache and throttle outlive the screens. */
/** One service for the app and the widget; its downloads near 16:00 can become afternoon images. */
internal val webcamService = WebcamService(onDownload = ::recordAfternoon)

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
 * A resort: one webcam's latest image at the top under a clear bar, with the
 * resort's name over it; basic information and favourite controls, the
 * weather card, the bulletin's danger level and snow depth as two tiles, then
 * the other webcams. The top webcam is the one the user chose, otherwise the
 * first of the cards' order (the catalogue's, stale images moved to the end).
 * Port of `ResortDetailView`.
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
    /** The webcam the user put at the top, shown first with a star. */
    featuredWebcamID: String? = null,
    /** Puts a webcam at the top, or with `null` brings back the screen's own order. */
    onFeatureWebcam: (String?) -> Unit = {},
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

    val webcams = orderedWebcams.featuring(featuredWebcamID)
    val hero = webcams.firstOrNull()
    val gridState = rememberLazyGridState()
    // The bar stays clear over the image, and takes the page's colour and the name once it scrolls away.
    val isHeroGone by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
    // Without a webcam, a large title that shrinks into the bar as the page scrolls, as iOS's large navigation title.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navigationIcon: @Composable () -> Unit = {
        // No Back beside the list, where the resort is the pane's root.
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onToggleFavorite) {
            if (favorite == null) {
                Icon(painterResource(R.drawable.ic_star_border), stringResource(R.string.add_to_favourites))
            } else {
                Icon(painterResource(R.drawable.ic_star), stringResource(R.string.remove_from_favourites), tint = FavoriteYellow)
            }
        }
    }
    Scaffold(
        modifier = if (hero == null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        topBar = {
            if (hero != null) {
                val onImage = !isHeroGone
                TopAppBar(
                    title = { if (!onImage) Text(resort.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (onImage) Color.Transparent else MaterialTheme.colorScheme.surface,
                        navigationIconContentColor = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface,
                    ),
                )
            } else LargeTopAppBar(
                title = { Text(resort.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        },
    ) { padding ->
        // One column on a phone, more on a wide window. With a webcam at the top, the page starts under the bar.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (hero == null) 16.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = if (hero == null) Modifier.padding(padding) else Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            if (hero != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    FeaturableWebcam(hero, featuredWebcamID, onFeatureWebcam, onSelectWebcam, Modifier.fullBleed(16.dp)) { modifier ->
                        ResortHero(hero, resort, cards[hero.id] ?: ImageState(), hero.id == featuredWebcamID, modifier)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { ResortHeader(resort, favorite, onSetDefault, snowDepth, onOpenForecast) }
            if (resort.webcams.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { NoWebcams() }
            }
            items(webcams.drop(1), key = { it.id }) { webcam ->
                FeaturableWebcam(webcam, featuredWebcamID, onFeatureWebcam, onSelectWebcam, Modifier.animateItem()) { modifier ->
                    WebcamCard(webcam, resort, cards[webcam.id] ?: ImageState(), webcam.id == featuredWebcamID, modifier)
                }
            }
        }
    }
}

/**
 * A webcam that opens on a tap, and on a long press (or a TalkBack action)
 * offers to go to the top of the screen or back down.
 */
@Composable
private fun FeaturableWebcam(
    webcam: Webcam,
    featuredWebcamID: String?,
    onFeatureWebcam: (String?) -> Unit,
    onSelectWebcam: (Webcam) -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val isFeatured = webcam.id == featuredWebcamID
    var isShowingMenu by remember { mutableStateOf(false) }
    val featureLabel = stringResource(if (isFeatured) R.string.remove_from_the_top else R.string.show_at_the_top)
    val toggleFeatured = { onFeatureWebcam(if (isFeatured) null else webcam.id) }
    Box(modifier) {
        content(
            Modifier
                .combinedClickable(onLongClick = { isShowingMenu = true }) { onSelectWebcam(webcam) }
                .semantics { customActions = listOf(CustomAccessibilityAction(featureLabel) { toggleFeatured(); true }) },
        )
        DropdownMenu(expanded = isShowingMenu, onDismissRequest = { isShowingMenu = false }) {
            DropdownMenuItem(
                text = { Text(featureLabel) },
                leadingIcon = { Icon(painterResource(if (isFeatured) R.drawable.ic_star_border else R.drawable.ic_star), null) },
                onClick = {
                    isShowingMenu = false
                    toggleFeatured()
                },
            )
        }
    }
}

/** Lays the element out wider than its cell by [inset] on each side, across the grid's content padding. */
private fun Modifier.fullBleed(inset: Dp) = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * The top of the resort screen: one webcam's latest image across the width,
 * under the bar, fading into the page, with the webcam's name and capture time
 * and the resort's name over it. Port of `ResortHero`.
 */
@Composable
private fun ResortHero(webcam: Webcam, resort: Resort, state: ImageState, isFeatured: Boolean, modifier: Modifier) {
    val page = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(300.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomStart) {
        state.image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        // Dark at the top for the bar, the page's own colour at the bottom so the image melts into it.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent, 0.5f to Color.Transparent, 1f to page),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                if (isFeatured) {
                    Icon(painterResource(R.drawable.ic_star), stringResource(R.string.shown_at_the_top), tint = FavoriteYellow, modifier = Modifier.size(14.dp))
                }
                Text(webcam.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                state.snapshot?.let { FreshnessLabel(SnapshotFreshness.of(it), resort) }
            }
            Text(
                resort.name,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
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
internal suspend fun decode(snapshot: WebcamSnapshot, maxPixelSize: Int): Bitmap? = withContext(Dispatchers.Default) {
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
        seasonSummary(resort)?.let { summary ->
            // TalkBack reads the sentence, not the calendar emoji.
            Text(
                "📅 $summary",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = summary },
            )
        }
        val uriHandler = LocalUriHandler.current
        resort.liveVideoURL?.let { url ->
            // Opens YouTube: the stream has no image the app could show.
            TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = PaddingValues(0.dp)) {
                Text("▶ " + stringResource(R.string.live_on_youtube))
            }
        }
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
        Spacer(Modifier.height(4.dp))
        ForecastStrip(resort, showModelSnowDepth = snowDepth == null, onOpenAdvanced = onOpenForecast)
        // Only resorts inside a bulletin massif have these: the Jura, the Vosges and the Massif Central do not.
        if (snowDepth != null) {
            Spacer(Modifier.height(8.dp))
            ResortConditionTiles(snowDepth, onOpenForecast)
        }
    }
}

/**
 * "Opening planned on 12 December", "Season planned until 18 April" or "Season ended
 * on 18 April", never "open": the catalogue does not follow a postponed opening.
 * `null` while the season runs with no published closing day. Port of `ResortSeason.summary`.
 */
@Composable
private fun seasonSummary(resort: Resort): String? {
    val phase = resort.season?.phase(Instant.now(), resort.timeZone) ?: return null
    val locale = LocalConfiguration.current.locales[0]
    // "12 December", "12 décembre": the catalogued day, at noon UTC so no time zone shifts it.
    fun day(date: LocalDate) = DateFormat.getInstanceForSkeleton("dMMMM", locale)
        .apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
        .format(Date.from(date.atTime(12, 0).toInstant(ZoneOffset.UTC)))
    return when (phase) {
        is ResortSeason.Phase.Upcoming -> stringResource(R.string.season_opening_planned, day(phase.opening))
        is ResortSeason.Phase.Running -> phase.closing?.let { stringResource(R.string.season_planned_until, day(it)) }
        is ResortSeason.Phase.Ended -> stringResource(R.string.season_ended, day(phase.closing))
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
private fun WebcamCard(webcam: Webcam, resort: Resort, state: ImageState, isFeatured: Boolean, modifier: Modifier = Modifier) {
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isFeatured) {
                        Icon(
                            painterResource(R.drawable.ic_star), stringResource(R.string.shown_at_the_top),
                            tint = FavoriteYellow, modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(webcam.name, style = MaterialTheme.typography.titleMedium)
                }
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
