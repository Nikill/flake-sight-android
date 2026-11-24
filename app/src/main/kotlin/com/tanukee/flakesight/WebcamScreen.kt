package com.tanukee.flakesight

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.icu.text.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import com.tanukee.flakesight.core.AfternoonSnapshot
import com.tanukee.flakesight.core.AfternoonWindow
import com.tanukee.flakesight.core.WebcamError
import com.tanukee.flakesight.core.WebcamSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnapshotFreshness
import com.tanukee.flakesight.core.SnapshotSize
import com.tanukee.flakesight.core.Webcam
import kotlinx.coroutines.launch


/**
 * One webcam: large panorama scrolling sideways, refresh, capture time and
 * source. Under the panorama, the afternoon images of the last days replace
 * the latest one on demand, for when it is dark or old. Port of
 * `WebcamDetailView`; no catalogued webcam has a live stream yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebcamScreen(webcam: Webcam, resort: Resort, onBack: () -> Unit) {
    var state by remember(webcam.id) { mutableStateOf(ImageState()) }
    val scope = rememberCoroutineScope()
    val load: (Boolean) -> Unit = { forceRefresh ->
        scope.launch { loadImage(webcam, resort, SnapshotSize.FULL, forceRefresh) { state = it(state) } }
    }
    LaunchedEffect(webcam.id) { loadImage(webcam, resort, SnapshotSize.FULL) { state = it(state) } }

    // Port of `AfternoonLoader`: the days on offer, the one shown (`null` for the latest image) and its image.
    val afternoons = afternoonService(LocalContext.current)
    var days by remember(webcam.id) { mutableStateOf(emptyList<LocalDate>()) }
    var selectedDay by remember(webcam.id) { mutableStateOf<LocalDate?>(null) }
    var afternoon by remember(webcam.id) { mutableStateOf(AfternoonState()) }
    var afternoonAttempt by remember(webcam.id) { mutableIntStateOf(0) }
    LaunchedEffect(webcam.id) {
        days = withContext(Dispatchers.IO) {
            if (afternoons.hasAfternoons(webcam)) afternoons.availableDays(webcam, resort) else emptyList()
        }
    }
    val noImageThatDay = stringResource(R.string.no_image_near_1600)
    LaunchedEffect(selectedDay, afternoonAttempt) {
        val day = selectedDay ?: return@LaunchedEffect
        // Never show one day's picture under another day's label.
        afternoon = AfternoonState(isLoading = true)
        afternoon = try {
            val found = afternoons.snapshot(webcam, resort, day)
            val image = found?.let { decode(WebcamSnapshot(it.webcamID, it.imageURL, it.imageData, it.capturedAt, it.capturedAt), 4096) }
            when {
                found == null -> AfternoonState(error = noImageThatDay)
                image == null -> AfternoonState(error = WebcamError.NotAnImage())
                else -> AfternoonState(found, image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AfternoonState(error = e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(webcam.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // A past image never changes; only the latest one refreshes.
                    if (selectedDay == null) {
                        IconButton(onClick = { load(true) }, enabled = !state.isLoading) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding)) {
            // Taller on a wide window (unfolded foldable, tablet), where there is room.
            val panoramaHeight = if (maxWidth >= 600.dp) 480.dp else 300.dp
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            ) {
                val locale = LocalConfiguration.current.locales[0]
                val day = selectedDay
                if (day == null) {
                    Panorama(
                        state.image, state.isLoading, state.error?.let { errorMessage(it, webcam) },
                        stringResource(R.string.latest_image_from, webcam.name), panoramaHeight, onRetry = { load(true) },
                    )
                } else {
                    val label = afternoon.snapshot?.let { afternoonLabel(it.day, it.capturedAt, resort, locale) }.orEmpty()
                    Panorama(
                        afternoon.image, afternoon.isLoading,
                        afternoon.error?.let { (it as? String) ?: errorMessage(it as Throwable, webcam) },
                        stringResource(R.string.image_from, webcam.name, label), panoramaHeight, onRetry = { afternoonAttempt++ },
                    )
                }
                if (days.isNotEmpty()) {
                    DayPicker(days, selectedDay, resort, locale, onSelect = { selectedDay = it })
                }
                Details(webcam, resort, state, afternoon.snapshot?.takeIf { day != null }, locale)
                Attribution(webcam)
            }
        }
    }
}

/** One afternoon image being shown: the image, or why there is none. [error] is a message or a [Throwable]. */
private data class AfternoonState(
    val snapshot: AfternoonSnapshot? = null,
    val image: Bitmap? = null,
    val error: Any? = null,
    val isLoading: Boolean = false,
)

/** "Today", "Yesterday", then "Tue 27": what a day chip shows, in the resort's calendar. */
@Composable
private fun dayLabel(day: LocalDate, resort: Resort, locale: Locale): String {
    val today = AfternoonWindow.day(Instant.now(), resort.timeZone)
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> DateFormat.getInstanceForSkeleton("EEEd", locale)
            .apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
            .format(Date.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC)))
    }
}

/** "Yesterday, 15:30" or "Tue 27, 15:30", in the resort's time zone. Port of `AfternoonSnapshot.label`. */
@Composable
private fun afternoonLabel(day: LocalDate, capturedAt: Instant, resort: Resort, locale: Locale): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(resort.timeZone).format(capturedAt)
    return stringResource(R.string.afternoon_label, dayLabel(day, resort, locale), time)
}

/** "Now", then one chip per day with an afternoon image. */
@Composable
private fun DayPicker(days: List<LocalDate>, selected: LocalDate?, resort: Resort, locale: Locale, onSelect: (LocalDate?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "🕓 " + stringResource(R.string.around_1600),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.now)) })
            days.forEach { day ->
                FilterChip(selected = day == selected, onClick = { onSelect(day) }, label = { Text(dayLabel(day, resort, locale)) })
            }
        }
    }
}

/** Panoramas are ~6:1, so they keep a usable height and scroll sideways, starting from the middle. */
@Composable
private fun Panorama(image: Bitmap?, isLoading: Boolean, error: String?, description: String, height: Dp, onRetry: () -> Unit) {
    when {
        image != null -> Box {
            val scroll = rememberScrollState()
            LaunchedEffect(scroll.maxValue) { if (scroll.value == 0) scroll.scrollTo(scroll.maxValue / 2) }
            Box(Modifier.horizontalScroll(scroll)) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(height).aspectRatio(image.width.toFloat() / image.height),
                )
            }
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp))
            }
        }
        error != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.titleMedium)
            Text(
                error,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
        else -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Details(webcam: Webcam, resort: Resort, state: ImageState, afternoon: AfternoonSnapshot?, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        if (afternoon != null) {
            // A dated picture of the past, never the current image.
            Text("🕓 " + afternoonLabel(afternoon.day, afternoon.capturedAt, resort, locale), style = MaterialTheme.typography.bodySmall)
        } else {
            // With no image at all, the panorama area already explains the failure.
            state.snapshot?.let { FreshnessLabel(SnapshotFreshness.of(it), resort) }
        }
        if (afternoon == null && state.image != null && state.error != null) {
            // A refresh failed but an older image is still shown.
            Text(errorMessage(state.error, webcam), color = MaterialTheme.colorScheme.error)
        }
        webcam.altitude?.let { altitude ->
            Text(metres(altitude.toInt(), locale))
        }
        webcam.orientation?.let { Text(stringResource(R.string.facing, it)) }
    }
}

@Composable
private fun Attribution(webcam: Webcam) {
    val attribution = webcamService.attribution(webcam)
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.source), style = MaterialTheme.typography.titleMedium)
        Text(
            attribution.owner?.let { stringResource(R.string.owner_via_provider, it, attribution.providerName) }
                ?: stringResource(R.string.via_provider, attribution.providerName),
        )
        TextButton(onClick = { uriHandler.openUri(webcam.sourcePageURL) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.view_on_provider, attribution.providerName))
        }
    }
}
