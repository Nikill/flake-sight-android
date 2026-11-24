package com.tanukee.flakesight

import android.net.http.HttpResponseCache
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import com.tanukee.flakesight.core.FavoriteResort
import com.tanukee.flakesight.core.FavoritesStore
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortRepository
import java.io.IOException
import com.tanukee.flakesight.core.Webcam
import java.io.File

class MainActivity : ComponentActivity() {
    /** On the device only, never sent anywhere (same privacy promise as iOS). */
    private val favoritesStore by lazy { FavoritesStore(File(filesDir, "favorites.json")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lets HttpURLConnection, under WebcamService, honour providers' cache headers,
        // so repeated views do not download large panoramas again.
        if (HttpResponseCache.getInstalled() == null) {
            HttpResponseCache.install(File(cacheDir, "WebcamImages"), 150L * 1024 * 1024)
        }
        val windowLayoutInfo = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            FlakeSightTheme {
                val repository = remember { ResortRepository.bundled() }
                val layoutInfo by windowLayoutInfo.collectAsState(initial = null)
                ResortBrowser(repository, favoritesStore, layoutInfo)
            }
        }
    }
}

/** Width from which the list stays beside the selected resort: an unfolded foldable, a tablet. */
private val TWO_PANE_MIN_WIDTH = 600.dp

/**
 * Root screen. Port of `ResortBrowserView`: a narrow window (phone, folded
 * foldable) stacks the screens over the resort list; a wide one (unfolded,
 * tablet) keeps the list in a left pane beside the selected resort.
 *
 * Both layouts read the same saved IDs, so folding or unfolding keeps the
 * resort and webcam on screen.
 */
@Composable
fun ResortBrowser(repository: ResortRepository, favoritesStore: FavoritesStore, layoutInfo: WindowLayoutInfo?) {
    // Saved IDs rather than a navigation library: two levels, resort then webcam.
    var selectedResortID by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWebcamID by rememberSaveable { mutableStateOf<String?>(null) }
    val resort = selectedResortID?.let(repository::resort)
    val webcam = selectedWebcamID?.let { resort?.webcam(it) }
    val selectResort: (Resort) -> Unit = {
        selectedResortID = it.id
        selectedWebcamID = null
    }
    val selectWebcam: (Webcam) -> Unit = { selectedWebcamID = it.id }
    val closeWebcam = { selectedWebcamID = null }

    val favorites by favoritesStore.favorites.collectAsState()
    // Remembered between launches under the iOS key, `favoritesOnTop`.
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var favoritesOnTop by remember { mutableStateOf(settings.getBoolean("favoritesOnTop", true)) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val visibleResorts = remember(favorites, favoritesOnTop, searchText) {
        repository.sortedResorts(favorites.map { it.resortID }, favoritesOnTop).filter { it.matches(searchText) }
    }
    @Composable
    fun ResortList(selectedResortID: String?) = ResortListScreen(
        visibleResorts,
        favorites,
        onSelect = selectResort,
        searchText = searchText,
        onSearchTextChange = { searchText = it },
        favoritesOnTop = favoritesOnTop,
        onFavoritesOnTopChange = {
            favoritesOnTop = it
            settings.edit().putBoolean("favoritesOnTop", it).apply()
        },
        selectedResortID = selectedResortID,
    )
    // A change that could not be saved is said, not silently dropped (`FavoritesSaveFailure` on iOS).
    var saveFailure by remember { mutableStateOf<String?>(null) }
    val changeFavorites: (FavoritesStore.() -> Unit) -> Unit = { change ->
        try {
            favoritesStore.change()
        } catch (e: IOException) {
            saveFailure = e.localizedMessage ?: e.toString()
        }
    }
    @Composable
    fun ResortPane(resort: Resort, onBack: (() -> Unit)?) = ResortScreen(
        resort,
        favorite = favorites.firstOrNull { it.resortID == resort.id },
        onToggleFavorite = { changeFavorites { toggleFavorite(resort.id) } },
        onSetDefault = { changeFavorites { setDefault(resort.id) } },
        onBack = onBack,
        onSelectWebcam = selectWebcam,
    )

    saveFailure?.let { reason ->
        AlertDialog(
            onDismissRequest = { saveFailure = null },
            confirmButton = { TextButton(onClick = { saveFailure = null }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.favourites_not_saved)) },
            text = { Text(stringResource(R.string.change_not_saved, reason)) },
        )
    }

    BoxWithConstraints {
        val windowWidth = maxWidth
        if (windowWidth < TWO_PANE_MIN_WIDTH) {
            when {
                resort == null -> ResortList(selectedResortID = null)
                webcam == null -> {
                    BackHandler { selectedResortID = null }
                    ResortPane(resort, onBack = { selectedResortID = null })
                }
                else -> {
                    BackHandler(onBack = closeWebcam)
                    WebcamScreen(webcam, resort, onBack = closeWebcam)
                }
            }
        } else {
            Row {
                Box(Modifier.width(listPaneWidth(windowWidth, layoutInfo))) {
                    ResortList(selectedResortID)
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    when {
                        resort == null -> NoResortSelected()
                        // The resort is the pane's root: Back only closes what is over it.
                        webcam == null -> ResortPane(resort, onBack = null)
                        else -> {
                            BackHandler(onBack = closeWebcam)
                            WebcamScreen(webcam, resort, onBack = closeWebcam)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The list pane ends at the hinge of a foldable held like a book, so no screen
 * straddles the fold; otherwise it takes 40% of the width, within reason.
 */
@Composable
private fun listPaneWidth(windowWidth: Dp, layoutInfo: WindowLayoutInfo?): Dp {
    val hinge = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
    return hinge?.let { with(LocalDensity.current) { it.bounds.left.toDp() } }
        ?: (windowWidth * 0.4f).coerceIn(280.dp, 360.dp)
}

/** The detail pane before any resort is chosen. iOS shows the map there, not ported yet. */
@Composable
private fun NoResortSelected() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.select_resort),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The favourite star's colour: iOS system yellow, as on the iPhone. */
val FavoriteYellow = Color(0xFFFFCC00)

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
fun ResortListScreen(
    resorts: List<Resort>,
    favorites: List<FavoriteResort>,
    onSelect: (Resort) -> Unit,
    searchText: String = "",
    onSearchTextChange: (String) -> Unit = {},
    favoritesOnTop: Boolean = true,
    onFavoritesOnTopChange: (Boolean) -> Unit = {},
    selectedResortID: String? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.resorts_title)) },
                actions = { SortMenu(favoritesOnTop, onFavoritesOnTopChange) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchField(searchText, onSearchTextChange)
            if (resorts.isEmpty() && searchText.isNotBlank()) {
                Text(
                    stringResource(R.string.no_search_results, searchText.trim()),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            ResortRows(resorts, favorites, onSelect, selectedResortID)
        }
    }
}

/** Searches names, regions and webcam names, ignoring case and accents (`Resort.matches`). */
@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        placeholder = { Text(stringResource(R.string.search_prompt)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Port of the list's Sort menu; only the name order exists so far, so it holds "Favourites on Top". */
@Composable
private fun SortMenu(favoritesOnTop: Boolean, onFavoritesOnTopChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.favourites_on_top)) },
                trailingIcon = { Checkbox(checked = favoritesOnTop, onCheckedChange = null) },
                onClick = { onFavoritesOnTopChange(!favoritesOnTop) },
            )
        }
    }
}

@Composable
private fun ResortRows(
    resorts: List<Resort>,
    favorites: List<FavoriteResort>,
    onSelect: (Resort) -> Unit,
    selectedResortID: String?,
) {
    LazyColumn {
        items(resorts, key = { it.id }) { resort ->
            val favorite = favorites.firstOrNull { it.resortID == resort.id }
            ListItem(
                modifier = Modifier.clickable { onSelect(resort) },
                // Marks the resort shown in the other pane.
                colors = ListItemDefaults.colors(
                    containerColor = if (resort.id == selectedResortID) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                supportingContent = { Text(resort.region) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (resort.webcams.isEmpty()) {
                                stringResource(R.string.forecast_only)
                            } else {
                                pluralStringResource(R.plurals.webcam_count, resort.webcams.size, resort.webcams.size)
                            },
                        )
                        if (favorite != null) {
                            if (favorite.isDefault) Text(stringResource(R.string.default_label))
                            Icon(
                                painterResource(R.drawable.ic_star),
                                contentDescription = stringResource(R.string.favourite),
                                tint = FavoriteYellow,
                            )
                        }
                    }
                },
            ) {
                Text(resort.name)
            }
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ResortListPreview() {
    FlakeSightTheme { ResortListScreen(ResortRepository.bundled().resorts.take(8), emptyList(), onSelect = {}) }
}
