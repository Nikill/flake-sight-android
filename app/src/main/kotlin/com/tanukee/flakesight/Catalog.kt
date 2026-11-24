package com.tanukee.flakesight

import android.content.Context
import com.tanukee.flakesight.core.DisabledContentStore
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.removing
import java.io.File

/** The catalogue shipped with the app, read once. */
private val bundled by lazy { ResortRepository.bundled() }

@Volatile
private var disabledContent: DisabledContentStore? = null

/** The withdrawn content last downloaded, kept in the app's files for the widget and the background capture too. */
internal fun disabledContentStore(context: Context): DisabledContentStore = disabledContent ?: synchronized(bundled) {
    disabledContent ?: DisabledContentStore(File(context.applicationContext.filesDir, "disabled-content.json")).also { disabledContent = it }
}

/**
 * The bundled catalogue without the content withdrawn remotely. Every surface
 * that shows the catalogue starts from this. Port of `ResortRepository.available()`.
 */
internal fun availableRepository(context: Context): ResortRepository =
    bundled.removing(disabledContentStore(context).content.value)
