package com.tanukee.flakesight

import android.icu.text.DateFormat
import android.icu.text.ListFormatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanukee.flakesight.core.AvalancheRisk
import com.tanukee.flakesight.core.ForecastLevel
import com.tanukee.flakesight.core.FreshSnow
import com.tanukee.flakesight.core.MountainWeather
import com.tanukee.flakesight.core.SlopeAspect
import com.tanukee.flakesight.core.SnowDepthReading
import com.tanukee.flakesight.core.SnowDepthReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where the full bulletins are, for anyone going off-piste. */
private const val BULLETINS_URL = "https://meteofrance.com/meteo-montagne"

/**
 * The rest of the massif's Météo-France avalanche bulletin on the advanced
 * weather screen, under the mountain profile: the danger level with its most
 * exposed slopes, the fresh snow of the last and coming days, the week's snow
 * depth, and the rain-snow limit, freezing level and wind every six hours.
 * Each part shows only when the bulletin has it. Port of `BulletinSection`.
 */
@Composable
fun BulletinSection(reading: SnowDepthReading, bulletin: SnowDepthReport.Massif, zone: ZoneId) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.avalanche_bulletin), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(issueLine(bulletin, locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bulletin.avalancheRisk?.let { AvalancheRiskCard(it, bulletin.exposedAspects, reading) }
        bulletin.freshSnow?.takeIf { it.days.isNotEmpty() }?.let { FreshSnowChart(it, locale) }
        bulletin.history?.takeIf { it.size > 1 }?.let { DepthHistoryChart(it, reading, locale) }
        bulletin.weather?.takeIf { it.periods.isNotEmpty() }?.let { RainSnowChart(it, reading, zone, locale) }
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(BULLETINS_URL) }, contentPadding = PaddingValues(0.dp)) {
            Text("↗ " + stringResource(R.string.read_full_bulletin))
        }
        Text(stringResource(R.string.bulletin_licence), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Mont-Blanc massif, issued 23 Nov, 15:30", French time as the bulletin. */
@Composable
private fun issueLine(bulletin: SnowDepthReport.Massif, locale: Locale): String {
    val date = bulletin.issueDate ?: return bulletin.name
    val format = DateFormat.getInstanceForSkeleton("dMMMHHmm", locale).apply {
        timeZone = android.icu.util.TimeZone.getTimeZone(SnowDepthReport.ZONE.id)
    }
    return stringResource(R.string.massif_issued, bulletin.name, format.format(Date.from(date)))
}

/** A titled card, as the other parts of the advanced screen. */
@Composable
private fun BulletinCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

// Danger level

/**
 * The danger level below and above the bulletin's boundary, drawn as two bands
 * with the resort's levels marked, the next day's highest level, and the rose
 * of the most exposed slopes.
 */
@Composable
private fun AvalancheRiskCard(risk: AvalancheRisk, aspects: List<SlopeAspect>, reading: SnowDepthReading) {
    val locale = LocalConfiguration.current.locales[0]
    val aspectNames = aspects.map { aspectAbbreviation(it) }
    val exposedSpoken = stringResource(R.string.most_exposed_slopes_spoken, ListFormatter.getInstance(locale).format(aspectNames))
    BulletinCard(stringResource(R.string.avalanche_risk), reading.avalancheSummary()?.let { avalancheSummaryLabel(it) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RiskBands(risk, reading, locale, Modifier.width(150.dp).height(120.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = exposedSpoken },
            ) {
                SlopeRose(aspects.toSet(), risk.maximum, Modifier.size(96.dp))
                Text(stringResource(R.string.most_exposed_slopes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        risk.tomorrowMaximum?.let { tomorrow ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(avalancheColor(tomorrow)), contentAlignment = Alignment.Center) {
                    Text(tomorrow.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (tomorrow >= 4) Color.White else Color.Black)
                }
                Text(stringResource(R.string.tomorrow_up_to, avalancheLevelLabel(tomorrow)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.risk_whole_massif), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two coloured bands, the level above the boundary over the one below, with the boundary and the resort's levels marked. */
@Composable
private fun RiskBands(risk: AvalancheRisk, reading: SnowDepthReading, locale: Locale, modifier: Modifier) {
    val altitudes = reading.levels.mapNotNull { it.level.altitude }
    // From a little below the village to a little above the summit, always including the boundary.
    val values = altitudes.map { it.toDouble() } + listOfNotNull(risk.boundary?.toDouble())
    val low = values.minOrNull() ?: 1500.0
    val high = values.maxOrNull() ?: 2500.0
    val margin = max((high - low) * 0.15, 150.0)
    val bottom = low - margin
    val top = high + margin
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).clearAndSetSemantics {}) {
        val height = maxHeight
        fun y(altitude: Double) = height * (1 - ((altitude - bottom) / (top - bottom)).toFloat())
        val boundaryY = risk.boundary?.takeIf { risk.isSplit }?.let { y(it.toDouble()) }
        Column(Modifier.fillMaxSize()) {
            if (boundaryY != null) {
                Band(risk.above, Modifier.fillMaxWidth().height(boundaryY))
                Band(risk.below, Modifier.fillMaxWidth().weight(1f))
            } else {
                Band(risk.below, Modifier.fillMaxSize())
            }
        }
        if (boundaryY != null) {
            Text(
                metres(risk.boundary!!, locale),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.centredAt(maxWidth - 34.dp, boundaryY).clip(RoundedCornerShape(50)).background(surface).padding(horizontal = 4.dp),
            )
        }
        reading.levels.forEach { level ->
            val altitude = level.level.altitude ?: return@forEach
            Text(
                levelArrow(level.level) + " " + metres(altitude, locale),
                fontSize = 10.sp,
                modifier = Modifier.centredAt(44.dp, y(altitude.toDouble())).clip(RoundedCornerShape(50)).background(surface).padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Band(level: Int, modifier: Modifier) {
    Box(modifier.background(avalancheColor(level).copy(alpha = 0.85f)).padding(end = 10.dp), contentAlignment = Alignment.CenterEnd) {
        Text(level.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (level >= 4) Color.White else Color.Black)
    }
}

/** Eight wedges round a compass, the most exposed aspects filled in the danger level's colour. */
@Composable
private fun SlopeRose(exposed: Set<SlopeAspect>, level: Int, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val unexposed = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val letters = listOf(SlopeAspect.NORTH, SlopeAspect.EAST, SlopeAspect.SOUTH, SlopeAspect.WEST).map { it to aspectAbbreviation(it) }
    val letterColor = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val radius = side / 2 - 12.dp
        Canvas(Modifier.fillMaxSize()) {
            val r = radius.toPx()
            val topLeft = Offset(center.x - r, center.y - r)
            for (aspect in SlopeAspect.entries) {
                val start = (aspect.degrees - 90 - 22.5).toFloat()
                drawArc(if (aspect in exposed) avalancheColor(level) else unexposed, start, 45f, true, topLeft, Size(r * 2, r * 2))
                drawArc(background, start, 45f, true, topLeft, Size(r * 2, r * 2), style = Stroke(1.5.dp.toPx()))
            }
            drawCircle(background, r * 0.25f, center)
        }
        letters.forEach { (aspect, letter) ->
            val angle = Math.toRadians(aspect.degrees - 90)
            val reach = radius + 7.dp
            Text(
                letter,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = letterColor,
                modifier = Modifier.centredAt(side / 2 + reach * cos(angle).toFloat(), side / 2 + reach * sin(angle).toFloat()),
            )
        }
    }
}

@Composable
private fun aspectAbbreviation(aspect: SlopeAspect) = stringResource(
    when (aspect) {
        SlopeAspect.NORTH -> R.string.aspect_n
        SlopeAspect.NORTH_EAST -> R.string.aspect_ne
        SlopeAspect.EAST -> R.string.aspect_e
        SlopeAspect.SOUTH_EAST -> R.string.aspect_se
        SlopeAspect.SOUTH -> R.string.aspect_s
        SlopeAspect.SOUTH_WEST -> R.string.aspect_sw
        SlopeAspect.WEST -> R.string.aspect_w
        SlopeAspect.NORTH_WEST -> R.string.aspect_nw
    },
)

// Fresh snow

/** The fresh snow over 24 hours: solid up to the bulletin's low figure, fading to its high one; the forecast days lighter and labelled. */
@Composable
private fun FreshSnowChart(freshSnow: FreshSnow, locale: Locale) {
    val ceiling = (freshSnow.days.maxOf { it.maximum } * 1.35 + 2).toFloat()
    BulletinCard(stringResource(R.string.fresh_snow_24_hours), stringResource(R.string.fresh_snow_subtitle, metres(freshSnow.altitude, locale))) {
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            freshSnow.days.forEach { day ->
                val label = freshSnowLabel(day)
                val spoken = if (day.isForecast) stringResource(R.string.amount_forecast, label) else label
                val dayName = day.day?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${it.dayOfMonth}" } ?: day.date
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = "$dayName: $spoken" },
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (day.isForecast) {
                        Text(stringResource(R.string.forecast_short), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val solid = if (day.maximum > 0) day.minimum.toFloat() / day.maximum else 1f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((max(day.maximum, 1) / ceiling).coerceIn(0.02f, 0.8f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.verticalGradient(
                                    0f to SnowBlue.copy(alpha = 0.15f),
                                    (1 - solid) to SnowBlue,
                                    1f to SnowBlue,
                                ),
                                alpha = if (day.isForecast) 0.45f else 1f,
                            ),
                    )
                    Text(dayName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** "30-40 cm", "5 cm" when both ends agree. */
@Composable
private fun freshSnowLabel(day: FreshSnow.Day): String {
    val number = java.text.NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val range = if (day.minimum == day.maximum) number.format(day.minimum) else "${number.format(day.minimum)}-${number.format(day.maximum)}"
    return stringResource(R.string.depth_figure_cm, range)
}

// The week's depth

/** The snow depth at each of the bulletin's altitudes over the past week, one line per altitude. */
@Composable
private fun DepthHistoryChart(history: List<SnowDepthReport.Snapshot>, reading: SnowDepthReading, locale: Locale) {
    val altitudes = history.flatMap { snapshot -> snapshot.levels.map { it.altitude } }.distinct().sortedDescending()
    val shades = listOf(1f, 0.65f, 0.35f)
    val series = altitudes.map { altitude -> altitude to history.map { it.depth(altitude)?.centimetres } }
    val deepest = series.flatMap { it.second }.filterNotNull().maxOrNull()?.coerceAtLeast(10) ?: 10
    val ceiling = deepest * 1.1f
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val subtitle = changeLabel(reading, locale)?.let { stringResource(R.string.at_the_summit_change, it) }
    val dayLabels = history.map { snapshot -> snapshot.observationDate?.dayOfWeek?.getDisplayName(TextStyle.NARROW, locale) ?: "" }
    val axisLabels = listOf(0, deepest / 2, deepest).map { stringResource(R.string.depth_figure_cm, it.toString()) }
    BulletinCard(stringResource(R.string.snow_depth_this_week), subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clearAndSetSemantics {}) {
            series.forEachIndexed { index, (altitude, _) ->
                Text("● " + metres(altitude, locale), fontSize = 11.sp, color = SnowBlue.copy(alpha = shades.getOrElse(index) { 0.35f }))
            }
        }
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            Column(Modifier.width(44.dp).fillMaxHeight().padding(bottom = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                axisLabels.reversed().forEach { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val step = if (history.size > 1) size.width / (history.size - 1) else 0f
                    fun point(index: Int, depth: Int) = Offset(index * step, size.height * (1 - depth / ceiling))
                    listOf(0f, 0.5f, 1f).forEach { f ->
                        val gy = size.height * (1 - f * deepest / ceiling)
                        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
                    }
                    series.forEachIndexed { s, (_, depths) ->
                        val color = SnowBlue.copy(alpha = shades.getOrElse(s) { 0.35f })
                        val points = depths.mapIndexedNotNull { i, depth -> depth?.let { point(i, it) } }
                        if (points.isEmpty()) return@forEachIndexed
                        val area = Path().apply {
                            moveTo(points.first().x, size.height)
                            points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, size.height)
                            close()
                        }
                        drawPath(area, color.copy(alpha = 0.18f * color.alpha))
                        val line = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(line, color, style = Stroke(2.dp.toPx()))
                        points.forEach { drawCircle(color, 2.5.dp.toPx(), it) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    dayLabels.forEach { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

// Rain, snow, freezing level and wind

/**
 * Every six hours: where it rains (below the rain-snow limit) and where it
 * snows, the freezing level, the resort's summit and village, and the wind at
 * the bulletin's two altitudes.
 */
@Composable
private fun RainSnowChart(weather: MountainWeather, reading: SnowDepthReading, zone: ZoneId, locale: Locale) {
    val periods = weather.periods.mapNotNull { period -> period.date?.let { it to period } }
    val hasBands = weather.periods.any { it.rainSnowLimit != null }
    val values = reading.levels.mapNotNull { it.level.altitude?.toDouble() } +
        weather.periods.mapNotNull { it.rainSnowLimit?.toDouble() } +
        weather.periods.mapNotNull { it.freezingLevel?.toDouble() }
    val low = values.minOrNull() ?: 1000.0
    val high = values.maxOrNull() ?: 3000.0
    val margin = max((high - low) * 0.1, 150.0)
    val bottom = max(0.0, low - margin)
    val top = high + margin
    fun share(altitude: Double) = (1 - (altitude - bottom) / (top - bottom)).toFloat()
    fun hourLabel(date: Instant) = date.atZone(zone).let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${"%02d".format(it.hour)}" }
    val levelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val unknown = "?"
    val spoken = periods.map { (date, period) ->
        stringResource(
            R.string.rain_snow_limit_spoken,
            hourLabel(date),
            period.rainSnowLimit?.let { metres(it, locale) } ?: unknown,
            period.freezingLevel?.let { metres(it, locale) } ?: unknown,
        )
    }.joinToString(" ")

    BulletinCard(stringResource(if (hasBands) R.string.rain_or_snow else R.string.freezing_level_and_wind), stringResource(R.string.every_six_hours)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp).clearAndSetSemantics { contentDescription = spoken }) {
            val axisWidth = 52.dp
            val plotWidth = maxWidth - axisWidth
            val labelRow = 16.dp
            val plotHeight = maxHeight - labelRow
            val columnWidth = plotWidth / max(periods.size, 1)
            Canvas(Modifier.width(plotWidth).height(plotHeight)) {
                val column = size.width / max(periods.size, 1)
                val freezing = mutableListOf<Offset>()
                periods.forEachIndexed { index, (_, period) ->
                    val left = index * column
                    period.rainSnowLimit?.let { limit ->
                        val ly = size.height * share(limit.toDouble())
                        drawRect(Rain.copy(alpha = 0.35f), Offset(left + 1, ly), Size(column - 2, size.height - ly))
                        drawRect(SnowBlue.copy(alpha = 0.3f), Offset(left + 1, 0f), Size(column - 2, ly))
                    }
                    period.freezingLevel?.let { freezing += Offset(left + column / 2, size.height * share(it.toDouble())) }
                }
                if (freezing.size > 1) {
                    val path = Path().apply {
                        moveTo(freezing[0].x, freezing[0].y)
                        freezing.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, FreezingLevel, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                }
                freezing.forEach { drawCircle(FreezingLevel, 2.5.dp.toPx(), it) }
                reading.levels.mapNotNull { it.level.altitude }.forEach { altitude ->
                    val ly = size.height * share(altitude.toDouble())
                    drawLine(levelColor, Offset(0f, ly), Offset(size.width, ly), 1.dp.toPx())
                }
            }
            // The resort's levels, just above their line.
            reading.levels.forEach { level ->
                val altitude = level.level.altitude ?: return@forEach
                Text(
                    levelArrow(level.level) + " " + metres(altitude, locale),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.centredAt(30.dp, plotHeight * share(altitude.toDouble()) - 7.dp),
                )
            }
            // Altitudes on the right.
            listOf(bottom + (top - bottom) * 0.15, (bottom + top) / 2, top - (top - bottom) * 0.15).forEach { altitude ->
                val rounded = (altitude / 100).toInt() * 100
                Text(
                    metres(rounded, locale),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.centredAt(plotWidth + axisWidth / 2, plotHeight * share(rounded.toDouble())),
                )
            }
            periods.forEachIndexed { index, (date, _) ->
                Text(
                    hourLabel(date),
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.centredAt(columnWidth * (index + 0.5f), plotHeight + labelRow / 2),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics {}) {
            if (hasBands) {
                Swatch(SnowBlue.copy(alpha = 0.3f), stringResource(R.string.legend_snow))
                Swatch(Rain.copy(alpha = 0.35f), stringResource(R.string.legend_rain))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(2.dp).background(FreezingLevel))
                Text(stringResource(R.string.legend_freezing_level), style = MaterialTheme.typography.labelSmall)
            }
        }
        WindRows(weather, periods, locale, ::hourLabel)
    }
}

@Composable
private fun Swatch(color: Color, title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(title, style = MaterialTheme.typography.labelSmall)
    }
}

/** The wind at each of the two altitudes: an arrow pointing where it blows and its speed, under each period. */
@Composable
private fun WindRows(
    weather: MountainWeather,
    periods: List<Pair<Instant, MountainWeather.Period>>,
    locale: Locale,
    hourLabel: (Instant) -> String,
) {
    val spoken = periods.map { (date, period) ->
        val winds = weather.windAltitudes.zip(period.wind).mapNotNull { (altitude, wind) ->
            if (altitude == null || wind == null) return@mapNotNull null
            stringResource(R.string.wind_spoken, wind.speed, wind.aspect?.let { aspectAbbreviation(it) } ?: "", metres(altitude, locale))
        }
        "${hourLabel(date)}: ${winds.joinToString(", ")}."
    }.joinToString(" ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        weather.windAltitudes.forEachIndexed { index, altitude ->
            if (altitude == null) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metres(altitude, locale), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(58.dp))
                periods.forEach { (_, period) ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { WindCell(period.wind.getOrNull(index)) }
                }
            }
        }
    }
}

/** An arrow pointing where the wind blows (it comes from the bulletin's direction) and its speed, stronger in bolder colour. */
@Composable
private fun WindCell(wind: MountainWeather.Wind?) {
    if (wind == null) {
        Text("-", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        return
    }
    val color = when {
        wind.speed >= 60 -> Color(0xFFE53935)
        wind.speed >= 40 -> FreezingLevel
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val aspect = wind.aspect
        Text(
            "↑",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (aspect == null) Color.Transparent else color,
            modifier = Modifier.rotate(((aspect?.degrees ?: 0.0) + 180).toFloat()),
        )
        Text(wind.speed.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun levelArrow(level: ForecastLevel) = when (level) {
    is ForecastLevel.Summit -> "▲"
    is ForecastLevel.Village -> "▼"
    ForecastLevel.ResortCentre -> "●"
}
