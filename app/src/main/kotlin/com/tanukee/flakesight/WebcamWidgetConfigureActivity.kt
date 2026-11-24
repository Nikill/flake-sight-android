package com.tanukee.flakesight

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.Webcam
import com.tanukee.flakesight.core.widgetWebcams
import kotlinx.coroutines.launch

/**
 * The webcam widget's settings: one optional webcam. Left on the default
 * resort, the widget follows the one chosen in the app. Port of
 * `WebcamWidgetIntent`; shown when the widget is added and, from Android 12,
 * when it is reconfigured.
 */
class WebcamWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Leaving without a choice cancels the widget being added.
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        enableEdgeToEdge()
        setContent {
            FlakeSightTheme {
                val scope = rememberCoroutineScope()
                var current by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    current = getAppWidgetState(this@WebcamWidgetConfigureActivity, PreferencesGlanceStateDefinition, glanceId)[WebcamWidget.WEBCAM_KEY]
                }
                WebcamChoiceScreen(
                    current,
                    onChoose = { webcamID ->
                        scope.launch {
                            updateAppWidgetState(this@WebcamWidgetConfigureActivity, glanceId) { state ->
                                if (webcamID == null) state.remove(WebcamWidget.WEBCAM_KEY) else state[WebcamWidget.WEBCAM_KEY] = webcamID
                            }
                            WebcamWidget().update(this@WebcamWidgetConfigureActivity, glanceId)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    },
                    onClose = ::finish,
                )
            }
        }
    }
}

/** The default resort first, then every webcam grouped by resort, with a search. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WebcamChoiceScreen(current: String?, onChoose: (String?) -> Unit, onClose: () -> Unit) {
    val repository = remember { ResortRepository.bundled() }
    var searchText by rememberSaveable { mutableStateOf("") }
    val sections = remember(searchText) { repository.widgetWebcams(searchText) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchField(searchText, onTextChange = { searchText = it })
            LazyColumn {
                if (searchText.isBlank()) {
                    item {
                        ChoiceRow(
                            title = stringResource(R.string.default_resort),
                            subtitle = stringResource(R.string.widget_follow_default),
                            selected = current == null,
                            onClick = { onChoose(null) },
                        )
                    }
                }
                sections.forEach { (resort: Resort, webcams: List<Webcam>) ->
                    stickyHeader(key = resort.id) {
                        Text(
                            resort.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            // Opaque, so the rows scrolling under the pinned header do not show through.
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(webcams, key = { it.id }) { webcam ->
                        ChoiceRow(webcam.name, subtitle = null, selected = webcam.id == current, onClick = { onChoose(webcam.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
    ) { Text(title) }
}
