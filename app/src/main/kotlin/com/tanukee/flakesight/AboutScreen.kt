package com.tanukee.flakesight

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.tanukee.flakesight.core.ForecastService
import com.tanukee.flakesight.core.AppIconChoice
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.SnowDepthReport
import java.net.URLEncoder

/** Where support requests go, as on iOS (`AppLinks.supportEmail`). */
private const val SUPPORT_EMAIL = "n.balligand@yahoo.com"

/**
 * The policy's public copy, which Google Play also links to; beside the iOS
 * one (`/flake-sight/privacy/`). Keep the page and the strings in step.
 */
internal const val PRIVACY_POLICY_URL = "https://tanukee.com/flake-sight/android/privacy/"
internal const val PRIVACY_POLICY_URL_FR = "https://tanukee.com/flake-sight/fr/android/privacy/"

/**
 * App information, over the resort list. Port of `AboutView`; Apple's licence
 * agreement has no Android counterpart, so there is no Licence link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(repository: ResortRepository, onClose: () -> Unit) {
    var isShowingPolicy by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // "0.1.0 (1)" from the version name and code.
    val version = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    val credits = webcamCredits(repository)
    var appIcon by remember { mutableStateOf(currentAppIcon(context)) }
    if (isShowingPolicy) {
        BackHandler { isShowingPolicy = false }
        PrivacyPolicyScreen(credits, onBack = { isShowingPolicy = false })
        return
    }
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Image(
                    painterResource(appIcon.preview),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(27.dp)),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.about_tagline), modifier = Modifier.padding(16.dp))
            SectionTitle(stringResource(R.string.app_icon))
            for (choice in AppIconChoice.entries) {
                val isSelected = choice == appIcon
                ListItem(
                    modifier = Modifier.selectable(isSelected, role = Role.RadioButton) {
                        setAppIcon(context, choice)
                        appIcon = choice
                    },
                    leadingContent = {
                        Image(painterResource(choice.preview), null, Modifier.size(60.dp).clip(RoundedCornerShape(13.5.dp)))
                    },
                    trailingContent = { if (isSelected) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary) },
                ) { Text(stringResource(choice.label)) }
            }
            ListItem(modifier = Modifier.clickable { isShowingPolicy = true }) { Text(stringResource(R.string.privacy_policy)) }
            ListItem(
                modifier = Modifier.clickable {
                    // A device without a mail app has nothing to open the link with.
                    runCatching { uriHandler.openUri("mailto:$SUPPORT_EMAIL?subject=" + URLEncoder.encode("Flake Sight $version", "UTF-8").replace("+", "%20")) }
                },
                supportingContent = { Text(SUPPORT_EMAIL) },
            ) { Text(stringResource(R.string.support)) }

            SectionTitle(stringResource(R.string.credits))
            CreditRow(stringResource(R.string.developer), "Nicolas Balligand")
            CreditRow(stringResource(R.string.webcams_credit), credits)
            Footnote(stringResource(R.string.images_property))
            CreditRow(stringResource(R.string.weather), "Open-Meteo") { uriHandler.openUri(ForecastService.ATTRIBUTION_URL) }
            Footnote(stringResource(R.string.open_meteo_licence))
            CreditRow(stringResource(R.string.snow_depth), "Météo-France") { uriHandler.openUri(SnowDepthReport.ATTRIBUTION_URL) }
            Footnote(stringResource(R.string.bulletin_licence))
        }
    }
}

/** "Webcam HD, Skaping, Viewsurf, Roundshot and resort websites", from the catalogue. */
@Composable
private fun webcamCredits(repository: ResortRepository): String {
    val names = repository.creditedProviderTypes.mapNotNull { it.brandName } +
        if (repository.hasResortWebsiteWebcams) listOf(stringResource(R.string.resort_websites)) else emptyList()
    return ListFormatter.getInstance(LocalConfiguration.current.locales[0]).format(names)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

private val AppIconChoice.preview: Int
    get() = when (this) {
        AppIconChoice.MOUNTAIN -> R.drawable.ic_launcher_art_mountain
        AppIconChoice.GOGGLES -> R.drawable.ic_launcher_art_goggles
        AppIconChoice.CLASSIC -> R.drawable.ic_launcher_art_classic
    }

/** "Mountain", "Goggles", "Original". */
private val AppIconChoice.label: Int
    get() = when (this) {
        AppIconChoice.MOUNTAIN -> R.string.app_icon_mountain
        AppIconChoice.GOGGLES -> R.string.app_icon_goggles
        AppIconChoice.CLASSIC -> R.string.app_icon_original
    }

private fun aliasComponent(context: Context, choice: AppIconChoice) = ComponentName(context, "${context.packageName}.${choice.alias}")

/** The icon on the Home Screen: the alias the package manager has enabled, as `alternateIconName` on iOS. */
private fun currentAppIcon(context: Context): AppIconChoice {
    val pm = context.packageManager
    val enabled = AppIconChoice.entries.firstOrNull { choice ->
        when (pm.getComponentEnabledSetting(aliasComponent(context, choice))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> choice == AppIconChoice.DEFAULT
            else -> false
        }
    }
    return AppIconChoice.fromAlternateIconName(enabled?.alternateIconName)
}

/** Enables the chosen launcher alias and disables the others; the launcher may take a moment to follow. */
private fun setAppIcon(context: Context, choice: AppIconChoice) {
    val pm = context.packageManager
    // The new one first, so there is never no launcher entry.
    for (other in listOf(choice) + (AppIconChoice.entries - choice)) {
        pm.setComponentEnabledSetting(
            aliasComponent(context, other),
            if (other == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

@Composable
private fun CreditRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        supportingContent = { Text(value, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
    ) { Text(label) }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The privacy policy, shipped in the app so it is readable offline and
 * reviewable with the code. Port of `PrivacyPolicyView`, adapted to Android:
 * the widget is part of the app, the map is Google Maps. Google Play needs the
 * same text at a public URL; keep the two in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyPolicyScreen(webcamCredits: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_policy)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(stringResource(R.string.privacy_no_data))
            Text(stringResource(R.string.privacy_favourites))
            Text(stringResource(R.string.privacy_location))
            Text(stringResource(R.string.privacy_webcams, webcamCredits))
            Text(stringResource(R.string.privacy_weather))
            Text(stringResource(R.string.privacy_map))
            Text(stringResource(R.string.privacy_tanukee))
            Text(stringResource(R.string.privacy_storage))
            Text(stringResource(R.string.privacy_questions, SUPPORT_EMAIL))
            Text(
                stringResource(R.string.privacy_last_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val uriHandler = LocalUriHandler.current
            val url = if (LocalConfiguration.current.locales[0].language == "fr") PRIVACY_POLICY_URL_FR else PRIVACY_POLICY_URL
            TextButton(onClick = { uriHandler.openUri(url) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.privacy_online))
            }
        }
    }
}
