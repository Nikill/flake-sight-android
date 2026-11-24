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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.tanukee.flakesight.core.DeepLink
import android.net.Uri
import android.provider.Settings
import com.tanukee.flakesight.core.Coordinate
import com.tanukee.flakesight.core.FavoriteResort
import com.tanukee.flakesight.core.FavoritesStore
import com.tanukee.flakesight.core.Resort
import com.tanukee.flakesight.core.ResortRepository
import com.tanukee.flakesight.core.ResortSortOrder
import com.tanukee.flakesight.core.SnowDepthReport
import com.tanukee.flakesight.core.SnowDepthStore
import com.tanukee.flakesight.core.SnowTotals
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import java.io.IOException
import com.tanukee.flakesight.core.Webcam
import java.io.File

class MainActivity : ComponentActivity() {
    /** On the device only, never sent anywhere (same privacy promise as iOS). */
    private val favoritesStore by lazy { FavoritesStore(File(filesDir, "favorites.json")) }

    /** The last avalanche bulletins' snow depths, kept for offline use. */
    private val snowDepthStore by lazy { SnowDepthStore(File(filesDir, "snow-depth.json")) }

    /** The webcam a tapped widget asks to open (`skipatrol://webcam/<id>`), until the browser shows it. */
    private var deepLinkWebcamID by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkWebcamID = intent.dataString?.let(DeepLink::webcamID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lets HttpURLConnection, under WebcamService, honour providers' cache headers,
        // so repeated views do not download large panoramas again.
        if (HttpResponseCache.getInstalled() == null) {
            HttpResponseCache.install(File(cacheDir, "WebcamImages"), 150L * 1024 * 1024)
        }
        // Not again after a rotation, which would reopen a webcam the user has closed.
        if (savedInstanceState == null) deepLinkWebcamID = intent.dataString?.let(DeepLink::webcamID)
        val windowLayoutInfo = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            FlakeSightTheme {
                val repository = remember { ResortRepository.bundled() }
                val layoutInfo by windowLayoutInfo.collectAsState(initial = null)
                ResortBrowser(repository, favoritesStore, snowDepthStore, layoutInfo, deepLinkWebcamID) { deepLinkWebcamID = null }
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
fun ResortBrowser(
    repository: ResortRepository,
    favoritesStore: FavoritesStore,
    snowDepthStore: SnowDepthStore,
    layoutInfo: WindowLayoutInfo?,
    deepLinkWebcamID: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    // Refreshed each time the app opens; a failure keeps the stored report.
    val snowDepthReport by snowDepthStore.report.collectAsState()
    LaunchedEffect(snowDepthStore) { snowDepthStore.refresh() }
    // Saved state rather than a navigation library: the resort, then a webcam or the advanced weather over it.
    var selectedResortID by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWebcamID by rememberSaveable { mutableStateOf<String?>(null) }
    var isShowingForecast by rememberSaveable { mutableStateOf(false) }
    // Under the resort on a phone, so Back from a resort opened on the map returns to the map.
    var isShowingMap by rememberSaveable { mutableStateOf(false) }
    val resort = selectedResortID?.let(repository::resort)
    val webcam = selectedWebcamID?.let { resort?.webcam(it) }
    val selectResort: (Resort) -> Unit = {
        selectedResortID = it.id
        selectedWebcamID = null
        isShowingForecast = false
    }
    val selectWebcam: (Webcam) -> Unit = { selectedWebcamID = it.id }
    val closeOverResort = {
        selectedWebcamID = null
        isShowingForecast = false
    }
    val isOverResort = webcam != null || isShowingForecast
    LaunchedEffect(deepLinkWebcamID) {
        deepLinkWebcamID?.let(repository::webcam)?.let { (resort, webcam) ->
            selectedResortID = resort.id
            selectedWebcamID = webcam.id
            isShowingForecast = false
        }
        if (deepLinkWebcamID != null) onDeepLinkHandled()
    }

    val favorites by favoritesStore.favorites.collectAsState()
    // The widget follows the default resort: redraw it when that changes, not on every launch.
    val defaultResortID = favorites.firstOrNull { it.isDefault }?.resortID
    var widgetResortID by rememberSaveable { mutableStateOf(defaultResortID) }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(defaultResortID) {
        if (defaultResortID != widgetResortID) {
            widgetResortID = defaultResortID
            WebcamWidget().updateAll(appContext)
        }
    }
    // Remembered between launches under the iOS key, `favoritesOnTop`.
    val settings = LocalContext.current.let { remember { it.getSharedPreferences("settings", Context.MODE_PRIVATE) } }
    var favoritesOnTop by remember { mutableStateOf(settings.getBoolean("favoritesOnTop", true)) }
    var sortOrder by remember { mutableStateOf(ResortSortOrder.fromRawValue(settings.getString("resortSortOrder", null))) }
    val changeSortOrder: (ResortSortOrder) -> Unit = {
        sortOrder = it
        settings.edit().putString("resortSortOrder", it.rawValue).apply()
    }
    // Distance sorting needs a fresh position each time the list opens; refused, the list goes back to names.
    val context = LocalContext.current
    val locationProvider = remember { LocationProvider(context.applicationContext) }
    var isShowingLocationDenied by remember { mutableStateOf(false) }
    val requestSortLocation = rememberLocationRequest(locationProvider, onDenied = {
        if (sortOrder == ResortSortOrder.DISTANCE) {
            changeSortOrder(ResortSortOrder.NAME)
            isShowingLocationDenied = true
        }
    })
    LaunchedEffect(sortOrder) {
        if (sortOrder == ResortSortOrder.DISTANCE) requestSortLocation()
    }
    val sortLocation = if (sortOrder == ResortSortOrder.DISTANCE) locationProvider.location else null
    var searchText by rememberSaveable { mutableStateOf("") }
    // The totals feed every row's recent snow as well as the snow sorts, so they load whatever the sort.
    // On failure the list simply stays alphabetical without figures, as the forecast strip hides itself.
    var snowTotals by remember { mutableStateOf(forecastService.cachedSnowTotals(repository.resorts)) }
    var snowTotalsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            snowTotals = forecastService.snowTotals(repository.resorts)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            snowTotalsFailed = true
        }
    }
    val visibleResorts = remember(favorites, favoritesOnTop, sortOrder, sortLocation, snowTotals, searchText) {
        repository.sortedResorts(sortOrder, favorites.map { it.resortID }, favoritesOnTop, sortLocation, snowTotals)
            .filter { it.matches(searchText) }
    }
    // On a wide window, closes the resort beside the list to show the map there.
    val showMap = {
        selectedResortID = null
        isShowingMap = true
    }
    @Composable
    fun ResortList(selectedResortID: String?, isSidebar: Boolean) = ResortListScreen(
        visibleResorts,
        favorites,
        onSelect = selectResort,
        searchText = searchText,
        onSearchTextChange = { searchText = it },
        sortOrder = sortOrder,
        onSortOrderChange = changeSortOrder,
        favoritesOnTop = favoritesOnTop,
        onFavoritesOnTopChange = {
            favoritesOnTop = it
            settings.edit().putBoolean("favoritesOnTop", it).apply()
        },
        snowTotals = snowTotals,
        snowDepthReport = snowDepthReport,
        selectedResortID = selectedResortID,
        onShowMap = showMap,
        sortLocation = sortLocation,
        isSidebar = isSidebar,
    )
    val requestMapLocation = rememberLocationRequest(locationProvider)
    @Composable
    fun Map(onBack: (() -> Unit)?) = ResortMapScreen(
        repository.resorts,
        snowTotals,
        snowTotalsFailed,
        onOpenResort = selectResort,
        onBack = onBack,
        userLocation = locationProvider.location,
        onRequestLocation = requestMapLocation,
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
        snowDepth = snowDepthReport?.reading(resort),
        onOpenForecast = { isShowingForecast = true },
    )

    /** The webcam or the advanced weather, over its resort; Back returns to the resort. */
    @Composable
    fun OverResort(resort: Resort) {
        BackHandler(onBack = closeOverResort)
        if (webcam != null) {
            WebcamScreen(webcam, resort, onBack = closeOverResort)
        } else {
            AdvancedForecastScreen(resort, snowDepthReport?.reading(resort), onBack = closeOverResort)
        }
    }

    if (isShowingLocationDenied) {
        AlertDialog(
            onDismissRequest = { isShowingLocationDenied = false },
            confirmButton = {
                TextButton(onClick = {
                    isShowingLocationDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { isShowingLocationDenied = false }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.location_access_off)) },
            text = { Text(stringResource(R.string.location_access_off_message)) },
        )
    }

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
                resort == null && isShowingMap -> {
                    BackHandler { isShowingMap = false }
                    Map(onBack = { isShowingMap = false })
                }
                resort == null -> ResortList(selectedResortID = null, isSidebar = false)
                !isOverResort -> {
                    BackHandler { selectedResortID = null }
                    ResortPane(resort, onBack = { selectedResortID = null })
                }
                else -> OverResort(resort)
            }
        } else {
            Row {
                Box(Modifier.width(listPaneWidth(windowWidth, layoutInfo))) {
                    ResortList(selectedResortID, isSidebar = true)
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    when {
                        // The map until a resort is chosen, as on iPad.
                        resort == null -> Map(onBack = null)
                        // The resort is the pane's root: Back only closes what is over it.
                        !isOverResort -> ResortPane(resort, onBack = null)
                        else -> OverResort(resort)
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

private val ROW_GROUP_RADIUS = 24.dp

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
    sortOrder: ResortSortOrder = ResortSortOrder.NAME,
    onSortOrderChange: (ResortSortOrder) -> Unit = {},
    favoritesOnTop: Boolean = true,
    onFavoritesOnTopChange: (Boolean) -> Unit = {},
    snowTotals: SnowTotals? = null,
    snowDepthReport: SnowDepthReport? = null,
    selectedResortID: String? = null,
    onShowMap: (() -> Unit)? = null,
    sortLocation: Coordinate? = null,
    /** Beside the selected resort or the map on a wide window, as the iPad and unfolded iPhone sidebar. */
    isSidebar: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.resorts_title)) },
                actions = {
                    if (onShowMap != null) {
                        // The map is what shows beside the list when no resort is selected.
                        IconButton(onClick = onShowMap, enabled = !isSidebar || selectedResortID != null) {
                            Icon(painterResource(R.drawable.ic_map), contentDescription = stringResource(R.string.map))
                        }
                    }
                    SortMenu(sortOrder, onSortOrderChange, favoritesOnTop, onFavoritesOnTopChange)
                },
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
            ResortRows(resorts, favorites, favoritesOnTop, onSelect, sortOrder, sortLocation, snowTotals, snowDepthReport, selectedResortID)
        }
    }
}

/** Searches names, regions and webcam names, ignoring case and accents (`Resort.matches`). */
@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    // A filled pill rather than a boxed form field, like the system search fields.
    TextField(
        value = text,
        onValueChange = onTextChange,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
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

/** Port of the list's Sort menu: the orders, then "Favourites on Top". Distance comes with location. */
@Composable
private fun SortMenu(
    sortOrder: ResortSortOrder,
    onSortOrderChange: (ResortSortOrder) -> Unit,
    favoritesOnTop: Boolean,
    onFavoritesOnTopChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ResortSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortOrderLabel(order))) },
                    leadingIcon = { RadioButton(selected = order == sortOrder, onClick = null) },
                    onClick = {
                        onSortOrderChange(order)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.favourites_on_top)) },
                trailingIcon = { Checkbox(checked = favoritesOnTop, onCheckedChange = null) },
                onClick = { onFavoritesOnTopChange(!favoritesOnTop) },
            )
        }
    }
}

private fun sortOrderLabel(order: ResortSortOrder) = when (order) {
    ResortSortOrder.NAME -> R.string.sort_name
    ResortSortOrder.DISTANCE -> R.string.sort_distance
    ResortSortOrder.FORECAST_SNOW -> R.string.sort_snow_forecast
    ResortSortOrder.RECENT_SNOW -> R.string.sort_recent_snow
}

/** "24 cm in the last 7 days" or "12 cm expected in the next 5 days", read in place of a row's figure. */
@Composable
internal fun snowSentence(kind: SnowTotals.Kind, amount: String) = when (kind) {
    SnowTotals.Kind.RECENT -> stringResource(R.string.snow_in_last_days, amount, kind.dayCount)
    SnowTotals.Kind.FORECAST -> stringResource(R.string.snow_expected_next_days, amount, kind.dayCount)
}

@Composable
private fun ResortRows(
    resorts: List<Resort>,
    favorites: List<FavoriteResort>,
    favoritesOnTop: Boolean,
    onSelect: (Resort) -> Unit,
    sortOrder: ResortSortOrder,
    sortLocation: Coordinate?,
    snowTotals: SnowTotals?,
    snowDepthReport: SnowDepthReport?,
    selectedResortID: String?,
) {
    val locale = LocalConfiguration.current.locales[0]
    // A new order, or the position arriving, starts from the top; the list would otherwise stay on the row that was visible.
    val listState = rememberLazyListState()
    LaunchedEffect(sortOrder, sortLocation) { listState.scrollToItem(0) }
    // Favourites on top sit in a rounded group of their own, set apart from the other resorts.
    val favoriteIDs = favorites.map { it.resortID }.toSet()
    val groupedCount = if (favoritesOnTop) resorts.takeWhile { it.id in favoriteIDs }.size else 0
    val groupColor = MaterialTheme.colorScheme.surfaceContainer
    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(resorts, key = { _, resort -> resort.id }) { index, resort ->
            val favorite = favorites.firstOrNull { it.resortID == resort.id }
            val isGrouped = index < groupedCount
            val groupShape = when {
                groupedCount == 1 -> RoundedCornerShape(ROW_GROUP_RADIUS)
                index == 0 -> RoundedCornerShape(topStart = ROW_GROUP_RADIUS, topEnd = ROW_GROUP_RADIUS)
                index == groupedCount - 1 -> RoundedCornerShape(bottomStart = ROW_GROUP_RADIUS, bottomEnd = ROW_GROUP_RADIUS)
                else -> RectangleShape
            }
            ListItem(
                // Rows without separators, each a rounded card when pressed or selected, as the iOS sidebar.
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, bottom = if (index == groupedCount - 1) 16.dp else 0.dp)
                    .then(if (isGrouped) Modifier.background(groupColor, groupShape) else Modifier)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(resort) },
                // Marks the resort shown in the other pane.
                colors = ListItemDefaults.colors(
                    containerColor = if (resort.id == selectedResortID) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
                supportingContent = {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(resort.region)
                            // The snow fallen lately, unless the list is sorted by it and the figure on the right says it.
                            val recent = snowTotals?.centimetres(SnowTotals.Kind.RECENT, resort.id)
                            if (recent != null && sortOrder != ResortSortOrder.RECENT_SNOW) {
                                val amount = centimetres(recent, locale)
                                val sentence = snowSentence(SnowTotals.Kind.RECENT, amount)
                                Text("· ❄ $amount", modifier = Modifier.semantics { contentDescription = sentence })
                            }
                        }
                        // The bulletin's depth on its own line, so a narrow list keeps its width for the name.
                        snowDepthReport?.reading(resort)?.let { reading ->
                            val spoken = spokenSnowDepth(reading, locale)
                            Text(compactSnowDepth(reading, locale), modifier = Modifier.semantics { contentDescription = spoken })
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val kind = sortOrder.snowKind
                        // The figure the list is sorted by.
                        if (kind != null) {
                            snowTotals?.centimetres(kind, resort.id)?.let { cm ->
                                val amount = centimetres(cm, locale)
                                val sentence = snowSentence(kind, amount)
                                Text(amount, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = sentence })
                            }
                        } else if (sortLocation != null) {
                            Text(roadDistance(resort.distanceFrom(sortLocation), locale), fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(
                                if (resort.webcams.isEmpty()) {
                                    stringResource(R.string.forecast_only)
                                } else {
                                    pluralStringResource(R.plurals.webcam_count, resort.webcams.size, resort.webcams.size)
                                },
                            )
                        }
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
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ResortListPreview() {
    FlakeSightTheme { ResortListScreen(ResortRepository.bundled().resorts.take(8), emptyList(), onSelect = {}) }
}
