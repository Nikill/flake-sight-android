package com.tanukee.flakesight

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanukee.flakesight.core.DailyForecast
import com.tanukee.flakesight.core.ForecastLevel
import com.tanukee.flakesight.core.ForecastService
import com.tanukee.flakesight.core.LevelForecast
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortForecast
import com.tanukee.flakesight.core.WeatherCondition
import com.tanukee.flakesight.core.forecastLevels
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.tanukee.flakesight.core.DetailedForecast
import com.tanukee.flakesight.core.ForecastGranularity
import com.tanukee.flakesight.core.ForecastSlot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.tanukee.flakesight.core.ForecastBriefing
import com.tanukee.flakesight.core.ForecastConfidence
import com.tanukee.flakesight.core.SnowGaugeScale
import com.tanukee.flakesight.core.SnowfallRange
import com.tanukee.flakesight.core.withRanges
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.tanukee.flakesight.core.ForecastPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.Instant
import java.util.Locale

/** One per process, so its half-hour cache outlives the screens; the resort list shares it for the snow totals. */
internal val forecastService = ForecastService()

/**
 * The resort screen's weather card. Days: five days at the summit and the
 * village, a pictogram, the snow expected and the day's maximum. Hours: the
 * coming four-hour slots in a row that scrolls sideways ([SlotStrip]). The
 * switch is remembered for every resort. Then the folded sentences, and
 * Open-Meteo's credit under the card. The title with its chevron, and the
 * days grid, open the advanced weather. Port of `ForecastStrip`.
 *
 * Greyed out while loading so the cards below do not jump; nothing at all
 * when the forecast cannot be fetched: this is a webcam app.
 */
@Composable
fun ForecastStrip(resort: Resort, showModelSnowDepth: Boolean = true, onOpenAdvanced: (() -> Unit)? = null) {
    var forecast by remember(resort.id) { mutableStateOf(forecastService.cachedForecast(resort)) }
    var hasFailed by remember(resort.id) { mutableStateOf(false) }
    // Snow soon and the good days ahead; left out when the hourly forecast cannot be fetched.
    var briefing by remember(resort.id) { mutableStateOf<ForecastBriefing?>(null) }
    // The hours view's four-hour slots come from the same hourly forecast.
    var detailed by remember(resort.id) { mutableStateOf<DetailedForecast?>(null) }
    var detailedFailed by remember(resort.id) { mutableStateOf(false) }
    // The models' amounts give each day its range; optional, so either can arrive first.
    var models by remember(resort.id) { mutableStateOf(forecastService.cachedModelSnowfall(resort)) }
    LaunchedEffect(resort.id) {
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
                val hourly = forecastService.detailedForecast(resort)
                detailed = hourly
                briefing = ForecastBriefing(hourly, Instant.now(), resort.timeZone)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                detailedFailed = true
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

    val shown = forecast?.let { daily -> models?.let(daily::withRanges) ?: daily }
    when {
        shown != null -> StripContent(
            shown, briefing, resort, isPlaceholder = false, showModelSnowDepth, onOpenAdvanced,
            slots = detailed?.let { ForecastSlot.slots(it, Instant.now(), resort.timeZone) }.orEmpty(),
            slotsFailed = detailedFailed,
        )
        !hasFailed -> StripContent(placeholder(resort), null, resort, isPlaceholder = true)
    }
}

@Composable
private fun StripContent(
    forecast: ResortForecast,
    briefing: ForecastBriefing?,
    resort: Resort,
    isPlaceholder: Boolean,
    showModelSnowDepth: Boolean = true,
    onOpenAdvanced: (() -> Unit)? = null,
    slots: List<ForecastSlot> = emptyList(),
    slotsFailed: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val openAdvanced = onOpenAdvanced?.takeIf { !isPlaceholder }
    // Days or four-hour slots, remembered for every resort under the iOS key.
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var granularity by remember { mutableStateOf(ForecastGranularity.fromRawValue(settings.getString("forecastGranularity", null))) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    // A card like the ones that open a screen: its title and chevron open the advanced weather.
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(12.dp),
    ) {
        val opensAdvanced = stringResource(R.string.opens_advanced_weather)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (openAdvanced != null) Modifier.clickable(onClickLabel = opensAdvanced, onClick = openAdvanced) else Modifier)
                .padding(vertical = 2.dp),
        ) {
            Text("⛅ " + stringResource(R.string.weather), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
        }
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
        // The greyed-out placeholder is always the days.
        if (!isPlaceholder && granularity == ForecastGranularity.HOURS) {
            when {
                slots.isNotEmpty() -> SlotStrip(slots, resort)
                slotsFailed -> Text(
                    stringResource(R.string.hourly_forecast_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        } else {
        val description = forecast.days.indices.map { daySentence(forecast, it, resort, locale) }.joinToString("\n")
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                // The whole grid opens the advanced weather, like the title.
                .then(if (openAdvanced != null) Modifier.clickable(onClickLabel = opensAdvanced, onClick = openAdvanced) else Modifier)
                .alpha(if (isPlaceholder) 0.3f else 1f)
                // TalkBack reads one sentence per day instead of the cells.
                .clearAndSetSemantics { if (!isPlaceholder) contentDescription = description },
        ) {
            Row {
                Text("", Modifier.width(LEVEL_COLUMN_WIDTH))
                forecast.days.forEach { day ->
                    Text(
                        day.weekdayLabel(locale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            forecast.levels.forEach { LevelRow(it, locale) }
        }
        }
        if (!isPlaceholder) {
            val sentences = listOfNotNull(
                briefing?.let { upcomingSnowSentence(it, locale) },
                briefing?.let { goodDaysSentence(it, locale) },
                recentSnowSentence(forecast),
                // One depth on the screen: the bulletin's, when it covers the resort.
                briefing?.takeIf { showModelSnowDepth }?.let { snowDepthSentence(it, locale) },
            )
            if (sentences.isNotEmpty()) ForecastDetails(sentences)
        }
    }
        // Outside the card, as on iOS.
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(ForecastService.ATTRIBUTION_URL) }, contentPadding = PaddingValues(0.dp)) {
            Text(
                stringResource(R.string.weather_data_by_open_meteo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LEVEL_COLUMN_WIDTH = 76.dp

/**
 * A day's snow as a small bar whose top is sharp when the weather models
 * agree and fades out when they do not: solid up to the lowest amount any of
 * them gives, then fading to the highest. A dry day is a flat line, so a row
 * of bars still reads as a row. Port of `FuzzySnowBar`.
 */
@Composable
private fun FuzzySnowBar(day: DailyForecast, scale: SnowGaugeScale, modifier: Modifier = Modifier) {
    val barHeight = 32.dp
    val low = barHeight * scale.fraction(day.snowfallRange?.low ?: day.snowfall).toFloat()
    val high = barHeight * scale.fraction(day.snowfallRange?.high ?: day.snowfall).toFloat()
    val dry = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Box(modifier.width(14.dp).height(barHeight).clearAndSetSemantics {}, contentAlignment = Alignment.BottomCenter) {
        if (high < 3.dp) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(dry))
        } else {
            // The share of the bar the models all agree on stays solid.
            val solid = (low / high).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(high)
                    .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(Brush.verticalGradient(0f to SnowBlue.copy(alpha = 0.06f), (1 - solid) to SnowBlue, 1f to SnowBlue)),
            )
        }
    }
}

/** "between 5 and 20 centimetres, low confidence", inside a sentence read aloud. */
@Composable
internal fun spokenRange(range: SnowfallRange, locale: Locale): String {
    val high = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(range.highCentimetres, MeasureUnit.CENTIMETER))
    val confidence = stringResource(
        if (range.confidence == ForecastConfidence.HIGH) R.string.high_confidence_spoken else R.string.low_confidence_spoken,
    )
    return stringResource(R.string.range_spoken, java.text.NumberFormat.getIntegerInstance(locale).format(range.lowCentimetres), high, confidence)
}

/** "5-20 cm", whole centimetres; "12 cm" when both ends round alike. */
internal fun rangeLabel(range: SnowfallRange, locale: Locale): String {
    val high = centimetres(range.highCentimetres, locale)
    if (range.lowCentimetres == range.highCentimetres) return high
    return java.text.NumberFormat.getIntegerInstance(locale).format(range.lowCentimetres) + "-" + high
}

/**
 * The sentences under the grid, folded under "Forecast Details": open until
 * the user folds them, which then holds for every resort, as on iOS.
 */
@Composable
private fun ForecastDetails(sentences: List<String>) {
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var expanded by remember { mutableStateOf(settings.getBoolean("forecastDetailsExpanded", true)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = {
                expanded = !expanded
                settings.edit().putBoolean("forecastDetailsExpanded", expanded).apply()
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            val state = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
            // The arrow is drawn, not read: TalkBack says whether the details are open instead.
            Text(if (expanded) "▾ " else "▸ ", Modifier.clearAndSetSemantics {})
            Text(stringResource(R.string.forecast_details), Modifier.semantics { stateDescription = state })
        }
        if (expanded) {
            sentences.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** "Snow expected at the summit from 15:00 to 21:00: about 4 cm.", or that none is coming. */
@Composable
private fun upcomingSnowSentence(briefing: ForecastBriefing, locale: Locale): String {
    val snow = briefing.upcomingSnow ?: return stringResource(R.string.no_snow_next_24_hours)
    val place = placePhrase(snow.level)
    val amount = centimetres(DailyForecast.centimetres(snow.amount) ?: 1, locale)
    val end = briefingTime(snow.end, briefing, locale)
    return if (snow.start <= briefing.now) {
        stringResource(R.string.snowing_now_until, place, end, amount)
    } else {
        stringResource(R.string.snow_expected_from_to, place, briefingTime(snow.start, briefing, locale), end, amount)
    }
}

/** "15:00" today, "Saturday 03:00" on another day, 24-hour as on iOS. */
private fun briefingTime(instant: Instant, briefing: ForecastBriefing, locale: Locale): String {
    val local = instant.atZone(briefing.zone)
    val sameDay = local.toLocalDate() == briefing.now.atZone(briefing.zone).toLocalDate()
    return DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "EEEE HH:mm", locale).format(local)
}

/** "Saturday: 21 cm of fresh snow and clear skies." for each good day, or that none is coming. */
@Composable
private fun goodDaysSentence(briefing: ForecastBriefing, locale: Locale): String {
    if (briefing.goodDays.isEmpty()) return stringResource(R.string.no_good_day)
    return briefing.goodDays.map { day ->
        val weekday = day.period.day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val label = if (day.isWholeDay) {
            weekday
        } else {
            stringResource(
                when (day.period.part) {
                    ForecastPeriod.Part.MORNING -> R.string.period_morning
                    ForecastPeriod.Part.AFTERNOON -> R.string.period_afternoon
                    ForecastPeriod.Part.NIGHT -> R.string.period_night
                },
                weekday,
            )
        }
        // The weekday starts the sentence: "samedi" becomes "Samedi".
        val start = label.replaceFirstChar { it.titlecase(locale) }
        // At least the fresh-snow threshold, so never under a centimetre.
        stringResource(R.string.good_day, start, centimetres(DailyForecast.centimetres(day.freshSnow) ?: 0, locale))
    }.joinToString(" ")
}

/** "About 95 cm of snow on the ground at the summit (model estimate).", or nothing under 5 cm. */
@Composable
private fun snowDepthSentence(briefing: ForecastBriefing, locale: Locale): String? {
    val depth = briefing.roundedSnowDepth ?: return null
    val level = briefing.snowDepth?.level ?: return null
    return stringResource(R.string.snow_depth_model, centimetres(depth, locale), placePhrase(level))
}

@Composable
private fun LevelRow(level: LevelForecast, locale: Locale) {
    // The dry days of a snowy row keep a blank line so its temperatures stay aligned; a dry row skips it.
    val hasSnow = level.days.any { it.snowfallCentimetres != null || it.snowfallRange != null }
    val scale = SnowGaugeScale(level.days)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            levelSymbol(level.level) + (level.level.altitude?.let { " " + metres(it, locale) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LEVEL_COLUMN_WIDTH),
        )
        level.days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(conditionSymbol(day.condition), fontSize = 22.sp)
                if (hasSnow) {
                    FuzzySnowBar(day, scale, Modifier.padding(top = 2.dp))
                    Text(
                        day.snowfallCentimetres?.let { centimetres(it, locale) } ?: " ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Text(day.maxTemperatureDegrees?.let(::degrees) ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Emoji draw in colour everywhere, as the iOS multicolour symbols do, with no asset to ship. */
internal fun conditionSymbol(condition: WeatherCondition) = when (condition) {
    WeatherCondition.CLEAR -> "☀️"
    WeatherCondition.PARTLY_CLOUDY -> "⛅"
    WeatherCondition.CLOUDY -> "☁️"
    WeatherCondition.FOG -> "🌫️"
    WeatherCondition.RAIN -> "🌧️"
    WeatherCondition.SNOW -> "🌨️"
    WeatherCondition.THUNDERSTORM -> "⛈️"
}

private fun levelSymbol(level: ForecastLevel) = when (level) {
    is ForecastLevel.Summit -> "▲"
    is ForecastLevel.Village -> "▼"
    ForecastLevel.ResortCentre -> "⛰️"
}

@Composable
internal fun conditionLabel(condition: WeatherCondition) = stringResource(
    when (condition) {
        WeatherCondition.CLEAR -> R.string.condition_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.condition_cloudy
        WeatherCondition.FOG -> R.string.condition_fog
        WeatherCondition.RAIN -> R.string.condition_rain
        WeatherCondition.SNOW -> R.string.condition_snow
        WeatherCondition.THUNDERSTORM -> R.string.condition_thunderstorm
    },
)

@Composable
internal fun placePhrase(level: ForecastLevel) = stringResource(
    when (level) {
        is ForecastLevel.Summit -> R.string.place_summit
        is ForecastLevel.Village -> R.string.place_village
        ForecastLevel.ResortCentre -> R.string.place_resort
    },
)

/** "In the last 7 days: 24 cm at the summit, none in the village." or "No snow in the last 7 days." */
@Composable
internal fun recentSnowSentence(forecast: ResortForecast): String? {
    val amounts = forecast.recentSnow() ?: return null
    val days = ForecastService.SNOW_RANKING_PAST_DAYS
    if (amounts.all { it.second == null }) return stringResource(R.string.no_recent_snow, days)
    val locale = LocalConfiguration.current.locales[0]
    val parts = amounts.map { (level, cm) ->
        val place = placePhrase(level)
        if (cm != null) "${centimetres(cm, locale)} $place" else stringResource(R.string.recent_snow_none_at, place)
    }
    return stringResource(R.string.recent_snow, days, parts.joinToString(", "))
}

/** "Today, Friday: Summit, 2,700 m, Clear, max -4°. Village, 1,200 m, Snow, 12 cm, max -7°." */
@Composable
private fun daySentence(forecast: ResortForecast, index: Int, resort: Resort, locale: Locale): String {
    val day = forecast.days[index]
    val weekday = day.weekdayName(locale).replaceFirstChar { it.titlecase(locale) }
    val heading = if (day.isToday(Instant.now(), resort.timeZone)) stringResource(R.string.today_weekday, weekday) else weekday
    val rows = forecast.levels.mapNotNull { level ->
        val levelDay = level.days.getOrNull(index) ?: return@mapNotNull null
        val levelLabel = when (val l = level.level) {
            is ForecastLevel.Summit -> stringResource(R.string.level_summit, metres(l.altitude, locale))
            is ForecastLevel.Village -> stringResource(R.string.level_village, metres(l.altitude, locale))
            ForecastLevel.ResortCentre -> stringResource(R.string.level_resort)
        }
        listOfNotNull(
            levelLabel,
            conditionLabel(levelDay.condition),
            levelDay.snowfallCentimetres?.let { centimetres(it, locale) },
            levelDay.snowfallRange?.let { spokenRange(it, locale) },
            levelDay.maxTemperatureDegrees?.let { stringResource(R.string.max_temperature, degrees(it)) },
        ).joinToString(", ")
    }
    return stringResource(R.string.day_summary, heading, rows.joinToString(". ") + ".")
}

/** "2,700 m" or "2 700 m". */
internal fun metres(value: Int, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(Measure(value, MeasureUnit.METER))

/** "12 cm", with the narrow no-break space French uses. */
internal fun centimetres(value: Int, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(Measure(value, MeasureUnit.CENTIMETER))

/** "-4°", whole degrees. */
internal fun degrees(value: Int) = "$value°"

/** The resort's levels and the forecast days from today, drawn greyed out before the first answer. */
private fun placeholder(resort: Resort): ResortForecast {
    val today = Instant.now().atZone(resort.timeZone).toLocalDate()
    val days = List(ForecastService.DAY_COUNT) {
        DailyForecast(today.plusDays(it.toLong()), WeatherCondition.PARTLY_CLOUDY, 0.0, 0.0)
    }
    return ResortForecast(resort.id, resort.forecastLevels.map { LevelForecast(it, days) }, Instant.now())
}
