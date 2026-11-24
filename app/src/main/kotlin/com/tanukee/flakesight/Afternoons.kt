package com.tanukee.flakesight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tanukee.flakesight.core.AfternoonSnapshotService
import com.tanukee.flakesight.core.AfternoonSnapshotStore
import com.tanukee.flakesight.core.AfternoonWindow
import com.tanukee.flakesight.core.FavoritesStore
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.Webcam
import com.tanukee.flakesight.core.WebcamSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

@Volatile
private var afternoons: AfternoonSnapshotService? = null

/**
 * The app's one afternoon image service, shared by the screens, the widget and
 * the background capture, which all run in this process. Its store sits in the
 * no-backup files, not the cache: an image of a webcam without archive cannot
 * be fetched again once the camera has moved on.
 */
internal fun afternoonService(context: Context): AfternoonSnapshotService = afternoons ?: synchronized(FavoritesStore::class) {
    afternoons ?: AfternoonSnapshotService(
        AfternoonSnapshotStore(File(context.applicationContext.noBackupFilesDir, "AfternoonSnapshots"), ::downscaled),
    ).also { service ->
        // The app keeps this up to date; read here too for the widget and the background capture.
        service.setFavoriteResortIDs(favoritesStore(context).favorites.value.map { it.resortID })
        afternoons = service
    }
}

/** Every download of [webcamService] is offered here, once the service exists. */
internal fun recordAfternoon(snapshot: WebcamSnapshot, webcam: Webcam, zone: ZoneId) {
    afternoons?.record(snapshot, webcam, zone)
}

internal fun favoritesStore(context: Context) = FavoritesStore(File(context.applicationContext.filesDir, "favorites.json"))

/** Longest side kept on disk: enough for the full-screen panorama, a tenth of the weight of the largest provider images. */
private const val AFTERNOON_PIXEL_SIZE = 4000

/** [data] itself when it is small enough, otherwise a JPEG whose longest side is 4000 px; `null` when it is not an image. */
private fun downscaled(data: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (max(bounds.outWidth, bounds.outHeight) <= AFTERNOON_PIXEL_SIZE) return data
    val bitmap = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
            val scale = AFTERNOON_PIXEL_SIZE.toFloat() / max(info.size.width, info.size.height)
            decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
        }
    }.getOrNull() ?: return null
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        output.toByteArray()
    }
}

/**
 * Downloads the favourite webcams' current images shortly before 16:00, so
 * those whose provider keeps no archive get an afternoon image even when nobody
 * opens the app. Port of `AfternoonCaptureTask`: Android decides when the work
 * really runs, often later; images the app or the widget download near 16:00
 * count too.
 */
class AfternoonCaptureWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            val repository = ResortRepository.bundled()
            val favorites = favoritesStore(applicationContext).favorites.value.mapNotNull { repository.resort(it.resortID) }
            afternoonService(applicationContext).captureFavorites(favorites, webcamService)
        } finally {
            // After this one ends: replacing the running work would cancel it.
            schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "afternoon-capture"

        /** A quarter of an hour early, well inside the afternoon window. */
        private val LEAD_TIME: Duration = Duration.ofMinutes(15)

        /**
         * Books a capture at the next 15:45 in Paris, where every catalogue resort is.
         * From the app, [ExistingWorkPolicy.KEEP] leaves a booking already made.
         */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP, now: Instant = Instant.now()) {
            val paris = ZoneId.of("Europe/Paris")
            var begin = AfternoonWindow.target(AfternoonWindow.day(now, paris), paris) - LEAD_TIME
            if (begin <= now) begin = begin.atZone(paris).plusDays(1).toInstant()
            val request = OneTimeWorkRequestBuilder<AfternoonCaptureWorker>()
                .setInitialDelay(Duration.between(now, begin))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
