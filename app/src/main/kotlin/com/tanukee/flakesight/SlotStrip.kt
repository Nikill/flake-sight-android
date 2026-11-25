package com.tanukee.flakesight

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanukee.flakesight.core.DailyForecast
import com.tanukee.flakesight.core.ForecastLevel
import com.tanukee.flakesight.core.ForecastSlot
import com.tanukee.flakesight.core.MountainProfile
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.WeatherCondition
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val COLUMN_WIDTH = 50.dp
private val HEADER_HEIGHT = 34.dp
private val BAR_HEIGHT = 26.dp
/** One level's cell: pictogram, bar, amount and temperature. */
private val LEVEL_HEIGHT = 24.dp + BAR_HEIGHT + 32.dp + 8.dp

/**
 * The coming four-hour slots side by side, in a row that scrolls sideways
 * from the slot under way, with the levels fixed on the left: for each level
 * a pictogram, a snow bar, the snow (or the rain) and the highest
 * temperature. The day's name opens each day, a thin line marks midnight.
 * Not a link: the row scrolls instead. Port of `SlotStrip`.
 */
@Composable
internal fun SlotStrip(slots: List<ForecastSlot>, resort: Resort) {
    val locale = LocalConfiguration.current.locales[0]
    val description = slots.map { slotSentence(it, resort, locale) }.joinToString("\n")
    // Each level's largest snowfall over the slots, never under 5 cm so a centimetre stays small.
    val scales = slots.flatMap { it.spots }.groupBy { it.level }.mapValues { (_, spots) -> maxOf(5.0, spots.maxOf { it.snowfall }) }
    Row(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Column(Modifier.padding(end = 6.dp)) {
            Box(Modifier.height(HEADER_HEIGHT))
            slots.first().spots.forEach { spot ->
                Column(Modifier.height(LEVEL_HEIGHT)) {
                    Text(
                        if (spot.level is ForecastLevel.Village) "▼" else "▲",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    spot.level.altitude?.let {
                        Text(metres(it, locale), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            slots.forEachIndexed { index, slot ->
                val opensDay = index > 0 && slot.startsDay(resort.timeZone)
                if (opensDay) Box(Modifier.width(1.dp).height(HEADER_HEIGHT + LEVEL_HEIGHT * slot.spots.size).background(MaterialTheme.colorScheme.outlineVariant))
                SlotColumn(slot, resort, locale, showsWeekday = index == 0 || opensDay, scales)
            }
        }
    }
}

@Composable
private fun SlotColumn(slot: ForecastSlot, resort: Resort, locale: Locale, showsWeekday: Boolean, scales: Map<ForecastLevel, Double>) {
    val local = slot.start.atZone(resort.timeZone)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(COLUMN_WIDTH)
            .clip(RoundedCornerShape(8.dp))
            .then(if (slot.isCurrent) Modifier.background(SnowBlue.copy(alpha = 0.1f)) else Modifier),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(HEADER_HEIGHT)) {
            Text(
                if (showsWeekday) local.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) else " ",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            Text(
                if (slot.isCurrent) stringResource(R.string.now) else DateTimeFormatter.ofPattern("HH:mm", locale).format(local),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        slot.spots.forEach { spot ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.height(LEVEL_HEIGHT),
            ) {
                Text(spotSymbol(spot), fontSize = 18.sp, modifier = Modifier.height(24.dp))
                SlotSnowBar(spot.snowfall, scales[spot.level] ?: 5.0)
                val snow = spot.snowfallCentimetres?.let { centimetres(it, locale) }
                Text(
                    snow ?: spot.rainMillimetres?.let { millimetres(it, locale) } ?: " ",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (snow == null) Rain else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    spot.maxTemperatureDegrees?.let(::degrees) ?: " ",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

/**
 * A slot's snow as a small solid bar on the row's scale; a dry slot is a flat
 * line so the row still reads as a row. The models' range is only known by
 * day, so the bar has no fading top here. Port of `SlotSnowBar`.
 */
@Composable
private fun SlotSnowBar(snowfall: Double, scale: Double) {
    val dry = DailyForecast.centimetres(snowfall) == null
    val bar = BAR_HEIGHT * (snowfall / maxOf(scale, 1.0)).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.width(12.dp).height(BAR_HEIGHT), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (dry) 3.dp else maxOf(bar, 3.dp))
                .clip(if (dry) RoundedCornerShape(50) else RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                .background(if (dry) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else SnowBlue),
        )
    }
}

/** What falls, or the sky when nothing does. */
private fun spotSymbol(spot: ForecastSlot.Spot) = when (spot.precipitation) {
    MountainProfile.Precipitation.SNOW -> conditionSymbol(WeatherCondition.SNOW)
    MountainProfile.Precipitation.RAIN -> conditionSymbol(WeatherCondition.RAIN)
    MountainProfile.Precipitation.MIXED -> "🌨️"
    MountainProfile.Precipitation.NONE -> conditionSymbol(spot.condition ?: WeatherCondition.CLOUDY)
}

/** "Friday, 20:00 to 00:00: Summit, 2,700 m: 3 cm of snow, max -9°. Village, 1,650 m: rain, max 2°." */
@Composable
private fun slotSentence(slot: ForecastSlot, resort: Resort, locale: Locale): String {
    val hour = DateTimeFormatter.ofPattern("HH:mm", locale)
    val start = slot.start.atZone(resort.timeZone)
    val day = start.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    val heading = stringResource(R.string.slot_heading, day, hour.format(start), hour.format(slot.end.atZone(resort.timeZone)))
    val body = slot.spots.map { spot ->
        val parts = buildList {
            when (spot.precipitation) {
                MountainProfile.Precipitation.SNOW, MountainProfile.Precipitation.MIXED -> {
                    add(stringResource(R.string.snow_amount, spot.snowfallCentimetres?.let { centimetres(it, locale) }.orEmpty()))
                    if (spot.precipitation == MountainProfile.Precipitation.MIXED) add(stringResource(R.string.rain_lowercase))
                }
                MountainProfile.Precipitation.RAIN -> add(stringResource(R.string.rain_lowercase))
                MountainProfile.Precipitation.NONE -> add(conditionLabel(spot.condition ?: WeatherCondition.CLOUDY))
            }
            spot.maxTemperatureDegrees?.let { add(stringResource(R.string.max_temperature, degrees(it))) }
        }
        val level = when (val l = spot.level) {
            is ForecastLevel.Summit -> stringResource(R.string.level_summit, metres(l.altitude, locale))
            is ForecastLevel.Village -> stringResource(R.string.level_village, metres(l.altitude, locale))
            ForecastLevel.ResortCentre -> stringResource(R.string.level_resort)
        }
        stringResource(R.string.day_summary, level, parts.joinToString(", "))
    }
    return stringResource(R.string.day_summary, heading, body.joinToString(". ")) + "."
}

/** "4 mm". */
internal fun millimetres(value: Int, locale: Locale): String =
    android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT)
        .format(android.icu.util.Measure(value, android.icu.util.MeasureUnit.MILLIMETER))
