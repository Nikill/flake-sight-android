package com.tanukee.flakesight

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.tanukee.flakesight.core.Coordinate
import com.tanukee.flakesight.core.ForecastService
import com.tanukee.flakesight.core.nearest
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnowBand
import com.tanukee.flakesight.core.SnowTotals
import java.util.Locale
import kotlin.math.cos

/**
 * Every resort as a pin. Selecting one shows a card to open the resort or get
 * driving directions in Google Maps. Port of `ResortMapView`.
 *
 * The snow layer, off by default and switched from the top bar, turns each pin
 * into the snow coming over the forecast days, or fallen over the last days,
 * coloured on one scale (`SnowBand`) explained by the legend at the top. Until
 * the totals are in, or when they cannot be loaded, the pins stay plain.
 *
 * Once the user is located, the map opens framed on them and the nearest
 * resorts rather than on the whole catalogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResortMapScreen(
    resorts: List<Resort>,
    /** Loaded by the list for every row; `null` until then. */
    snowTotals: SnowTotals?,
    snowTotalsFailed: Boolean,
    /** What "View Resort" does: show the resort over the map, or beside the list. */
    onOpenResort: (Resort) -> Unit,
    onBack: (() -> Unit)?,
    userLocation: Coordinate? = null,
    /** Asks for location access if needed, then locates. */
    onRequestLocation: () -> Unit = {},
) {
    // Asks for location access so the map can show the user's position.
    LaunchedEffect(Unit) { onRequestLocation() }
    // Remembered between launches under the iOS keys.
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var showsSnow by remember { mutableStateOf(settings.getBoolean("mapShowsSnow", false)) }
    var snowKind by remember {
        mutableStateOf(SnowTotals.Kind.entries.firstOrNull { it.rawValue == settings.getString("mapSnowKind", null) } ?: SnowTotals.Kind.FORECAST)
    }
    var selectedResortID by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedResort = resorts.firstOrNull { it.id == selectedResortID }
    val snow = if (showsSnow) snowTotals else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    val label = stringResource(R.string.snow_layer)
                    IconToggleButton(
                        checked = showsSnow,
                        onCheckedChange = {
                            showsSnow = it
                            settings.edit().putBoolean("mapShowsSnow", it).apply()
                        },
                        modifier = Modifier.semantics { contentDescription = label },
                        // The emoji ignores the tint, so the state shows as a background.
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) { Text("❄", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        // The map keeps its Google logo and controls clear of the legend and the card.
        var legendHeight by remember { mutableStateOf(0.dp) }
        var cardHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        Box(Modifier.padding(padding).fillMaxSize()) {
            SnowMap(
                resorts,
                snow,
                snowKind,
                selectedResortID,
                onSelect = { selectedResortID = it },
                userLocation = userLocation,
                contentPadding = PaddingValues(
                    top = if (showsSnow) legendHeight else 0.dp,
                    bottom = if (selectedResort != null) cardHeight else 0.dp,
                ),
            )
            if (showsSnow) {
                SnowLegend(
                    snowKind,
                    onKindChange = {
                        snowKind = it
                        settings.edit().putString("mapSnowKind", it.rawValue).apply()
                    },
                    state = when {
                        snowTotals != null -> LegendState.READY
                        snowTotalsFailed -> LegendState.UNAVAILABLE
                        else -> LegendState.LOADING
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                        .onSizeChanged { legendHeight = with(density) { it.height.toDp() } },
                )
            }
            if (selectedResort != null) {
                ResortMapCard(
                    selectedResort,
                    snow,
                    onOpen = onOpenResort,
                    onClose = { selectedResortID = null },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
                )
            }
        }
    }
}

@Composable
private fun SnowMap(
    resorts: List<Resort>,
    snow: SnowTotals?,
    kind: SnowTotals.Kind,
    selectedResortID: String?,
    onSelect: (String?) -> Unit,
    userLocation: Coordinate?,
    contentPadding: PaddingValues,
) {
    val bounds = remember(resorts) {
        LatLngBounds.builder().apply { resorts.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
    }
    // Centred on the catalogue straight away, then fitted to it, or to the user and the
    // nearest resorts, once the map knows its size; the camera then stays where the user leaves it.
    val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(bounds.center, 5f) }
    var isFramed by rememberSaveable { mutableStateOf(false) }
    var isMapLoaded by remember { mutableStateOf(false) }
    var isPositionedByUser by rememberSaveable { mutableStateOf(false) }
    val framingPadding = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving && cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            isPositionedByUser = true
        }
    }
    // Located after opening: move there unless the user already moved the map.
    LaunchedEffect(userLocation, isMapLoaded) {
        if (userLocation != null && isMapLoaded && !isPositionedByUser) {
            framing(resorts, userLocation)?.let { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(it, framingPadding)) }
        }
    }
    val isDark = isSystemInDarkTheme()
    val locale = LocalConfiguration.current.locales[0]
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        // The blue dot and its button, once located (which means access was granted).
        properties = MapProperties(isMyLocationEnabled = userLocation != null),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        contentPadding = contentPadding,
        onMapClick = { onSelect(null) },
        onMapLoaded = {
            if (!isFramed) {
                val start = userLocation?.let { framing(resorts, it) } ?: bounds
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(start, framingPadding))
                isFramed = true
            }
            isMapLoaded = true
        },
    ) {
        resorts.forEach { resort ->
            key(resort.id) {
                val state = rememberUpdatedMarkerState(LatLng(resort.latitude, resort.longitude))
                val isSelected = resort.id == selectedResortID
                val band = snow?.band(kind, resort.id)
                val onClick: (com.google.android.gms.maps.model.Marker) -> Boolean = {
                    onSelect(resort.id)
                    true
                }
                if (snow != null && band != null) {
                    val label = snow.centimetres(kind, resort.id)?.let { centimetres(it, locale) }
                    val description = listOfNotNull(resort.name, snowTotalSentence(snow, kind, resort.id)).joinToString(", ")
                    MarkerComposable(
                        band, label.orEmpty(), isSelected, isDark,
                        state = state,
                        contentDescription = description,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = if (isSelected) 1f else 0f,
                        onClick = onClick,
                    ) { SnowPin(band, label, isSelected, isDark) }
                } else {
                    Marker(state = state, title = resort.name, contentDescription = resort.name, zIndex = if (isSelected) 1f else 0f, onClick = onClick)
                }
            }
        }
    }
}

/** The user and the 15 nearest resorts, at least [MINIMUM_SPAN_METRES] across. */
private fun framing(resorts: List<Resort>, user: Coordinate): LatLngBounds? {
    val nearest = resorts.nearest(15, user)
    if (nearest.isEmpty()) return null
    val halfSpan = MINIMUM_SPAN_METRES / 2 / METRES_PER_DEGREE_OF_LATITUDE
    return LatLngBounds.builder().apply {
        nearest.forEach { include(LatLng(it.latitude, it.longitude)) }
        include(LatLng(user.latitude - halfSpan, user.longitude - halfSpan / cos(Math.toRadians(user.latitude))))
        include(LatLng(user.latitude + halfSpan, user.longitude + halfSpan / cos(Math.toRadians(user.latitude))))
    }.build()
}

private const val MINIMUM_SPAN_METRES = 5_000.0
private const val METRES_PER_DEGREE_OF_LATITUDE = 111_320.0

/**
 * A resort's snow total on the map: the figure on its step's colour, or a grey
 * dot when under a centimetre, ringed in white to stand off the map.
 */
@Composable
private fun SnowPin(band: SnowBand, label: String?, isSelected: Boolean, isDark: Boolean) {
    val shape = RoundedCornerShape(50)
    // Room for the shadow and the larger selected pin inside the bitmap.
    Box(Modifier.padding(4.dp)) {
        Box(
            Modifier.scale(if (isSelected) 1.25f else 1f)
                .shadow(2.dp, shape)
                .background(band.color(isDark), shape)
                .border(2.dp, Color.White, shape),
        ) {
            if (label != null) {
                Text(
                    label,
                    color = band.inkColor(isDark),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            } else {
                Box(Modifier.size(10.dp))
            }
        }
    }
}

private enum class LegendState { LOADING, READY, UNAVAILABLE }

/**
 * The snow layer's panel: which total the pins show, the colour scale, and the
 * Open-Meteo credit every screen drawing a forecast carries.
 */
@Composable
private fun SnowLegend(
    kind: SnowTotals.Kind,
    onKindChange: (SnowTotals.Kind) -> Unit,
    state: LegendState,
    modifier: Modifier = Modifier,
) {
    MapPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val kinds = listOf(SnowTotals.Kind.FORECAST, SnowTotals.Kind.RECENT)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == kind,
                        onClick = { onKindChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) {
                        Text(
                            stringResource(if (option == SnowTotals.Kind.FORECAST) R.string.sort_snow_forecast else R.string.sort_recent_snow),
                            maxLines = 1,
                        )
                    }
                }
            }
            val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
            val caption = MaterialTheme.typography.bodySmall
            when (state) {
                LegendState.LOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.loading_snow_totals), style = caption, color = captionColor)
                }
                LegendState.UNAVAILABLE -> Text("⚠ " + stringResource(R.string.snow_totals_unavailable), style = caption, color = captionColor)
                LegendState.READY -> {
                    Text(
                        stringResource(
                            if (kind == SnowTotals.Kind.RECENT) R.string.period_last_days else R.string.period_next_days,
                            kind.dayCount,
                        ),
                        style = caption,
                        color = captionColor,
                    )
                    Scale()
                    val uriHandler = LocalUriHandler.current
                    TextButton(onClick = { uriHandler.openUri(ForecastService.ATTRIBUTION_URL) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.weather_data_by_open_meteo), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** One column per step, sharing the width, the unit once at the end. */
@Composable
private fun Scale() {
    val isDark = isSystemInDarkTheme()
    val legend = stringResource(R.string.legend)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = legend },
    ) {
        SnowBand.entries.forEach { band ->
            val title = band.title()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = title },
            ) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(band.color(isDark), RoundedCornerShape(50)))
                Text(band.shortTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Text(
            "cm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun ResortMapCard(
    resort: Resort,
    snow: SnowTotals?,
    onOpen: (Resort) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(resort.name, style = MaterialTheme.typography.titleMedium)
                    val webcams = if (resort.webcams.isEmpty()) {
                        stringResource(R.string.forecast_only)
                    } else {
                        pluralStringResource(R.plurals.webcam_count, resort.webcams.size, resort.webcams.size)
                    }
                    Text("${resort.region} · $webcams", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snow != null) {
                        val forecast = snowTotalSentence(snow, SnowTotals.Kind.FORECAST, resort.id)
                        val recent = snowTotalSentence(snow, SnowTotals.Kind.RECENT, resort.id)
                        if (forecast != null && recent != null) {
                            Column(Modifier.padding(top = 4.dp)) {
                                Text("🌨 $forecast", style = MaterialTheme.typography.bodySmall)
                                Text("❄ $recent", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(resort) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.view_resort) + " ›", maxLines = 1)
                }
                val uriHandler = LocalUriHandler.current
                OutlinedButton(onClick = { uriHandler.openUri(directionsURL(resort)) }, modifier = Modifier.weight(1f)) {
                    Text("🚗 " + stringResource(R.string.directions), maxLines = 1)
                }
            }
        }
    }
}

/** Full width on a phone; on a wide window, a card rather than a banner. */
@Composable
private fun MapPanel(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.padding(12.dp).widthIn(max = MAP_PANEL_MAX_WIDTH),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) { Box(Modifier.padding(12.dp)) { content() } }
}

private val MAP_PANEL_MAX_WIDTH: Dp = 500.dp

/** Google Maps driving directions to the car park, or the resort centre (`Resort.drivingDestination`). */
internal fun directionsURL(resort: Resort): String {
    val (latitude, longitude) = resort.drivingDestination
    return "https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude&travelmode=driving"
}

/** "12 cm expected in the next 5 days", "No snow in the last 7 days", or `null` when the resort was not fetched. */
@Composable
private fun snowTotalSentence(snow: SnowTotals, kind: SnowTotals.Kind, resortID: String): String? {
    if (snow.amount(kind, resortID) == null) return null
    val locale: Locale = LocalConfiguration.current.locales[0]
    val cm = snow.centimetres(kind, resortID)
    return when {
        cm != null -> snowSentence(kind, centimetres(cm, locale))
        kind == SnowTotals.Kind.RECENT -> stringResource(R.string.no_snow_last_days, kind.dayCount)
        else -> stringResource(R.string.no_snow_next_days, kind.dayCount)
    }
}

// The scale's colours and wording. Port of `SnowBand+Color`: one blue ramp,
// light to deep, turned round in dark mode so the deepest snow stays the most visible.

private fun SnowBand.color(isDark: Boolean): Color = Color(
    when (this) {
        SnowBand.NONE -> 0xFF8E8E93
        SnowBand.LIGHT -> if (isDark) 0xFF1C5CAB else 0xFF6DA7EC
        SnowBand.MODERATE -> if (isDark) 0xFF3987E5 else 0xFF256ABF
        SnowBand.HEAVY -> if (isDark) 0xFF86B6EF else 0xFF184F95
        SnowBand.DEEP -> if (isDark) 0xFFCDE2FB else 0xFF0D366B
    },
)

/** White on the darker steps, dark ink on the paler ones. */
private fun SnowBand.inkColor(isDark: Boolean): Color {
    val white = when (this) {
        SnowBand.NONE -> true
        SnowBand.LIGHT -> isDark
        SnowBand.MODERATE, SnowBand.HEAVY, SnowBand.DEEP -> !isDark
    }
    return if (white) Color.White else Color(0xFF0F1724)
}

/** "0", "1-9", "10-29", "30-49", "50+" under the legend's colours. */
private val SnowBand.shortTitle: String
    get() = when {
        this == SnowBand.NONE -> "0"
        upperBound == null -> "$lowerBound+"
        else -> "$lowerBound-$upperBound"
    }

/** "Under 1 cm", "1 to 9 cm", "50 cm or more": read by TalkBack for each step. */
@Composable
private fun SnowBand.title(): String = when (val upper = upperBound) {
    null -> stringResource(R.string.snow_band_over, lowerBound)
    else -> if (this == SnowBand.NONE) {
        stringResource(R.string.snow_band_under, SnowBand.LIGHT.lowerBound)
    } else {
        stringResource(R.string.snow_band_range, lowerBound, upper)
    }
}

