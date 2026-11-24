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
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.Locale

/** One per process, so its half-hour cache outlives the screens. */
private val forecastService = ForecastService()

/**
 * Five days of weather at the summit and the village: a pictogram, the snow
 * expected and the day's maximum, then the snow fallen over the last seven
 * days and Open-Meteo's credit. Port of `ForecastStrip`, without the hourly
 * sentences, the models' ranges and the advanced weather screen yet.
 *
 * Greyed out while loading so the cards below do not jump; nothing at all
 * when the forecast cannot be fetched: this is a webcam app.
 */
@Composable
fun ForecastStrip(resort: Resort) {
    var forecast by remember(resort.id) { mutableStateOf(forecastService.cachedForecast(resort)) }
    var hasFailed by remember(resort.id) { mutableStateOf(false) }
    LaunchedEffect(resort.id) {
        try {
            forecast = forecastService.forecast(resort)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            hasFailed = forecast == null
        }
    }

    val shown = forecast
    when {
        shown != null -> StripContent(shown, resort, isPlaceholder = false)
        !hasFailed -> StripContent(placeholder(resort), resort, isPlaceholder = true)
    }
}

@Composable
private fun StripContent(forecast: ResortForecast, resort: Resort, isPlaceholder: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.weather), style = MaterialTheme.typography.titleMedium)
        val description = forecast.days.indices.map { daySentence(forecast, it, resort, locale) }.joinToString("\n")
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
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
        if (!isPlaceholder) {
            recentSnowSentence(forecast)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
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

@Composable
private fun LevelRow(level: LevelForecast, locale: Locale) {
    // The dry days of a snowy row keep a blank line so its temperatures stay aligned; a dry row skips it.
    val hasSnow = level.days.any { it.snowfallCentimetres != null }
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
private fun conditionSymbol(condition: WeatherCondition) = when (condition) {
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
private fun conditionLabel(condition: WeatherCondition) = stringResource(
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
private fun placePhrase(level: ForecastLevel) = stringResource(
    when (level) {
        is ForecastLevel.Summit -> R.string.place_summit
        is ForecastLevel.Village -> R.string.place_village
        ForecastLevel.ResortCentre -> R.string.place_resort
    },
)

/** "In the last 7 days: 24 cm at the summit, none in the village." or "No snow in the last 7 days." */
@Composable
private fun recentSnowSentence(forecast: ResortForecast): String? {
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
            levelDay.maxTemperatureDegrees?.let { stringResource(R.string.max_temperature, degrees(it)) },
        ).joinToString(", ")
    }
    return stringResource(R.string.day_summary, heading, rows.joinToString(". ") + ".")
}

/** "2,700 m" or "2 700 m". */
internal fun metres(value: Int, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(Measure(value, MeasureUnit.METER))

/** "12 cm", with the narrow no-break space French uses. */
private fun centimetres(value: Int, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(Measure(value, MeasureUnit.CENTIMETER))

/** "-4°", whole degrees. */
private fun degrees(value: Int) = "$value°"

/** The resort's levels and the forecast days from today, drawn greyed out before the first answer. */
private fun placeholder(resort: Resort): ResortForecast {
    val today = Instant.now().atZone(resort.timeZone).toLocalDate()
    val days = List(ForecastService.DAY_COUNT) {
        DailyForecast(today.plusDays(it.toLong()), WeatherCondition.PARTLY_CLOUDY, 0.0, 0.0)
    }
    return ResortForecast(resort.id, resort.forecastLevels.map { LevelForecast(it, days) }, Instant.now())
}
