package com.tanukee.flakesight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlakeSightTheme {
                val resorts = remember { ResortRepository.bundled().resorts }
                ResortListScreen(resorts)
            }
        }
    }
}

/** Snow blue of the iOS app: #256ABF, #3987E5 in dark mode. */
@Composable
fun FlakeSightTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF3987E5))
    } else {
        lightColorScheme(primary = Color(0xFF256ABF))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResortListScreen(resorts: List<Resort>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.resorts_title)) }) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(resorts, key = { it.id }) { resort ->
                ListItem(
                    supportingContent = { Text(resort.region) },
                    trailingContent = {
                        Text(
                            if (resort.webcams.isEmpty()) {
                                stringResource(R.string.forecast_only)
                            } else {
                                pluralStringResource(R.plurals.webcam_count, resort.webcams.size, resort.webcams.size)
                            },
                        )
                    },
                ) {
                    Text(resort.name)
                }
                HorizontalDivider()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ResortListPreview() {
    FlakeSightTheme { ResortListScreen(ResortRepository.bundled().resorts.take(8)) }
}
