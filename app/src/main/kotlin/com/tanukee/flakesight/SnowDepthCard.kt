package com.tanukee.flakesight

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalDensity

import android.icu.text.DateFormat
import android.icu.text.ListFormatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanukee.flakesight.core.AvalancheRisk
import com.tanukee.flakesight.core.ForecastLevel
import com.tanukee.flakesight.core.SnowDepthReading
import com.tanukee.flakesight.core.SnowDepthReport
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The snow layer's outline, the iOS `SnowPalette.snow` blue. */
internal val SnowBlue = Color(0xFF5B9BD5)

/**
 * The snow on the ground from the massif's avalanche bulletin, drawn as a
 * mountain whose snow layer is as thick as the bulletin says at each
 * altitude: north slopes on the left, south on the right (both named under
 * the drawing), from the snow line up. Port of `SnowyMountainCard`, shown in
 * the advanced weather's bulletin section; the resort screen keeps the depths
 * as a tile ([ResortConditionTiles]).
 */
@Composable
fun SnowyMountainCard(reading: SnowDepthReading, onClick: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val change = changeLabel(reading, locale)
    val description = listOfNotNull(spokenSnowDepth(reading, locale), change).joinToString(" ")
    val source = sourceLabel(reading, locale)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(SnowBlue.copy(alpha = 0.1f))
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = "$description $source" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("❄️ " + stringResource(R.string.snow_on_the_ground), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (change != null) {
                val arrow = when (reading.isGrowing) {
                    true -> "↗ "
                    false -> "↘ "
                    null -> ""
                }
                Text(
                    arrow + change,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SnowyMountain(reading, locale, Modifier.fillMaxWidth().height(132.dp))
        // The drawing's flanks: north slopes on the left, south on the right.
        Row(Modifier.fillMaxWidth().padding(top = 0.dp)) {
            val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.north), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.south), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The drawing itself: a mountain, its snow, the snow line and the levels. */
@Composable
private fun SnowyMountain(reading: SnowDepthReading, locale: Locale, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val snapshot = reading.bulletin?.current
    val altitudes = reading.levels.mapNotNull { it.level.altitude }
    val summit = (altitudes.maxOrNull() ?: 2500).toDouble()
    val snowLine = snapshot?.snowLine
    val snowLineLabel = snowLine?.let { stringResource(R.string.snow_line, metres(it, locale)) }
    val levelLabels = reading.levels.mapNotNull { level ->
        level.level.altitude?.let { it to "${levelArrow(level.level)} ${metres(it, locale)}  ${depthLabel(level.depth, locale)}" }
    }
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)

    // Altitudes drawn: a little below the lowest of the village and the snow line, to a little above the summit.
    var bottom = (altitudes.minOrNull() ?: 1500).toDouble()
    if (snowLine != null) bottom = min(bottom, snowLine.toDouble())
    bottom = min(bottom, summit - 400)
    val margin = (summit - bottom) * 0.12
    val low = bottom - margin
    val high = summit + margin * 0.6
    // Centimetres for the thickest drawn snow: the deepest of the day, never less than a metre, so a thin cover looks thin.
    val depthScale = max(snapshot?.levels?.flatMap { listOfNotNull(it.north, it.south) }?.maxOrNull() ?: 100, 100).toDouble()

    Canvas(modifier) {
        val peakX = size.width * 0.58f
        val leftFoot = size.width * 0.02f
        val rightFoot = size.width * 0.98f
        fun y(altitude: Double) = size.height * (1 - ((altitude - low) / (high - low)).toFloat())
        fun rise(altitude: Double) = ((altitude - low) / (summit - low)).toFloat()
        fun north(altitude: Double) = Offset(leftFoot + (peakX - leftFoot) * rise(altitude), y(altitude))
        fun south(altitude: Double) = Offset(rightFoot - (rightFoot - peakX) * rise(altitude), y(altitude))

        val outline = Path().apply {
            moveTo(0f, size.height)
            north(low).let { lineTo(it.x, it.y) }
            north(summit).let { lineTo(it.x, it.y) }
            south(low).let { lineTo(it.x, it.y) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(outline, Brush.verticalGradient(listOf(outlineColor.copy(alpha = 0.28f), outlineColor.copy(alpha = 0.08f))))

        if (snapshot != null) {
            // Along each flank from the snow line to the peak, as deep below the surface as the bulletin's depth on that side.
            val start = max(low, (snowLine ?: low.toInt()).toDouble())
            if (start < summit) {
                val maximumThickness = 26.dp.toPx()
                val altitudesDrawn = (0..24).map { start + (summit - start) * it / 24 }
                fun thickness(side: SnowDepthReport.Side, altitude: Double): Float {
                    val depth = snapshot.depth(side, altitude.toInt())?.centimetres ?: 0
                    return maximumThickness * min(depth / depthScale, 1.0).toFloat()
                }
                val snow = Path().apply {
                    north(start).let { moveTo(it.x, it.y) }
                    altitudesDrawn.forEach { a -> north(a).let { lineTo(it.x, it.y) } }
                    altitudesDrawn.reversed().forEach { a -> south(a).let { lineTo(it.x, it.y) } }
                    altitudesDrawn.forEach { a -> south(a).let { lineTo(it.x, it.y + thickness(SnowDepthReport.Side.SOUTH, a)) } }
                    altitudesDrawn.reversed().forEach { a -> north(a).let { lineTo(it.x, it.y + thickness(SnowDepthReport.Side.NORTH, a)) } }
                    close()
                }
                drawPath(snow, Color.White)
                drawPath(snow, SnowBlue.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
            }
            if (snowLine != null && snowLineLabel != null && snowLine.toDouble() in low..high) {
                val lineY = y(snowLine.toDouble())
                drawLine(
                    outlineColor, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
                drawText(measurer, snowLineLabel, Offset(2.dp.toPx(), lineY + 2.dp.toPx()), TextStyle(fontSize = 10.sp, color = outlineColor))
            }
        }

        // Each level beside the south flank at its altitude, kept inside the drawing.
        for ((altitude, label) in levelLabels) {
            val layout = measurer.measure(label, labelStyle)
            val point = south(altitude.toDouble())
            val x = min(max(point.x + 52.dp.toPx(), 80.dp.toPx()), size.width - 80.dp.toPx())
            val centreY = min(max(point.y, 10.dp.toPx()), size.height - 10.dp.toPx())
            val padding = Offset(6.dp.toPx(), 2.dp.toPx())
            val topLeft = Offset(x - layout.size.width / 2f, centreY - layout.size.height / 2f)
            drawRoundRect(
                labelBackground,
                topLeft - padding,
                Size(layout.size.width + padding.x * 2, layout.size.height + padding.y * 2),
                CornerRadius(layout.size.height.toFloat()),
            )
            drawText(layout, topLeft = topLeft)
        }
    }
}

/**
 * Two tiles under the weather card, from the massif's avalanche bulletin: the
 * danger level alone, in the European scale's colour and word, and the snow
 * on the ground at the summit and in the village with the week's change. Both
 * open the advanced weather, which has the detail. Square on a phone; on a
 * wide window a fixed height, so a square does not push the webcams away; in
 * large text they stack. Port of `ResortConditionTiles`.
 */
@Composable
fun ResortConditionTiles(reading: SnowDepthReading, onClick: (() -> Unit)?) {
    val locale = LocalConfiguration.current.locales[0]
    val stacks = LocalDensity.current.fontScale >= 1.5f
    val hint = stringResource(R.string.opens_advanced_weather)
    BoxWithConstraints {
        val tile: Modifier = when {
            stacks -> Modifier.fillMaxWidth()
            maxWidth >= 600.dp -> Modifier.height(190.dp)
            else -> Modifier.aspectRatio(1f)
        }
        @Composable
        fun Tile(background: Color, spoken: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
            modifier = modifier
                .then(tile)
                .clip(RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClickLabel = hint, onClick = onClick) else Modifier)
                .background(background)
                .padding(12.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        ) { content() }

        @Composable
        fun Title(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f))
            if (onClick != null) Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        @Composable
        fun Tiles(modifier: Modifier) {
            val level = reading.avalancheLevel
            if (level != null) {
                Tile(avalancheColor(level).copy(alpha = 0.16f), stringResource(R.string.avalanche_risk_spoken, avalancheLevelLabel(level)), modifier) {
                    Title(stringResource(R.string.avalanche_risk))
                    Spacer(Modifier.weight(1f))
                    // The level's number on its colour, as on the bulletin's pictogram.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(64.dp).clip(RoundedCornerShape(12.dp)).background(avalancheColor(level)),
                    ) {
                        Text(level.toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (level >= 4) Color.White else Color.Black)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        avalancheLevelName(level), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
            val change = changeLabel(reading, locale)
            Tile(SnowBlue.copy(alpha = 0.12f), listOfNotNull(spokenSnowDepth(reading, locale), change).joinToString(" "), modifier) {
                Title(stringResource(R.string.snow_on_the_ground))
                Spacer(Modifier.weight(1f))
                reading.levels.forEach { level ->
                    Text(
                        levelArrow(level.level) + " " + (level.level.altitude?.let { metres(it, locale) } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                    Text(
                        depthLabel(level.depth, locale),
                        style = if (level == reading.top) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (change != null) Text(change, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            // Keeps the snow tile at half the width.
            if (level == null && !stacks) Spacer(modifier)
        }

        if (stacks) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Tiles(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Tiles(Modifier.weight(1f)) }
        }
    }
}

/** The European avalanche danger scale's colours, as Météo-France and every Alpine bulletin use them. */
internal fun avalancheColor(level: Int) = when (level) {
    1 -> Color(0xFFCCFF66)
    2 -> Color(0xFFFFE600)
    3 -> Color(0xFFFF9900)
    4 -> Color(0xFFE61A1A)
    else -> Color(0xFF8C0000)
}

/** "4/5 High". */
@Composable
internal fun avalancheLevelLabel(level: Int): String = stringResource(R.string.avalanche_level, level, avalancheLevelName(level))

/** "High", the European scale's word for the level. */
@Composable
internal fun avalancheLevelName(level: Int): String {
    return stringResource(
        when (level) {
            1 -> R.string.avalanche_low
            2 -> R.string.avalanche_moderate
            3 -> R.string.avalanche_considerable
            4 -> R.string.avalanche_high
            else -> R.string.avalanche_very_high
        },
    )
}

/** "4/5 High above 2,200 m, 3/5 Considerable below", or "3/5 Considerable". */
@Composable
internal fun avalancheSummaryLabel(summary: AvalancheRisk.Summary): String = when (summary) {
    is AvalancheRisk.Summary.Single -> avalancheLevelLabel(summary.level)
    is AvalancheRisk.Summary.Split -> stringResource(
        R.string.avalanche_split,
        avalancheLevelLabel(summary.above),
        metres(summary.boundary, LocalConfiguration.current.locales[0]),
        avalancheLevelLabel(summary.below),
    )
}

/** "+55 cm in 7 days", "-10 cm in 7 days" or "No change in 7 days" at the top level; `null` without the week's history. */
@Composable
internal fun changeLabel(reading: SnowDepthReading, locale: Locale): String? {
    val (centimetres, days) = reading.change() ?: return null
    return when {
        centimetres > 0 -> stringResource(R.string.snow_change_up, centimetres(centimetres, locale), days)
        centimetres < 0 -> stringResource(R.string.snow_change_down, centimetres(-centimetres, locale), days)
        else -> stringResource(R.string.snow_change_none, days)
    }
}

/** "Off-piste, Météo-France avalanche bulletin of 21 Nov": what the figures are and the day they describe, as the licence asks. */
@Composable
private fun sourceLabel(reading: SnowDepthReading, locale: Locale): String {
    val format = DateFormat.getInstanceForSkeleton("dMMM", locale).apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
    val day = format.format(Date.from(reading.observedOn.atStartOfDay().toInstant(ZoneOffset.UTC)))
    return stringResource(R.string.bulletin_source, day)
}

/** "105 cm", or "125+ cm" above the bulletin's altitudes. */
@Composable
private fun depthLabel(depth: SnowDepthReading.Depth, locale: Locale): String {
    val number = java.text.NumberFormat.getIntegerInstance(locale).format(depth.centimetres)
    return stringResource(R.string.depth_figure_cm, if (depth.isLowerBound) "$number+" else number)
}

/** "▲ 105 ▼ 60 cm", or "▲ 125+ ▼ 105 cm" above the bulletin's altitudes, for the resort rows. */
@Composable
internal fun compactSnowDepth(reading: SnowDepthReading, locale: Locale): String {
    val number = java.text.NumberFormat.getIntegerInstance(locale)
    val figures = reading.levels.joinToString(" ") { level ->
        val figure = number.format(level.depth.centimetres) + if (level.depth.isLowerBound) "+" else ""
        "${levelArrow(level.level)} $figure"
    }
    return stringResource(R.string.depth_figure_cm, figures)
}

/** "Snow depth: at least 125 cm at the summit and 105 cm in the village.", for TalkBack. */
@Composable
internal fun spokenSnowDepth(reading: SnowDepthReading, locale: Locale): String {
    val parts = reading.levels.map { level ->
        val amount = centimetres(level.depth.centimetres, locale)
        val quantity = if (level.depth.isLowerBound) stringResource(R.string.at_least, amount) else amount
        "$quantity ${placePhrase(level.level)}"
    }
    return stringResource(R.string.snow_depth_spoken, ListFormatter.getInstance(locale).format(parts))
}

/** The summit and village marks of the forecast strip; a bulletin never reaches the resort centre. */
private fun levelArrow(level: ForecastLevel) = when (level) {
    is ForecastLevel.Summit -> "▲"
    is ForecastLevel.Village -> "▼"
    ForecastLevel.ResortCentre -> "●"
}
