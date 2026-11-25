package com.tanukee.flakesight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanukee.flakesight.core.DetailedForecast
import com.tanukee.flakesight.core.DailyForecast
import com.tanukee.flakesight.core.ForecastConfidence
import com.tanukee.flakesight.core.ModelSnowfall
import com.tanukee.flakesight.core.SnowfallRange
import com.tanukee.flakesight.core.withRanges
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.saveable.rememberSaveable
import com.tanukee.flakesight.core.ForecastLevel
import com.tanukee.flakesight.core.ForecastService
import com.tanukee.flakesight.core.MountainProfile
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortForecast
import com.tanukee.flakesight.core.SnowDepthReading
import com.tanukee.flakesight.core.SnowDepthReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import com.tanukee.flakesight.core.ForecastGranularity
import com.tanukee.flakesight.core.ForecastSlot
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The forecast's colours, as `SnowPalette` on iOS: the snow blue of the snow card, rain cyan, the freezing level orange. */
internal val Rain = Color(0xFF32ADE6)
internal val FreezingLevel = Color(0xFFFF9500)

/**
 * The forecast in detail: the coming days across the mountain (what falls at
 * the summit and in the village, the freezing level between them, with the
 * avalanche bulletin's snow on the ground, snow line and rain-snow limit),
 * the snow of the last days, then the rest of the bulletin. Port of
 * `AdvancedForecastView`; the weather models table comes next. Reuses the
 * resort screen's requests from the cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedForecastScreen(resort: Resort, snowDepth: SnowDepthReading?, onBack: () -> Unit) {
    var forecast by remember(resort.id) { mutableStateOf(forecastService.cachedForecast(resort)) }
    var detailed by remember(resort.id) { mutableStateOf<DetailedForecast?>(null) }
    var models by remember(resort.id) { mutableStateOf(forecastService.cachedModelSnowfall(resort)) }
    var hasFailed by remember(resort.id) { mutableStateOf(false) }
    var attempt by remember(resort.id) { mutableIntStateOf(0) }
    LaunchedEffect(resort.id, attempt) {
        hasFailed = false
        launch {
            try {
                models = forecastService.modelSnowfall(resort)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        launch {
            try {
                detailed = forecastService.detailedForecast(resort)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        try {
            forecast = forecastService.forecast(resort)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            hasFailed = forecast == null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.advanced_weather))
                        Text(resort.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        // Each day's range once the models came back.
        val shown = forecast?.let { daily -> models?.let(daily::withRanges) ?: daily }
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                shown != null -> AdvancedContent(shown, detailed, models, snowDepth, resort)
                hasFailed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) {
                    Text(stringResource(R.string.forecast_unavailable), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.forecast_could_not_load), textAlign = TextAlign.Center)
                    Button(onClick = { attempt++ }) { Text(stringResource(R.string.try_again)) }
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
            }
        }
    }
}

@Composable
private fun AdvancedContent(
    forecast: ResortForecast,
    detailed: DetailedForecast?,
    models: ModelSnowfall?,
    snowDepth: SnowDepthReading?,
    resort: Resort,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // A resort without catalogued altitudes has no mountain to draw.
        MountainProfile.of(forecast, detailed, resort.timeZone)?.let { profile ->
            val slots = remember(detailed) { detailed?.let { ForecastSlot.slots(it, Instant.now(), resort.timeZone) }.orEmpty() }
            MountainProfileChart(profile, snowDepth?.bulletin, resort, slots)
        }
        recentSnowSentence(forecast)?.let { sentence ->
            Text(
                "❄️  $sentence",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SnowBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        val bulletin = snowDepth?.bulletin
        if (snowDepth != null && bulletin != null) BulletinSection(snowDepth, bulletin, resort.timeZone)
        WeatherModelsTable(forecast, models)
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(ForecastService.ATTRIBUTION_URL) }, contentPadding = PaddingValues(0.dp)) {
            Text(
                stringResource(R.string.weather_data_by_open_meteo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val AXIS_WIDTH = 76.dp
private val DAY_ROW_HEIGHT = 22.dp
private val CHART_HEIGHT = 260.dp

/**
 * The coming days across the mountain: the summit and the village at their
 * altitude, what falls at each every day with the day's highest temperature,
 * and the freezing level between them as a dashed line. With the massif's bulletin, a
 * first column gives the snow on the ground at each level (north over south),
 * the snow line runs across, and the bulletin's rain-snow limit follows the
 * days it covers. Port of `MountainProfileView`.
 */
@Composable
private fun MountainProfileChart(profile: MountainProfile, bulletin: SnowDepthReport.Massif?, resort: Resort, slots: List<ForecastSlot>) {
    val locale = LocalConfiguration.current.locales[0]
    // The Days / Hours switch, shared with the resort screen's weather card under the iOS key.
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var granularity by remember { mutableStateOf(ForecastGranularity.fromRawValue(settings.getString("forecastGranularity", null))) }
    val showsHours = granularity == ForecastGranularity.HOURS && slots.isNotEmpty()
    val snapshot = bulletin?.current
    val snowLine = snapshot?.snowLine?.takeIf { it.toDouble() in profile.altitudeDomain }
    val columns = (if (snapshot != null) 1 else 0) + profile.days.size
    val firstDayColumn = columns - profile.days.size
    // The bulletin's rain-snow limit for each day it covers, the midday period when there is one.
    val rainSnowLimits = bulletin?.weather?.rainSnowLimits(resort.timeZone).orEmpty()
    val limitPoints = profile.days.mapIndexedNotNull { index, day -> rainSnowLimits[day.date]?.let { index to it } }
    val description = profile.days.map { daySummary(it, locale) }.joinToString("\n")
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val domain = profile.altitudeDomain

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.snow_and_rain_by_altitude), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (slots.isNotEmpty()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ForecastGranularity.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = granularity == choice,
                        onClick = {
                            granularity = choice
                            settings.edit().putString("forecastGranularity", choice.rawValue).apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, ForecastGranularity.entries.size),
                        icon = {},
                    ) {
                        Text(stringResource(if (choice == ForecastGranularity.DAYS) R.string.forecast_days else R.string.forecast_hours))
                    }
                }
            }
        }
        if (showsHours) {
            HoursProfileChart(profile, bulletin, resort, slots, locale)
        } else BoxWithConstraints(Modifier.fillMaxWidth().height(CHART_HEIGHT).clearAndSetSemantics { contentDescription = description }) {
            val plotWidth = maxWidth - AXIS_WIDTH
            val plotHeight = maxHeight - DAY_ROW_HEIGHT
            val columnWidth = plotWidth / columns
            fun x(column: Int): Dp = AXIS_WIDTH + columnWidth * (column + 0.5f)
            fun y(altitude: Double): Dp = plotHeight * (1 - ((altitude - domain.start) / (domain.endInclusive - domain.start)).toFloat())

            Canvas(Modifier.fillMaxSize()) {
                val left = AXIS_WIDTH.toPx()

                for (altitude in profile.altitudes) {
                    val ly = y(altitude.toDouble()).toPx()
                    drawLine(lineColor.copy(alpha = 0.35f), Offset(left, ly), Offset(size.width, ly), strokeWidth = 1.dp.toPx())
                }
                if (snowLine != null) {
                    val ly = y(snowLine.toDouble()).toPx()
                    drawLine(
                        SnowBlue, Offset(left, ly), Offset(size.width, ly), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                    )
                }
                // The freezing level from day to day, kept inside the drawing.
                val freezing = profile.days.mapIndexedNotNull { index, day ->
                    day.freezingLevel?.let { Offset(x(firstDayColumn + index).toPx(), y(profile.clamped(it)).toPx()) }
                }
                if (freezing.size > 1) {
                    val path = Path().apply {
                        moveTo(freezing[0].x, freezing[0].y)
                        freezing.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path, FreezingLevel,
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                val limits = limitPoints.map { (index, altitude) -> Offset(x(firstDayColumn + index).toPx(), y(profile.clamped(altitude.toDouble())).toPx()) }
                if (limits.size > 1) {
                    val path = Path().apply {
                        moveTo(limits[0].x, limits[0].y)
                        limits.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, Rain, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))))
                }
                limits.forEach { drawCircle(Rain, 3.dp.toPx(), it) }
            }

            // The levels on the left, at their altitude.
            profile.days.firstOrNull()?.spots?.forEach { spot ->
                Text(
                    levelMark(spot.level) + " " + metres(spot.altitude, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(AXIS_WIDTH).centredAt(AXIS_WIDTH / 2, y(spot.altitude.toDouble())),
                )
            }
            // The bulletin's snow on the ground, north over south, before the days.
            if (snapshot != null) {
                for (altitude in profile.altitudes) {
                    GroundBadge(
                        snapshot.depth(SnowDepthReport.Side.NORTH, altitude),
                        snapshot.depth(SnowDepthReport.Side.SOUTH, altitude),
                        Modifier.centredAt(x(0), y(altitude.toDouble())),
                    )
                }
                ColumnLabel(stringResource(R.string.ground), Modifier.centredAt(x(0), plotHeight + DAY_ROW_HEIGHT / 2))
            }
            profile.days.forEachIndexed { index, day ->
                val column = firstDayColumn + index
                day.spots.forEach { spot -> SpotBadge(spot, locale, Modifier.centredAt(x(column), y(spot.altitude.toDouble()))) }
                ColumnLabel(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), Modifier.centredAt(x(column), plotHeight + DAY_ROW_HEIGHT / 2))
            }
        }
        Legend(snapshot?.snowLine, hasRainSnowLimit = limitPoints.isNotEmpty(), locale)
    }
}

/**
 * The hours view of the profile: time along the horizontal axis, a day
 * across the width, scrolling sideways from the slot under way. Each slot's
 * badges at the summit and in the village, the freezing level slot by slot,
 * and the bulletin's rain-snow limit at its real six-hour times. The levels
 * and the ground column stay fixed on the left. TalkBack reads one sentence
 * per slot.
 */
@Composable
private fun HoursProfileChart(profile: MountainProfile, bulletin: SnowDepthReport.Massif?, resort: Resort, slots: List<ForecastSlot>, locale: Locale) {
    val snapshot = bulletin?.current
    val snowLine = snapshot?.snowLine?.takeIf { it.toDouble() in profile.altitudeDomain }
    val domain = profile.altitudeDomain
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = resort.timeZone
    val start = slots.first().start
    val end = slots.last().end
    val limits = bulletin?.weather?.periods.orEmpty().mapNotNull { period ->
        val date = period.date ?: return@mapNotNull null
        period.rainSnowLimit?.takeIf { date in start..end }?.let { date to it }
    }
    val description = slots.map { slotSentence(it, resort, locale) }.joinToString("\n")
    val hour = DateTimeFormatter.ofPattern("HH:mm", locale)
    BoxWithConstraints(Modifier.fillMaxWidth().height(CHART_HEIGHT).clearAndSetSemantics { contentDescription = description }) {
        val chartHeight = maxHeight
        val plotHeight = chartHeight - DAY_ROW_HEIGHT * 2
        val groundWidth = if (snapshot != null) 56.dp else 0.dp
        // A day, six slots, across the visible width.
        val slotWidth = (maxWidth - AXIS_WIDTH - groundWidth) / 6
        fun y(altitude: Double): Dp = DAY_ROW_HEIGHT + plotHeight * (1 - ((altitude - domain.start) / (domain.endInclusive - domain.start)).toFloat())
        fun x(instant: Instant): Dp = slotWidth * ((instant.epochSecond - start.epochSecond) / (ForecastSlot.LENGTH * 3600.0)).toFloat()

        Row {
            Box(Modifier.width(AXIS_WIDTH + groundWidth).fillMaxSize()) {
                profile.days.firstOrNull()?.spots?.forEach { spot ->
                    Text(
                        levelMark(spot.level) + " " + metres(spot.altitude, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(AXIS_WIDTH).centredAt(AXIS_WIDTH / 2, y(spot.altitude.toDouble())),
                    )
                }
                if (snapshot != null) {
                    for (altitude in profile.altitudes) {
                        GroundBadge(
                            snapshot.depth(SnowDepthReport.Side.NORTH, altitude),
                            snapshot.depth(SnowDepthReport.Side.SOUTH, altitude),
                            Modifier.centredAt(AXIS_WIDTH + groundWidth / 2, y(altitude.toDouble())),
                        )
                    }
                    ColumnLabel(stringResource(R.string.ground), Modifier.centredAt(AXIS_WIDTH + groundWidth / 2, chartHeight - DAY_ROW_HEIGHT / 2))
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).width(slotWidth * slots.size).fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    for (altitude in profile.altitudes) {
                        val ly = y(altitude.toDouble()).toPx()
                        drawLine(lineColor.copy(alpha = 0.35f), Offset(0f, ly), Offset(size.width, ly), strokeWidth = 1.dp.toPx())
                    }
                    // A line at each midnight, and a light one at each slot.
                    slots.forEachIndexed { index, slot ->
                        val lx = x(slot.start).toPx()
                        val strong = index > 0 && slot.startsDay(zone)
                        drawLine(lineColor.copy(alpha = if (strong) 0.6f else 0.15f), Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 1.dp.toPx())
                    }
                    if (snowLine != null) {
                        val ly = y(snowLine.toDouble()).toPx()
                        drawLine(
                            SnowBlue, Offset(0f, ly), Offset(size.width, ly), strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                        )
                    }
                    fun line(points: List<Offset>, color: Color, on: Float, off: Float) {
                        if (points.size < 2) return
                        val path = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.dp.toPx(), off.dp.toPx()))))
                    }
                    val middle = slotWidth / 2
                    line(
                        slots.mapNotNull { slot -> slot.freezingLevel?.let { Offset((x(slot.start) + middle).toPx(), y(profile.clamped(it)).toPx()) } },
                        FreezingLevel, 6f, 4f,
                    )
                    val limitPoints = limits.map { (date, altitude) -> Offset(x(date).toPx(), y(profile.clamped(altitude.toDouble())).toPx()) }
                    line(limitPoints, Rain, 1f, 3f)
                    limitPoints.forEach { drawCircle(Rain, 3.dp.toPx(), it) }
                }
                slots.forEachIndexed { index, slot ->
                    val centre = x(slot.start) + slotWidth / 2
                    val local = slot.start.atZone(zone)
                    if (index == 0 || slot.startsDay(zone)) {
                        Text(
                            local.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.centredAt(centre, DAY_ROW_HEIGHT / 2),
                        )
                    }
                    ColumnLabel(
                        if (slot.isCurrent) stringResource(R.string.now) else hour.format(local),
                        Modifier.centredAt(centre, chartHeight - DAY_ROW_HEIGHT / 2),
                    )
                    slot.spots.forEach { spot ->
                        spot.level.altitude?.let { SlotBadge(spot, locale, Modifier.centredAt(centre, y(it.toDouble()))) }
                    }
                }
            }
        }
    }
}

/** What falls at one level over a four-hour slot: its pictogram, the snow or the rain, and the highest temperature. */
@Composable
private fun SlotBadge(spot: ForecastSlot.Spot, locale: Locale, modifier: Modifier) {
    val symbol = when (spot.precipitation) {
        MountainProfile.Precipitation.SNOW -> "❄️"
        MountainProfile.Precipitation.RAIN -> "💧"
        MountainProfile.Precipitation.MIXED -> "🌨️"
        MountainProfile.Precipitation.NONE -> conditionSymbol(spot.condition ?: com.tanukee.flakesight.core.WeatherCondition.CLOUDY)
    }
    val snow = spot.snowfallCentimetres?.let { centimetres(it, locale) }
    val amount = snow ?: spot.rainMillimetres?.let { millimetres(it, locale) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(symbol, fontSize = 12.sp)
        if (amount != null) {
            Text(
                amount, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (snow == null) Rain else MaterialTheme.colorScheme.onSurface,
            )
        }
        spot.maxTemperatureDegrees?.let {
            Text(degrees(it), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Places the element with its centre at ([x], [y]) inside the chart box. */
internal fun Modifier.centredAt(x: Dp, y: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx() - placeable.height / 2)
    }
}

@Composable
private fun ColumnLabel(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** What falls at one level on one day: its pictogram and the amount. */
@Composable
private fun SpotBadge(spot: MountainProfile.Spot, locale: Locale, modifier: Modifier) {
    val symbol = when (spot.precipitation) {
        MountainProfile.Precipitation.SNOW -> "❄️"
        MountainProfile.Precipitation.RAIN -> "💧"
        MountainProfile.Precipitation.MIXED -> "🌨️"
        MountainProfile.Precipitation.NONE -> conditionSymbol(spot.forecast.condition)
    }
    val amount = when (spot.precipitation) {
        MountainProfile.Precipitation.SNOW, MountainProfile.Precipitation.MIXED -> spot.forecast.snowfallCentimetres?.let { centimetres(it, locale) }
        MountainProfile.Precipitation.RAIN -> stringResource(R.string.legend_rain)
        MountainProfile.Precipitation.NONE -> null
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Text(symbol, fontSize = 14.sp)
        if (amount != null) {
            Text(
                amount,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (spot.precipitation == MountainProfile.Precipitation.RAIN) Rain else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        // The range the models give, under the amount.
        val range = spot.forecast.snowfallRange
        if (spot.precipitation != MountainProfile.Precipitation.RAIN && range != null) {
            Text(rangeLabel(range, locale), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // The day's highest temperature at this level.
        spot.forecast.maxTemperatureDegrees?.let {
            Text(degrees(it), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The snow on the ground at one level, from the bulletin: north slopes over south, the bulletin's way of giving it. */
@Composable
private fun GroundBadge(north: SnowDepthReading.Depth?, south: SnowDepthReading.Depth?, modifier: Modifier) {
    @Composable
    fun side(letter: String, depth: SnowDepthReading.Depth?) = Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            depth?.let { "${it.centimetres}${if (it.isLowerBound) "+" else ""}" } ?: "?",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .background(SnowBlue.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        side("N", north)
        side("S", south)
    }
}

@Composable
private fun Legend(snowLine: Int?, hasRainSnowLimit: Boolean, locale: Locale) {
    @Composable
    fun dash(color: Color, on: Float, off: Float) = Canvas(Modifier.width(18.dp).height(2.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.dp.toPx(), off.dp.toPx())),
        )
    }
    val style = MaterialTheme.typography.labelMedium
    // Wraps between entries, never inside one.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("❄️ " + stringResource(R.string.legend_snow), style = style, color = SnowBlue, modifier = Modifier.clearAndSetSemantics {})
        Text("💧 " + stringResource(R.string.legend_rain), style = style, color = Rain, modifier = Modifier.clearAndSetSemantics {})
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dash(FreezingLevel, 4f, 3f)
            Text(stringResource(R.string.legend_freezing_level), style = style, modifier = Modifier.clearAndSetSemantics {})
            FreezingLevelInfoButton()
        }
        if (hasRainSnowLimit) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics {}) {
                dash(Rain, 1f, 3f)
                Text(stringResource(R.string.rain_snow), style = style)
            }
        }
        if (snowLine != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics {}) {
                dash(SnowBlue, 2f, 3f)
                Text(stringResource(R.string.snow_from, metres(snowLine, locale)), style = style)
            }
        }
    }
}

/** "Friday: Summit, 2,100 m: 38 cm of snow. Village, 1,600 m: rain. 0 °C at 1,700 m.", for TalkBack. */
@Composable
private fun daySummary(day: MountainProfile.Day, locale: Locale): String {
    val weekday = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    val sentences = day.spots.map { spot ->
        val what = when (spot.precipitation) {
            MountainProfile.Precipitation.SNOW -> listOf(stringResource(R.string.amount_of_snow, centimetres(spot.forecast.snowfallCentimetres ?: 0, locale)))
            MountainProfile.Precipitation.MIXED -> listOf(
                stringResource(R.string.amount_of_snow, centimetres(spot.forecast.snowfallCentimetres ?: 0, locale)),
                stringResource(R.string.rain_spoken),
            )
            MountainProfile.Precipitation.RAIN -> listOf(stringResource(R.string.rain_spoken))
            MountainProfile.Precipitation.NONE -> listOf(conditionLabel(spot.forecast.condition))
        } + listOfNotNull(
            spot.forecast.snowfallRange?.let { spokenRange(it, locale) },
            spot.forecast.maxTemperatureDegrees?.let { stringResource(R.string.max_temperature, degrees(it)) },
        )
        stringResource(R.string.day_summary, levelName(spot.level, locale), what.joinToString(", "))
    } + listOfNotNull(day.roundedFreezingLevel?.let { stringResource(R.string.freezing_level_at, metres(it, locale)) })
    return stringResource(R.string.day_summary, weekday, sentences.joinToString(". ")) + "."
}

/**
 * Each weather model's daily snowfall at one level, and how far they agree:
 * the figures behind the ranges, for those who want to see who says what.
 * Port of `WeatherModelsTable`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherModelsTable(forecast: ResortForecast, models: ModelSnowfall?) {
    val locale = LocalConfiguration.current.locales[0]
    var levelIndex by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.weather_models), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            WeatherModelsInfoButton()
        }
        Text(stringResource(R.string.models_footnote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (forecast.levels.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                forecast.levels.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = index == levelIndex,
                        onClick = { levelIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index, forecast.levels.size),
                    ) { Text(levelName(level.level, locale), maxLines = 1) }
                }
            }
        }
        val level = forecast.levels.getOrElse(levelIndex) { forecast.levels.first() }
        val amounts = models?.levels?.firstOrNull { it.level == level.level }
        if (amounts == null) {
            Text(stringResource(R.string.models_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val style = MaterialTheme.typography.bodySmall
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        val number = java.text.NumberFormat.getIntegerInstance(locale)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Spacer(Modifier.weight(1.6f))
                level.days.forEach { Text(it.weekdayLabel(locale), style = style, color = secondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
            }
            HorizontalDivider()
            SnowfallRange.MODEL_NAMES.forEachIndexed { model, name ->
                Row {
                    Text(name, style = style, maxLines = 1, modifier = Modifier.weight(1.6f))
                    level.days.forEach { day ->
                        val value = amounts.days[day.date]?.getOrNull(model)
                        Text(
                            // Whole centimetres; under one rounds to 0.
                            value?.let { number.format(DailyForecast.centimetres(it) ?: 0) } ?: "-",
                            style = style,
                            color = if (value == null) secondary else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider()
            Row {
                Text(stringResource(R.string.agreement), style = style, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.6f))
                level.days.forEach { day ->
                    val confidence = day.snowfallRange?.confidence
                    Text(
                        when (confidence) {
                            ForecastConfidence.HIGH -> stringResource(R.string.agreement_strong)
                            ForecastConfidence.LOW -> stringResource(R.string.agreement_weak)
                            null -> "-"
                        },
                        style = style,
                        fontWeight = if (confidence != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (confidence == ForecastConfidence.HIGH) MaterialTheme.colorScheme.primary else secondary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** "Summit, 2,100 m", "Village, 1,600 m" or "Resort". */
@Composable
private fun levelName(level: ForecastLevel, locale: Locale) = when (level) {
    is ForecastLevel.Summit -> stringResource(R.string.level_summit, metres(level.altitude, locale))
    is ForecastLevel.Village -> stringResource(R.string.level_village, metres(level.altitude, locale))
    ForecastLevel.ResortCentre -> stringResource(R.string.level_resort)
}

private fun levelMark(level: ForecastLevel) = when (level) {
    is ForecastLevel.Summit -> "▲"
    is ForecastLevel.Village -> "▼"
    ForecastLevel.ResortCentre -> "●"
}

