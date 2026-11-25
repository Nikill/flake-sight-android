package com.tanukee.flakesight

import android.content.Context
import com.tanukee.flakesight.core.CatalogUpdateStore
import com.tanukee.flakesight.core.DisabledContentStore
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.available
import java.io.File

/** The catalogue shipped with the app, read once. */
private val bundled by lazy { ResortRepository.bundled() }

@Volatile
private var disabledContent: DisabledContentStore? = null

@Volatile
private var catalogUpdate: CatalogUpdateStore? = null

/** The withdrawn content last downloaded, kept in the app's files for the widget and the background capture too. */
internal fun disabledContentStore(context: Context): DisabledContentStore = disabledContent ?: synchronized(bundled) {
    disabledContent ?: DisabledContentStore(File(context.applicationContext.filesDir, "disabled-content.json")).also { disabledContent = it }
}

/** The resorts published since this catalogue was copied, kept like the withdrawn content. */
internal fun catalogUpdateStore(context: Context): CatalogUpdateStore = catalogUpdate ?: synchronized(bundled) {
    catalogUpdate ?: CatalogUpdateStore(File(context.applicationContext.filesDir, "catalog-update.json")).also { catalogUpdate = it }
}

/**
 * The bundled catalogue with the last downloaded update applied, then without
 * the content withdrawn remotely. Every surface that shows the catalogue starts
 * from this. Port of `ResortRepository.available()`.
 */
internal fun availableRepository(context: Context): ResortRepository =
    bundled.available(catalogUpdateStore(context).update.value, disabledContentStore(context).content.value)
