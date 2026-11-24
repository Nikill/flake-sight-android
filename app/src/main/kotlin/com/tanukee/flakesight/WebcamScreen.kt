package com.tanukee.flakesight

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnapshotFreshness
import com.tanukee.flakesight.core.SnapshotSize
import com.tanukee.flakesight.core.Webcam
import kotlinx.coroutines.launch


/**
 * One webcam: large panorama scrolling sideways, refresh, capture time and
 * source. Port of `WebcamDetailView`; afternoon images come with their area,
 * and no catalogued webcam has a live stream yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebcamScreen(webcam: Webcam, resort: Resort, onBack: () -> Unit) {
    var state by remember(webcam.id) { mutableStateOf(ImageState()) }
    val scope = rememberCoroutineScope()
    val load: (Boolean) -> Unit = { forceRefresh ->
        scope.launch { loadImage(webcam, resort, SnapshotSize.FULL, forceRefresh) { state = it(state) } }
    }
    LaunchedEffect(webcam.id) { loadImage(webcam, resort, SnapshotSize.FULL) { state = it(state) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(webcam.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { load(true) }, enabled = !state.isLoading) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding)) {
            // Taller on a wide window (unfolded foldable, tablet), where there is room.
            val panoramaHeight = if (maxWidth >= 600.dp) 480.dp else 300.dp
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            ) {
                Panorama(webcam, state, panoramaHeight, onRetry = { load(true) })
                Details(webcam, resort, state)
                Attribution(webcam)
            }
        }
    }
}

/** Panoramas are ~6:1, so they keep a usable height and scroll sideways, starting from the middle. */
@Composable
private fun Panorama(webcam: Webcam, state: ImageState, height: Dp, onRetry: () -> Unit) {
    val image = state.image
    when {
        image != null -> Box {
            val scroll = rememberScrollState()
            LaunchedEffect(scroll.maxValue) { if (scroll.value == 0) scroll.scrollTo(scroll.maxValue / 2) }
            Box(Modifier.horizontalScroll(scroll)) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = stringResource(R.string.latest_image_from, webcam.name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(height).aspectRatio(image.width.toFloat() / image.height),
                )
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp))
            }
        }
        state.error != null -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.titleMedium)
            Text(
                errorMessage(state.error, webcam),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
        else -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun Details(webcam: Webcam, resort: Resort, state: ImageState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        // With no image at all, the panorama area already explains the failure.
        state.snapshot?.let { FreshnessLabel(SnapshotFreshness.of(it), resort) }
        if (state.image != null && state.error != null) {
            // A refresh failed but an older image is still shown.
            Text(errorMessage(state.error, webcam), color = MaterialTheme.colorScheme.error)
        }
        webcam.altitude?.let { altitude ->
            Text(metres(altitude.toInt(), LocalConfiguration.current.locales[0]))
        }
        webcam.orientation?.let { Text(stringResource(R.string.facing, it)) }
    }
}

@Composable
private fun Attribution(webcam: Webcam) {
    val attribution = webcamService.attribution(webcam)
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.source), style = MaterialTheme.typography.titleMedium)
        Text(
            attribution.owner?.let { stringResource(R.string.owner_via_provider, it, attribution.providerName) }
                ?: stringResource(R.string.via_provider, attribution.providerName),
        )
        TextButton(onClick = { uriHandler.openUri(webcam.sourcePageURL) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.view_on_provider, attribution.providerName))
        }
    }
}
