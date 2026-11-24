package com.tanukee.flakesight

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tanukee.flakesight.core.DeepLink
import com.tanukee.flakesight.core.FavoritesStore
import com.tanukee.flakesight.core.LastSnapshotStore
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.SnapshotFreshness
import com.tanukee.flakesight.core.SnapshotSize
import com.tanukee.flakesight.core.Webcam
import com.tanukee.flakesight.core.WebcamSnapshot
import com.tanukee.flakesight.core.WidgetWebcamResolver
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Latest image of a webcam with the time it was taken. Port of `WebcamWidget`
 * and `WebcamTimelineProvider`.
 *
 * Shows the webcam chosen in its settings (`WebcamWidgetConfigureActivity`), else
 * the default resort's first webcam, else the first catalogue resort's
 * (`WidgetWebcamResolver`). The widget runs in the app's own process, so it reads
 * the favourites file directly where iOS needs an App Group. Android refreshes it
 * every 30 minutes, as iOS's timeline; the app refreshes it too when the default
 * resort changes. Tapping opens the webcam in the app.
 */
class WebcamWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val configured = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WEBCAM_KEY]
        val first = entry(context, configured)
        provideContent {
            // A new choice in the settings while the widget's session is still alive.
            val webcamID = currentState(WEBCAM_KEY)
            var entry by remember { mutableStateOf(first) }
            var loadedFor by remember { mutableStateOf(configured) }
            LaunchedEffect(webcamID) {
                if (webcamID != loadedFor) {
                    entry = entry(context, webcamID)
                    loadedFor = webcamID
                }
            }
            WebcamWidgetContent(entry)
        }
    }

    /** One rendering of the widget. */
    private sealed interface Entry {
        data class Image(val bitmap: Bitmap, val webcam: Webcam, val resort: Resort, val snapshot: WebcamSnapshot) : Entry

        /** The image couldn't be downloaded; the widget says so instead of showing something misleading. */
        data class Unavailable(val webcam: Webcam, val resort: Resort) : Entry

        /** The catalogue has no webcam to show. */
        data object NoWebcam : Entry
    }

    private suspend fun entry(context: Context, configuredWebcamID: String?): Entry {
        val defaultResortID = FavoritesStore(File(context.filesDir, "favorites.json")).defaultResortID()
        val (resort, webcam) = WidgetWebcamResolver.resolve(configuredWebcamID, defaultResortID, availableRepository(context)) ?: return Entry.NoWebcam
        // The widget may run without the app: its downloads near 16:00 count as afternoon images too.
        afternoonService(context)
        val store = LastSnapshotStore(File(context.cacheDir, "LastSnapshots"))
        val snapshot = try {
            webcamService.latestSnapshot(webcam, resort, SnapshotSize.THUMBNAIL).also { store.save(it, SnapshotSize.THUMBNAIL) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed refresh keeps the last good image. Its real capture time still decides
            // whether it is flagged as stale, so an old picture is never passed off as current.
            webcamService.cachedSnapshot(webcam, SnapshotSize.THUMBNAIL) ?: store.snapshot(webcam, SnapshotSize.THUMBNAIL)
        }
        // Small enough for the home screen's bitmap budget; the widget crops it to its own shape.
        val bitmap = snapshot?.let { decode(it, WIDGET_PIXEL_SIZE) } ?: return Entry.Unavailable(webcam, resort)
        return Entry.Image(bitmap, webcam, resort, snapshot)
    }

    @Composable
    private fun WebcamWidgetContent(entry: Entry) {
        val context = LocalContext.current
        val size = LocalSize.current
        val isSmall = size.width < 180.dp
        val isLarge = size.height >= 280.dp
        val webcamID = (entry as? Entry.Image)?.webcam?.id ?: (entry as? Entry.Unavailable)?.webcam?.id
        val open = Intent(Intent.ACTION_VIEW, webcamID?.let { Uri.parse(DeepLink.webcamURL(it)) }, context, MainActivity::class.java)
        Box(
            modifier = GlanceModifier.fillMaxSize().background(Color.Black).clickable(actionStartActivity(open)),
            contentAlignment = Alignment.BottomStart,
        ) {
            when (entry) {
                is Entry.Image -> {
                    Image(
                        ImageProvider(entry.bitmap),
                        contentDescription = context.getString(R.string.latest_image_from, entry.webcam.name),
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                    Caption(entry, isSmall, isLarge)
                }
                is Entry.Unavailable -> Unavailable(entry.webcam.name, context.getString(R.string.image_unavailable), isSmall)
                Entry.NoWebcam -> Unavailable(context.getString(R.string.app_name), context.getString(R.string.no_webcam_available), isSmall)
            }
        }
    }

    /** Name and capture time over a dark gradient, so they stay readable on snow. */
    @Composable
    private fun Caption(entry: Entry.Image, isSmall: Boolean, isLarge: Boolean) {
        val context = LocalContext.current
        val padding = if (isSmall) 10.dp else if (isLarge) 20.dp else 14.dp
        Column(GlanceModifier.fillMaxWidth().background(ImageProvider(R.drawable.widget_scrim)).padding(padding)) {
            Text(
                entry.webcam.name,
                maxLines = if (isSmall || isLarge) 2 else 1,
                style = TextStyle(color = White, fontWeight = FontWeight.Medium, fontSize = if (isSmall) 14.sp else if (isLarge) 20.sp else 16.sp),
            )
            if (!isSmall) {
                Text(entry.resort.name, maxLines = 1, style = TextStyle(color = Dimmed, fontSize = if (isLarge) 15.sp else 12.sp))
            }
            // Never presents an old image as current: stale ones get an orange badge with their date.
            val freshness = SnapshotFreshness.of(entry.snapshot)
            val label = freshness.shortLabel(entry.resort.timeZone)
            when (freshness) {
                is SnapshotFreshness.Recent -> Text(
                    label.orEmpty(),
                    style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = if (isLarge) 34.sp else 22.sp),
                )
                is SnapshotFreshness.Stale -> Box(
                    GlanceModifier.background(Orange).cornerRadius(12.dp).padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        context.getString(R.string.freshness_last_image, label.orEmpty()),
                        maxLines = 1,
                        style = TextStyle(color = White, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    )
                }
                SnapshotFreshness.Unknown -> Text(
                    context.getString(R.string.freshness_unknown),
                    style = TextStyle(color = Dimmed, fontSize = 12.sp),
                )
            }
        }
    }

    @Composable
    private fun Unavailable(title: String, message: String, isSmall: Boolean) {
        Column(GlanceModifier.fillMaxSize().padding(if (isSmall) 12.dp else 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, maxLines = 2, style = TextStyle(color = White, fontWeight = FontWeight.Medium, fontSize = 16.sp))
            Text(message, style = TextStyle(color = Dimmed, fontSize = 12.sp))
        }
    }

    companion object {
        /** The webcam chosen in the widget's settings; absent to follow the default resort. */
        val WEBCAM_KEY = stringPreferencesKey("webcamID")

        private const val WIDGET_PIXEL_SIZE = 800
        private val White = ColorProvider(Color.White)
        private val Dimmed = ColorProvider(Color.White.copy(alpha = 0.8f))
        /** iOS system orange, the stale badge. */
        private val Orange = ColorProvider(Color(0xFFFF9500))
    }
}

class WebcamWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WebcamWidget()
}
