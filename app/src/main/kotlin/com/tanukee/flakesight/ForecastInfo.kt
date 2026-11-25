package com.tanukee.flakesight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanukee.flakesight.core.ForecastService
import com.tanukee.flakesight.core.WeatherModelInfo

/**
 * An "i" that opens an explanation in a bottom sheet, the Android form of iOS's
 * `InfoButton` sheet. [title] is the sheet's title; [label] what TalkBack reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoButton(label: String, title: String, content: @Composable () -> Unit) {
    var isShowing by remember { mutableStateOf(false) }
    IconButton(onClick = { isShowing = true }, modifier = Modifier.size(32.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = label, modifier = Modifier.size(18.dp))
    }
    if (isShowing) {
        ModalBottomSheet(onDismissRequest = { isShowing = false }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

@Composable
private fun InfoParagraph(heading: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Text(text)
    }
}

/** What the freezing level is and why it matters on skis. Port of `FreezingLevelInfoView`. */
@Composable
internal fun FreezingLevelInfoButton() = InfoButton(stringResource(R.string.about_freezing_level), stringResource(R.string.freezing_level_title)) {
    Text(stringResource(R.string.freezing_level_intro))
    InfoParagraph(stringResource(R.string.rain_or_snow), stringResource(R.string.freezing_level_rain_or_snow))
    InfoParagraph(stringResource(R.string.snow_on_the_ground), stringResource(R.string.freezing_level_ground))
    InfoParagraph(stringResource(R.string.on_these_charts), stringResource(R.string.freezing_level_charts))
    InfoParagraph(stringResource(R.string.temperature_inversions), stringResource(R.string.freezing_level_inversions))
}

/** Who makes each weather model of the table and what sets it apart. Port of `WeatherModelsInfoView`. */
@Composable
internal fun WeatherModelsInfoButton() = InfoButton(stringResource(R.string.about_weather_models), stringResource(R.string.weather_models)) {
    Text(stringResource(R.string.weather_models_intro))
    for (model in WeatherModelInfo.ALL) {
        val (origin, details) = when (model.id) {
            "meteofrance_seamless" -> R.string.origin_france to R.string.model_meteofrance_details
            "icon_seamless" -> R.string.origin_germany to R.string.model_icon_details
            "ecmwf_ifs025" -> R.string.origin_europe to R.string.model_ecmwf_details
            else -> R.string.origin_united_states to R.string.model_gfs_details
        }
        val provider = "${model.provider ?: stringResource(R.string.provider_ecmwf)}, ${stringResource(origin)}"
        val detailsText = stringResource(details)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(12.dp)
                .clearAndSetSemantics { contentDescription = "${model.name}. $provider. $detailsText" },
        ) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text(provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detailsText)
        }
    }
    InfoParagraph(stringResource(R.string.agreement), stringResource(R.string.agreement_explained))
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(ForecastService.ATTRIBUTION_URL) }, contentPadding = PaddingValues(0.dp)) {
        Text(stringResource(R.string.weather_data_by_open_meteo), style = MaterialTheme.typography.labelSmall)
    }
}
