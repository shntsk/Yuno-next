package com.yuno

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.yuno.icons.SfIcons

/** Opens a channel page from anywhere (player channel row, card avatars). Provided by the shell. */
val LocalChannelOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The channel's id when its address already says it (/channel/UC…), so its page can load alongside NewPipe's details. */
private fun channelIdIn(url: String): String? =
    url.substringAfter("/channel/", "").takeWhile { it != '/' && it != '?' }.takeIf { it.startsWith("UC") && it.length == 24 }

/**
 * A channel page (GitHub #53, Derek picked A "Underline tabs" in docs/design/channel-page.html):
 * the profile, then YouTube's tabs — Home (the creator's shelves), Videos (Latest / Popular /
 * Oldest), Shorts (played in the Shorts pager) and Playlists — each loaded the first time it's shown.
 */
/** Playlists, Shows and Podcasts: the same paged list of playlist rows. */
internal class PlaylistShelf(
    private val scope: CoroutineScope,
    private val fetch: suspend (String?) -> PlaylistPage,
) {
    var items by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var next: String? = null
    private var loaded = false
    private var job: Job? = null
    val hasMore get() = next != null
    val empty get() = loaded && !loading && error == null && items.isEmpty()
    val tried get() = loaded

    fun ensure() { if (!loaded) load(more = false) }
    fun more() { if (hasMore) load(more = true) }

    fun load(more: Boolean) {
        val token = if (more) next ?: return else null
        if (loading) return
        loaded = true
        loading = true
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { fetch(token) }
                .onSuccess { page ->
                    items = if (token == null) page.playlists else (items + page.playlists).distinctBy { it.id }
                    next = page.next
                }
                .onFailure { if (it is CancellationException) throw it; error = YouTube.friendlyError(it) }
            loading = false
        }
    }
}

@Stable
class ChannelState(val url: String, private val scope: CoroutineScope) {
    var details by mutableStateOf<ChannelDetails?>(null)
        private set
    /** The page's Home tab (tabs, featured video, shelves); null until loaded or if it couldn't be read. */
    var home by mutableStateOf<ChannelHome?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var subscribed by mutableStateOf<Boolean?>(null)
        private set
    /** The tab picked; [current] is the one shown (Home first, as on YouTube — Derek). */
    private var tab by mutableStateOf(ChannelTab.Home)
    /**
     * Each tab's scroll, kept here so back from a short, video, or playlist returns to the same row.
     * [list] is the Videos tab.
     */
    val list = LazyListState()
    val homeList = LazyListState()
    val liveList = LazyListState()
    val shortsList = LazyListState()
    val showsList = LazyListState()
    val podcastsList = LazyListState()
    val playlistsList = LazyListState()
    val postsList = LazyListState()
    val aboutList = LazyListState()

    val videos = FeedState(scope) { token -> filled(InnerTube.channelVideos(channelId(), token)) }
    val live = FeedState(scope) { token -> filled(InnerTube.channelLive(channelId(), tabParams(ChannelTab.Live), token)) }
    val shorts = FeedState(scope) { token -> InnerTube.channelShorts(channelId(), tabParams(ChannelTab.Shorts), token) }
    internal val playlists = PlaylistShelf(scope) { token -> InnerTube.channelPlaylists(channelId(), tabParams(ChannelTab.Playlists), token) }
    internal val shows = PlaylistShelf(scope) { token -> InnerTube.channelPlaylists(channelId(), tabParams(ChannelTab.Shows), token) }
    internal val podcasts = PlaylistShelf(scope) { token -> InnerTube.channelPlaylists(channelId(), tabParams(ChannelTab.Podcasts), token) }

    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
        private set
    var postsLoading by mutableStateOf(false)
        private set
    var postsError by mutableStateOf<String?>(null)
        private set
    private var postsNext: String? = null
    private var postsLoaded = false
    private var postsJob: Job? = null
    val postsHaveMore get() = postsNext != null
    val postsStarted get() = postsLoaded

    /** The Videos tab's search field. A query replaces that list until the field is closed. */
    var searchOpen by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var searchVideos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchNext: String? = null
    private var searchJob: Job? = null
    val searchHasMore get() = searchNext != null

    fun openSearch() { searchOpen = true }

    /** Item grids can be rows or a grid. Home, Posts and About keep their own layout. Shorts stays a grid. */
    var gridView by mutableStateOf(true)
        private set

    fun toggleGrid() { gridView = !gridView }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        searchQuery = ""
        searchVideos = emptyList()
        searchLoading = false
        searchError = null
        searchNext = null
    }

    fun updateSearch(query: String) {
        searchQuery = query
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            searchVideos = emptyList()
            searchLoading = false
            searchError = null
            searchNext = null
            return
        }
        searchVideos = emptyList()
        searchNext = null
        searchLoading = true
        searchError = null
        searchJob = scope.launch {
            kotlinx.coroutines.delay(400)
            if (details == null) return@launch
            runCatching { filled(InnerTube.channelSearch(channelId(), q)) }
                .onSuccess { page -> searchVideos = page.videos; searchNext = page.next }
                .onFailure { if (it is CancellationException) throw it; searchError = YouTube.friendlyError(it); searchVideos = emptyList(); searchNext = null }
            searchLoading = false
        }
    }

    private fun loadMoreSearch() {
        val token = searchNext ?: return
        if (searchLoading || details == null) return
        searchLoading = true
        searchJob = scope.launch {
            runCatching { filled(InnerTube.channelSearch(channelId(), searchQuery.trim(), token)) }
                .onSuccess { page -> searchVideos = (searchVideos + page.videos).distinctBy { it.id }; searchNext = page.next }
                .onFailure { if (it is CancellationException) throw it; searchError = YouTube.friendlyError(it) }
            searchLoading = false
        }
    }

    /** Home's shelves as shown: Shorts shelves only while Shorts are on. */
    val shelves: List<ChannelShelf>
        get() = home?.shelves.orEmpty().filter { ShortsSetting.enabled || it.shorts.isEmpty() }

    /**sx7
     * The tabs this channel published, in YouTube's order, then About. Home only when it has
     * something on it, Shorts only while Shorts are on. Without the page, the uploads alone.
     */
    val tabs: List<ChannelTab>
        get() {
            val h = home ?: return listOf(ChannelTab.Videos)
            val published = h.tabs.keys.filter { t ->
                when (t) {
                    ChannelTab.Home -> h.featured != null || shelves.isNotEmpty()
                    ChannelTab.Shorts -> ShortsSetting.enabled
                    ChannelTab.About -> false
                    else -> true
                }
            }
            val about = if (hasAbout()) listOf(ChannelTab.About) else emptyList()
            return (published + about).ifEmpty { listOf(ChannelTab.Videos) }
        }

    private fun hasAbout(): Boolean {
        val d = details ?: return false
        val about = home?.about
        return d.description.isNotBlank() || !home?.handle.isNullOrBlank() || d.subscribers > 0 ||
            about?.description?.isNotBlank() == true || about?.links?.isNotEmpty() == true
    }

    val current: ChannelTab get() = tabs.let { t -> tab.takeIf { it in t } ?: t.first() }

    init { load() }

    private fun channelId() = details?.id ?: error("channel not loaded")
    private fun tabParams(t: ChannelTab) = home?.tabs?.get(t) ?: error("no ${t.title} tab")

    /** The uploads list doesn't repeat the channel on every item; fill it in. */
    private fun fill(v: VideoItem): VideoItem {
        val d = details ?: return v
        return v.copy(channel = v.channel.ifBlank { d.name }, avatar = v.avatar ?: d.avatar, channelUrl = v.channelUrl ?: d.url)
    }

    private fun filled(page: FeedPage) = page.copy(videos = page.videos.map(::fill))

    fun load() {
        loading = true
        error = null
        scope.launch {
            // Signed out, the page loads alongside NewPipe's details when the address has the id. Signed
            // in it waits for the channel's name and picture, which go to Bells with its bell.
            val early = channelIdIn(url)?.takeIf { !Account.signedIn }?.let { id -> async { runCatching { InnerTube.channelHome(id, null, null) } } }
            runCatching { YouTube.channel(url) }
                .onSuccess { d ->
                    val page = early?.await() ?: runCatching { InnerTube.channelHome(d.id, d.name, d.avatar) }
                    page.exceptionOrNull()?.let { if (it is CancellationException) throw it; Log.w("Yuno", "channel: couldn't read ${d.id}'s page; uploads only", it) }
                    // Profile and tabs land together, so the page never shifts as the tabs arrive.
                    details = d
                    home = page.getOrNull()?.let { h ->
                        h.copy(featured = h.featured?.let(::fill), shelves = h.shelves.map { s -> s.copy(videos = s.videos.map(::fill)) })
                    }
                    subscribed = home?.subscribed
                }
                .onFailure { if (it is CancellationException) throw it; error = YouTube.friendlyError(it) }
            loading = false
        }
    }

    fun show(t: ChannelTab) { tab = t }

    /** What [t] shows, loaded the first time it's shown. */
    fun ensureLoaded(t: ChannelTab) {
        if (details == null) return
        when (t) {
            ChannelTab.Videos -> videos.ensureLoaded()
            ChannelTab.Live -> live.ensureLoaded()
            ChannelTab.Shorts -> shorts.ensureLoaded()
            ChannelTab.Shows -> shows.ensure()
            ChannelTab.Podcasts -> podcasts.ensure()
            ChannelTab.Playlists -> playlists.ensure()
            ChannelTab.Posts -> if (!postsLoaded) loadPosts(more = false)
            ChannelTab.Home, ChannelTab.About -> Unit
        }
    }

    fun loadMore() {
        if (searchOpen && searchQuery.isNotBlank() && current == ChannelTab.Videos) {
            loadMoreSearch()
            return
        }
        when (current) {
            ChannelTab.Videos -> videos.loadMore()
            ChannelTab.Live -> live.loadMore()
            ChannelTab.Shorts -> shorts.loadMore()
            ChannelTab.Shows -> shows.more()
            ChannelTab.Podcasts -> podcasts.more()
            ChannelTab.Playlists -> playlists.more()
            ChannelTab.Posts -> loadPosts(more = true)
            ChannelTab.Home, ChannelTab.About -> Unit
        }
    }

    fun loadPosts(more: Boolean) {
        val token = if (more) postsNext ?: return else null
        if (postsLoading) return
        postsLoaded = true
        postsLoading = true
        postsError = null
        postsJob?.cancel()
        postsJob = scope.launch {
            val d = details
            runCatching { InnerTube.channelPosts(channelId(), tabParams(ChannelTab.Posts), token, d?.name.orEmpty(), d?.avatar) }
                .onSuccess { p ->
                    posts = if (token == null) p.posts else (posts + p.posts).distinctBy { it.id }
                    postsNext = p.next
                }
                .onFailure { if (it is CancellationException) throw it; postsError = YouTube.friendlyError(it) }
            postsLoading = false
        }
    }

    /** The Shorts tab's shorts for the pager, from [start]; it pages on through the channel by itself. */
    fun shortsFrom(start: Int): ShortsList {
        val id = channelId()
        val params = tabParams(ChannelTab.Shorts)
        return ShortsList(shorts.videos, start, shorts.nextToken) { token -> InnerTube.channelShorts(id, params, token) }
    }

    fun toggleSubscribe(onError: (String) -> Unit) {
        val id = details?.id ?: return
        val before = subscribed ?: return
        subscribed = !before
        scope.launch {
            runCatching { InnerTube.setSubscribed(id, !before) }
                .onSuccess { if (before) Bells.forget(id) }
                .onFailure { subscribed = before; onError(str(R.string.ui_subscription_error)) }
        }
    }
}

private const val ProfileKey = "header"
private const val TabsKey = "tabs"

@Composable
fun ChannelScreen(
    state: ChannelState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onMessage: (String) -> Unit,
    /** The colour the shell's bottom fade should settle into. Null once this page is gone. */
    onFootColor: (Color?) -> Unit = {},
) {
    val d = state.details
    // The page is the colour that shows up most in the profile picture and the banner,
    // unless Settings › Appearance has channel colors turned off.
    val tinted = ChannelColorSetting.enabled
    val palette = rememberArtworkPalette(
        if (tinted) d?.avatar else null,
        if (tinted) d?.banner else null,
    )
    SideEffect { onFootColor(palette.wash) }
    DisposableEffect(Unit) { onDispose { onFootColor(null) } }

    val scheme = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme, typography = BitChordTypography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge.merge(OwnDirectionText)) {
            ChannelPage(state, contentPadding, palette, onOpen, onOpenShorts, onOpenPlaylist, onMessage)
        }
    }
}

@Composable
private fun ChannelPage(
    state: ChannelState,
    contentPadding: PaddingValues,
    palette: ArtworkPalette,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onMessage: (String) -> Unit,
) {
    val columns = gridColumns()
    val barHeight = topBarHeight()
    val tabs = state.tabs
    val scope = rememberCoroutineScope()
    // Until the page arrives the only tab is a placeholder, so the pager must not treat it as the
    // channel's Videos tab or remember it as the one the user opened.
    val pager = key(tabs) {
        val start = if (state.home == null) 0 else tabs.indexOf(state.current).coerceAtLeast(0)
        rememberPagerState(initialPage = start, pageCount = { tabs.size.coerceAtLeast(1) })
    }
    val settled = tabs.getOrNull(pager.settledPage)
    val visible = tabs.getOrNull(pager.currentPage)
    SideEffect {
        if (state.details != null && visible != null && visible != state.current) state.show(visible)
    }
    LaunchedEffect(settled, state.details, state.home, state.loading) {
        if (state.loading || state.details == null) return@LaunchedEffect
        val t = settled ?: return@LaunchedEffect
        state.show(t)
        state.ensureLoaded(t)
    }
    fun pick(t: ChannelTab) {
        val index = tabs.indexOf(t)
        if (index < 0 || index == pager.currentPage) return
        scope.launch { pager.animateScrollToPage(index) }
    }
    val page = palette.wash

    Box(Modifier.fillMaxSize().background(page)) {
        val d = state.details
        when {
            d == null && state.error != null -> Box(Modifier.padding(top = barHeight)) {
                EmptyState(
                    Icons.Rounded.CloudOff, stringResource(R.string.ui_channel_error_title), state.error.orEmpty(),
                    action = { PillButton(stringResource(R.string.ui_try_again), state::load) },
                )
            }
            d == null -> LazyColumn(contentPadding = contentPadding.withTop(barHeight)) {
                item { ChannelSkeletons() }
            }
            else -> ChannelPagerBody(state, d, tabs, pager, palette, contentPadding, barHeight, columns, onOpen, onOpenShorts, onOpenPlaylist, onMessage, ::pick)
        }
    }
}

/** The artist page's side circles, and the gap between the pill and the bell. */
private val ArtistSideButton = 52.dp
private val ArtistActionGap = 12.dp
private const val LightFillAlpha = 0.07f
private const val CircleEdgeAlpha = 0.10f

/** The old wide banner was 5.6. This is a bit taller than the last step, with a tighter corner. */
private const val BannerAspect = 5.6f / 1.55f
private const val BannerRadius = 14
private val ProfileSize = 72

/**
 * Banner, then the profile picture beside the name, @handle and subscriber count
 * (those three share the picture's height, spaced evenly), then a full-width Subscribe pill.
 */
@Composable
private fun ChannelName(state: ChannelState, d: ChannelDetails, palette: ArtworkPalette, onMessage: (String) -> Unit) {
    val handle = state.home?.handle
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
        d.banner?.let {
            Thumbnail(it, Modifier.fillMaxWidth().aspectRatio(BannerAspect), radius = BannerRadius)
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(d.avatar, d.name, size = ProfileSize)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).height(ProfileSize.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                Text(
                    d.name,
                    style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 24.sp),
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!handle.isNullOrBlank()) {
                    Text(
                        handle,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 16.sp),
                        color = palette.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (d.subscribers > 0) {
                    Text(
                        subscribersLine(d.subscribers),
                        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp),
                        color = palette.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        state.subscribed?.let { subscribed ->
            Spacer(Modifier.height(12.dp))
            SubscribeRow(state, d, subscribed, onMessage)
        }
    }
}

/** Subscribe fills the width. The bell sits at the end once subscribed. */
@Composable
private fun SubscribeRow(state: ChannelState, d: ChannelDetails, subscribed: Boolean, onMessage: (String) -> Unit) {
    val toggleBell = rememberBellToggle(onMessage)
    val channel = BellChannel(d.id, d.name, d.avatar)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArtistActionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubscribeWord(subscribed, Modifier.weight(1f)) { state.toggleSubscribe(onMessage) }
        if (subscribed) {
            val bell = Bells.isOn(d.id)
            ArtistCircle(
                icon = if (bell) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                contentDescription = stringResource(if (bell) R.string.app_bell_on_description else R.string.app_bell_off_description),
                onClick = { toggleBell(channel) },
            )
        }
    }
}

/** The page colour over whatever scrolls under the status bar. Off until the list moves. */
@Composable
private fun ChannelTopFade(list: LazyListState, page: Color, modifier: Modifier = Modifier) {
    val brush = remember(page) {
        Brush.verticalGradient(
            colorStops = Array(16) { i ->
                val t = i / 15f
                t to page.copy(alpha = androidx.compose.animation.core.EaseInCubic.transform(1f - t))
            },
        )
    }
    val alpha by remember(list) {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) return@derivedStateOf 1f
            val header = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.toFloat() ?: return@derivedStateOf 0f
            if (header <= 0f) return@derivedStateOf 0f
            (list.firstVisibleItemScrollOffset / header).coerceIn(0f, 1f)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .graphicsLayer { this.alpha = alpha }
            .drawBehind { drawRect(brush) },
    )
}

/**
 * The header is the first thing in the list, so it leaves with the same scroll as the rows.
 * The tab spacer sticks. The filter row is an ordinary item, so it leaves with the videos.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.channelScrollHead(headerPx: Float, header: androidx.compose.ui.unit.Dp, tabs: androidx.compose.ui.unit.Dp) {
    item(key = "channel-head") { Spacer(Modifier.height(header)) }
    // While the header spacer is still 0, a sticky tab spacer pins to the top and the page opens scrolled.
    if (headerPx > 0f) {
        stickyHeader(key = "channel-tabs") { Spacer(Modifier.height(tabs)) }
    }
}

/** The space under the tab row, on every tab: the same inset as the page's sides. */
private fun LazyListScope.pageGap() {
    item(key = "page-gap") { Spacer(Modifier.height(PageGutter)) }
}

@Composable
private fun ChannelPagerBody(
    state: ChannelState,
    d: ChannelDetails,
    tabs: List<ChannelTab>,
    pager: androidx.compose.foundation.pager.PagerState,
    palette: ArtworkPalette,
    contentPadding: PaddingValues,
    barHeight: androidx.compose.ui.unit.Dp,
    columns: Int,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onMessage: (String) -> Unit,
    pick: (ChannelTab) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var headerPx by remember { mutableFloatStateOf(0f) }
    var tabRowPx by remember { mutableFloatStateOf(0f) }
    val headerDp = with(density) { headerPx.toDp() }
    val tabsDp = with(density) { tabRowPx.toDp() }
    val bottom = contentPadding.calculateBottomPadding()
    val listPad = PaddingValues(bottom = bottom)
    val lift = headerDp + tabsDp
    val homeList = state.homeList
    val videosList = state.list
    val liveList = state.liveList
    val shortsList = state.shortsList
    val showsList = state.showsList
    val podcastsList = state.podcastsList
    val playlistsList = state.playlistsList
    val postsList = state.postsList
    val aboutList = state.aboutList
    fun listAt(index: Int) = when (tabs.getOrNull(index)) {
        ChannelTab.Home -> homeList
        ChannelTab.Videos -> videosList
        ChannelTab.Live -> liveList
        ChannelTab.Shorts -> shortsList
        ChannelTab.Shows -> showsList
        ChannelTab.Podcasts -> podcastsList
        ChannelTab.Playlists -> playlistsList
        ChannelTab.Posts -> postsList
        ChannelTab.About, null -> aboutList
    }
    // Captured before the header is measured. Measuring it can shove the list; the frame below
    // puts it back, including a place restored from the last visit.
    val landing = remember {
        val open = listAt(pager.settledPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
        open.firstVisibleItemIndex to open.firstVisibleItemScrollOffset
    }
    // The header moves exactly as far as the open page has scrolled. One scroll, both ways.
    val liveOffset by remember {
        derivedStateOf {
            val px = headerPx
            if (px <= 0f) return@derivedStateOf 0f
            val list = listAt(pager.currentPage)
            val scrolled = if (list.firstVisibleItemIndex > 0) px else list.firstVisibleItemScrollOffset.toFloat()
            -scrolled.coerceIn(0f, px)
        }
    }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var pinnedPage by remember { mutableIntStateOf(-1) }
    var openedAtTop by remember { mutableStateOf(false) }
    val swiping = pager.isScrollInProgress
    if (!swiping && openedAtTop && pinnedPage != pager.settledPage) pinnedPage = pager.settledPage
    if (!swiping && openedAtTop && pager.settledPage == pinnedPage) offsetPx = liveOffset
    // A sideways move stays level, including when the open tab is scrolled down past the header.
    var capturedFrom by remember { mutableIntStateOf(-1) }
    var anchorIndex by remember { mutableIntStateOf(0) }
    var anchorOffset by remember { mutableIntStateOf(0) }
    SideEffect {
        if (!swiping || !openedAtTop) {
            capturedFrom = -1
            return@SideEffect
        }
        val fromPage = pager.settledPage.coerceIn(0, tabs.lastIndex)
        if (capturedFrom != fromPage) {
            capturedFrom = fromPage
            val from = listAt(fromPage)
            anchorIndex = from.firstVisibleItemIndex
            anchorOffset = from.firstVisibleItemScrollOffset
        }
        tabs.forEachIndexed { index, _ ->
            if (index == fromPage) return@forEachIndexed
            val list = listAt(index)
            if (list.firstVisibleItemIndex != anchorIndex || list.firstVisibleItemScrollOffset != anchorOffset) {
                list.requestScrollToItem(anchorIndex, anchorOffset)
            }
        }
    }
    // The tab you stop on glides back to the top. A second swipe cancels the one in between.
    LaunchedEffect(tabs) {
        var seen = pager.settledPage
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }
            .collectLatest { (moving, page) ->
                if (moving || page == seen) return@collectLatest
                seen = page
                val list = listAt(page)
                if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) return@collectLatest
                list.animateScrollToItem(0)
            }
    }
    // Only the open list. scrollToItem on a tab that is not composed never returns, and the
    // page stayed blank. The sticky tab spacer can shove this list as the header is measured,
    // so put back the place it had, which is the top on a fresh open.
    LaunchedEffect(headerPx > 0f) {
        if (headerPx <= 0f || openedAtTop) return@LaunchedEffect
        withFrameNanos { }
        val (index, offset) = landing
        listAt(pager.settledPage).requestScrollToItem(index, offset)
        val scrolled = if (index > 0) headerPx else offset.toFloat()
        offsetPx = -scrolled.coerceIn(0f, headerPx)
        openedAtTop = true
    }
    val shelfOpen = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    val groups = channelFilterGroups(state.videos.filters, state.videos.chips)
    @Composable
    fun playlistPage(list: LazyListState, shelf: PlaylistShelf, empty: Int, pageIndex: Int) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = listPad) {
            channelScrollHead(headerPx, headerDp, tabsDp)
            pageGap()
            channelPlaylistItems(shelf.items, columns, state.gridView, palette, onOpenPlaylist)
            feedTail(
                shelf.loading || shelf.hasMore || !shelf.tried,
                shelf.error.takeIf { shelf.items.isEmpty() },
                { shelf.load(more = false) },
                empty = if (shelf.empty) empty else null,
                alone = shelf.items.isEmpty(),
                phrase = palette.onBackgroundVariant,
                lift = lift,
                bone = if (state.gridView) ChannelBone.PlaylistCard else ChannelBone.PlaylistRow,
            )
        }
        if (pageIndex == pager.settledPage) ListLoadMore(list, shelf.items.isNotEmpty()) { state.loadMore() }
    }
    Box(Modifier.fillMaxSize().padding(top = barHeight).clipToBounds()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
        ) { index ->
            val tab = tabs.getOrNull(index) ?: return@HorizontalPager
            // Only the settled tab is loaded by ChannelPage. Prefetched pager pages stay cheap
            // until the user actually opens them.
            when (tab) {
                ChannelTab.Home -> LazyColumn(Modifier.fillMaxSize(), state = homeList, contentPadding = listPad) {
                    channelScrollHead(headerPx, headerDp, tabsDp)
                    homeTab(state, palette, shelfOpen, onOpen, onOpenShorts, onOpenPlaylist) { link ->
                        when (link) {
                            is ShelfLink.Tab -> pick(link.tab)
                            is ShelfLink.Playlist -> onOpenPlaylist(link.ref)
                        }
                    }
                }
                ChannelTab.Videos -> {
                    val feed = state.videos
                    val searching = state.searchOpen && state.searchQuery.isNotBlank()
                    val shown = if (searching) state.searchVideos else feed.videos
                    LazyColumn(Modifier.fillMaxSize(), state = videosList, contentPadding = listPad) {
                        channelScrollHead(headerPx, headerDp, tabsDp)
                        item(key = "channel-filters") {
                            ChannelFilterBar(
                                groups = groups,
                                chosen = feed.chip,
                                searching = state.searchOpen,
                                query = state.searchQuery,
                                onQuery = state::updateSearch,
                                onToggleSearch = { if (state.searchOpen) state.closeSearch() else state.openSearch() },
                                labelOf = { sortLabel(it) },
                                onPick = feed::selectChip,
                            )
                        }
                        channelVideoItems(shown, columns, state.gridView, palette, onOpen)
                        if (searching) feedTail(
                            state.searchLoading || state.searchHasMore,
                            state.searchError.takeIf { shown.isEmpty() },
                            { state.updateSearch(state.searchQuery) },
                            empty = if (!state.searchLoading && state.searchError == null && shown.isEmpty()) R.string.channel_search_empty else null,
                            alone = shown.isEmpty(),
                            phrase = palette.onBackgroundVariant,
                            lift = lift,
                            bone = if (state.gridView) ChannelBone.VideoCard else ChannelBone.VideoRow,
                        ) else feedTail(
                            feed.loading || feed.hasMore || feed.waiting,
                            feed.error.takeIf { feed.videos.isEmpty() },
                            feed::refresh,
                            empty = if (feed.loadedEmpty) R.string.ui_channel_no_videos else null,
                            alone = feed.videos.isEmpty(),
                            phrase = palette.onBackgroundVariant,
                            lift = lift,
                            bone = if (state.gridView) ChannelBone.VideoCard else ChannelBone.VideoRow,
                        )
                    }
                    if (index == pager.settledPage) ListLoadMore(videosList, shown.isNotEmpty()) { state.loadMore() }
                }
                ChannelTab.Live -> {
                    val feed = state.live
                    val liveGroups = channelFilterGroups(feed.filters, feed.chips)
                    LazyColumn(Modifier.fillMaxSize(), state = liveList, contentPadding = listPad) {
                        channelScrollHead(headerPx, headerDp, tabsDp)
                        if (liveGroups.isNotEmpty()) {
                            item(key = "channel-filters") {
                                ChannelFilterBar(
                                    groups = liveGroups,
                                    chosen = feed.chip,
                                    searching = false,
                                    query = "",
                                    onQuery = {},
                                    onToggleSearch = {},
                                    showSearch = false,
                                    labelOf = { sortLabel(it) },
                                    onPick = feed::selectChip,
                                )
                            }
                        } else pageGap()
                        channelVideoItems(feed.videos, columns, state.gridView, palette, onOpen)
                        feedTail(
                            feed.loading || feed.hasMore || feed.waiting,
                            feed.error.takeIf { feed.videos.isEmpty() },
                            feed::refresh,
                            empty = if (feed.loadedEmpty) R.string.ui_channel_no_live else null,
                            alone = feed.videos.isEmpty(),
                            phrase = palette.onBackgroundVariant,
                            lift = lift,
                            bone = if (state.gridView) ChannelBone.VideoCard else ChannelBone.VideoRow,
                        )
                    }
                    if (index == pager.settledPage) ListLoadMore(liveList, feed.videos.isNotEmpty()) { state.loadMore() }
                }
                ChannelTab.Shorts -> {
                    val feed = state.shorts
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val cols = ((maxWidth - PageGutter * 2 + 12.dp) / (160.dp + 12.dp)).toInt().coerceAtLeast(1)
                        LazyColumn(Modifier.fillMaxSize(), state = shortsList, contentPadding = listPad) {
                            channelScrollHead(headerPx, headerDp, tabsDp)
                            pageGap()
                            val rowCount = (feed.videos.size + cols - 1) / cols
                            items(rowCount, key = { row -> feed.videos[row * cols].id }) { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    repeat(cols) { column ->
                                        val at = row * cols + column
                                        val short = feed.videos.getOrNull(at)
                                        if (short != null) {
                                            ShortTile(short, Modifier.weight(1f), palette) {
                                                onOpenShorts(state.shortsFrom(at))
                                            }
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            feedTail(
                                feed.loading || feed.hasMore || feed.waiting,
                                feed.error.takeIf { feed.videos.isEmpty() },
                                feed::refresh,
                                empty = if (feed.loadedEmpty) R.string.ui_channel_no_shorts else null,
                                alone = feed.videos.isEmpty(),
                                phrase = palette.onBackgroundVariant,
                                lift = lift,
                                bone = ChannelBone.Shorts,
                            )
                        }
                    }
                    if (index == pager.settledPage) ListLoadMore(shortsList, feed.videos.isNotEmpty()) { state.loadMore() }
                }
                ChannelTab.Shows -> playlistPage(showsList, state.shows, R.string.ui_channel_no_videos, index)
                ChannelTab.Podcasts -> playlistPage(podcastsList, state.podcasts, R.string.ui_channel_no_playlists, index)
                ChannelTab.Playlists -> playlistPage(playlistsList, state.playlists, R.string.ui_channel_no_playlists, index)
                ChannelTab.Posts -> {
                    LazyColumn(Modifier.fillMaxSize(), state = postsList, contentPadding = listPad) {
                        channelScrollHead(headerPx, headerDp, tabsDp)
                        pageGap()
                        items(state.posts, key = { it.id }) { post -> ChannelPostCard(post, palette) }
                        feedTail(
                            state.postsLoading || state.postsHaveMore || !state.postsStarted,
                            state.postsError.takeIf { state.posts.isEmpty() },
                            { state.loadPosts(more = false) },
                            empty = if (state.postsStarted && !state.postsLoading && state.postsError == null && state.posts.isEmpty()) R.string.channel_posts_empty else null,
                            alone = state.posts.isEmpty(),
                            phrase = palette.onBackgroundVariant,
                            lift = lift,
                            bone = ChannelBone.Post,
                        )
                    }
                    if (index == pager.settledPage) ListLoadMore(postsList, state.posts.isNotEmpty()) { state.loadMore() }
                }
                ChannelTab.About -> LazyColumn(Modifier.fillMaxSize(), state = aboutList, contentPadding = listPad) {
                    channelScrollHead(headerPx, headerDp, tabsDp)
                    pageGap()
                    item {
                        ChannelAboutPage(
                            description = state.home?.about?.description?.takeIf { it.isNotBlank() } ?: d.description,
                            handle = state.home?.handle,
                            subscribers = state.home?.about?.subscribers ?: if (d.subscribers > 0) subscribersLine(d.subscribers) else null,
                            about = state.home?.about,
                            palette = palette,
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().offset { IntOffset(0, offsetPx.roundToInt()) }) {
            Box(Modifier.fillMaxWidth().background(palette.wash).onSizeChanged { headerPx = it.height.toFloat() }) {
                ChannelName(state, d, palette, onMessage)
            }
            if (tabs.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().background(palette.wash).onSizeChanged { tabRowPx = it.height.toFloat() }) {
                    ChannelTabRow(pager.currentPage, tabs.map { tabLabel(it) }, palette) { index ->
                        scope.launch { pager.animateScrollToPage(index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListLoadMore(list: LazyListState, ready: Boolean, onMore: () -> Unit) {
    val nearEnd by remember(list) {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
    }
    LaunchedEffect(nearEnd, ready) { if (nearEnd && ready) onMore() }
}

/** One line, in the middle of the page. Empty tabs use this instead of an icon and a title. */
@Composable
private fun LazyItemScope.CenteredPhrase(text: String, color: Color, lift: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillParentMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier.padding(horizontal = 32.dp).offset(y = -lift / 2),
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Subscribe" / "Subscribed", in the artist page's glass pill. Words only. */
@Composable
private fun SubscribeWord(subscribed: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(ArtistSideButton)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LightFillAlpha), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = CircleEdgeAlpha), CircleShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (subscribed) R.string.app_subscribed else R.string.app_subscribe),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Small circular icon button — the artist page's side circles.
 * A light veil, the glass rim, and a hairline edge. From BitChord's CircleIconButton.
 */
@Composable
private fun ArtistCircle(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ArtistSideButton)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LightFillAlpha), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = CircleEdgeAlpha), CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(ArtistSideButton * 0.44f),
        )
    }
}

/** The tabs row's spacing: a little air above, the bar's room below. */
private val TabsRowModifier = Modifier.padding(top = 10.dp, bottom = 6.dp)

@Composable
private fun tabLabel(t: ChannelTab) = when (t) {
    ChannelTab.Home -> stringResource(R.string.app_tab_home)
    ChannelTab.Videos -> stringResource(R.string.ui_videos)
    ChannelTab.Shorts -> stringResource(R.string.app_tab_shorts)
    ChannelTab.Live -> stringResource(R.string.channel_tab_live)
    ChannelTab.Shows -> stringResource(R.string.channel_tab_shows)
    ChannelTab.Podcasts -> stringResource(R.string.channel_tab_podcasts)
    ChannelTab.Playlists -> stringResource(R.string.ui_playlists)
    ChannelTab.Posts -> stringResource(R.string.channel_tab_posts)
    ChannelTab.About -> stringResource(R.string.channel_tab_about)
}

/**
 * Home, as Flow's shelf page on a phone: an 8dp gap, the featured video as the home page's card,
 * then each shelf as a vertical list of the same rows the other tabs use. Four rows show, with
 * an arrow for the rest. A Shorts shelf stays a horizontal strip.
 */
private fun LazyListScope.homeTab(
    state: ChannelState,
    palette: ArtworkPalette,
    open: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onLink: (ShelfLink) -> Unit,
) {
    item { Spacer(Modifier.height(PageGutter)) }
    state.home?.featured?.let { video ->
        item(key = "featured") { ChannelFeedVideo(video, palette) { onOpen(video) } }
    }
    state.shelves.forEachIndexed { i, shelf ->
        if (shelf.shorts.isNotEmpty()) {
            item(key = "shelf-$i") { ShortsShelf(shelf, palette, onOpenShorts, onLink) }
            return@forEachIndexed
        }
        val rows = when {
            shelf.videos.isNotEmpty() -> shelf.videos.size
            shelf.playlists.isNotEmpty() -> shelf.playlists.size
            else -> shelf.channels.size
        }
        if (rows == 0) return@forEachIndexed
        val preview = 4
        val expanded = open[i] == true
        val count = if (expanded) rows else rows.coerceAtMost(preview)
        item(key = "shelf-$i-title") { ShelfHeader(shelfTitle(shelf.title), shelf.link != null, palette) { shelf.link?.let(onLink) } }
        when {
            shelf.videos.isNotEmpty() -> items(count, key = { n -> "shelf-$i-v-${shelf.videos[n].id}" }) { n ->
                ChannelVideoRow(shelf.videos[n], palette) { onOpen(shelf.videos[n]) }
            }
            shelf.playlists.isNotEmpty() -> items(count, key = { n -> "shelf-$i-p-${shelf.playlists[n].id}" }) { n ->
                PlaylistRow(shelf.playlists[n], palette) { onOpenPlaylist(shelf.playlists[n]) }
            }
            else -> items(count, key = { n -> "shelf-$i-c-${shelf.channels[n].id}" }) { n ->
                ShelfChannelRow(shelf.channels[n], palette)
            }
        }
        if (rows > preview) {
            item(key = "shelf-$i-more") {
                Box(Modifier.fillMaxWidth().pressable { open[i] = !expanded }, contentAlignment = Alignment.Center) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.onBackgroundVariant,
                        modifier = Modifier.padding(vertical = 4.dp).size(24.dp),
                    )
                }
            }
        }
        item(key = "shelf-$i-gap") { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * The list's end for a tab: a spinner while there's more, the error with Try again when nothing
 * loaded, or the tab's own empty note ([empty]). No keys (rule 8: a keyed trailing loader pins the list).
 * While the tab has nothing yet ([alone]) it takes the page's height, so switching to an empty
 * tab does not pull the list back up to the profile.
 */
private fun LazyListScope.feedTail(
    more: Boolean,
    error: String?,
    retry: () -> Unit,
    empty: Int?,
    alone: Boolean,
    phrase: Color,
    lift: androidx.compose.ui.unit.Dp,
    bone: ChannelBone = ChannelBone.VideoRow,
) {
    when {
        error != null -> item {
            Box(if (alone) Modifier.fillParentMaxHeight() else Modifier) { EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.ui_feed_error_title),
                body = error,
                action = { PillButton(stringResource(R.string.ui_try_again), retry) },
            ) }
        }
        empty != null -> item { CenteredPhrase(stringResource(empty), phrase, lift) }
        more -> item { ChannelTabSkeleton(bone, page = alone) }
    }
}

/**
 * The Videos tab's filters (Latest, Popular, Oldest, and whatever else YouTube sends):
 * chips in a row that scrolls. The chosen one's fill is the artist-line secondary.
 */
@Composable
private fun SortChips(chips: List<FeedChip>, chosen: FeedChip?, palette: ArtworkPalette, onPick: (FeedChip) -> Unit) {
    val onInk = if (palette.onBackgroundVariant.luminance() > 0.5f) Color.Black else Color.White
    val reduced = rememberReducedMotion()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 11.dp, end = 11.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            val on = chosen?.title == chip.title || (chosen == null && chip.token == null)
            val bg by animateColorAsState(if (on) palette.onBackgroundVariant else palette.elevated, tween(if (reduced) 0 else 220), label = "sortBg")
            val fg by animateColorAsState(if (on) onInk else palette.onBackgroundVariant, tween(if (reduced) 0 else 220), label = "sortFg")
            val corner by animateDpAsState(if (on) 18.dp else 10.dp, if (reduced) tween(0) else spring(dampingRatio = 0.55f, stiffness = 500f), label = "sortCorner")
            Box(
                Modifier
                    .pressable(onClick = { if (!on) onPick(chip) })
                    .height(36.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(bg)
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(sortLabel(chip.title), color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** YouTube's English sort names in the app's language (others as sent). */
@Composable
private fun sortLabel(english: String) = when (english.lowercase()) {
    "latest" -> stringResource(R.string.ui_channel_sort_latest)
    "popular" -> stringResource(R.string.ui_channel_sort_popular)
    "oldest" -> stringResource(R.string.ui_channel_sort_oldest)
    else -> english
}

/** YouTube's own shelf names in the app's language; a creator's own titles stay as written. */
@Composable
private fun shelfTitle(english: String) = when (english.lowercase()) {
    "videos" -> stringResource(R.string.ui_videos)
    "shorts" -> stringResource(R.string.app_tab_shorts)
    "popular videos" -> stringResource(R.string.ui_channel_popular_videos)
    "featured channels" -> stringResource(R.string.ui_channel_featured_channels)
    "playlists", "created playlists" -> stringResource(R.string.ui_playlists)
    else -> english
}

/** Flow's shelf title: bold, and the whole row opens the shelf when it has somewhere to go. */
@Composable
private fun ShelfHeader(title: String, hasMore: Boolean, palette: ArtworkPalette, onClick: () -> Unit) {
    if (title.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (hasMore) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasMore) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.onBackgroundVariant)
        }
    }
}

/** Flow's Shorts shelf: the heading, then a horizontal strip of reel cards at their own width. */
@Composable
private fun ShortsShelf(
    shelf: ChannelShelf,
    palette: ArtworkPalette,
    onOpenShorts: (ShortsList) -> Unit,
    onLink: (ShelfLink) -> Unit,
) {
    val more = shelf.link
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (more != null) Modifier.pressable { onLink(more) } else Modifier)
                .padding(horizontal = PageGutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shelfTitle(shelf.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (more != null) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.onBackgroundVariant, modifier = Modifier.size(24.dp))
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(shelf.shorts, key = { _, s -> s.id }) { i, s ->
                ShortTile(s, Modifier.width(160.dp), palette) { onOpenShorts(ShortsList(shelf.shorts, i)) }
            }
        }
    }
}

/** A short, as Flow's reel card: the 9:16 poster, then the title and the view count under it. */
@Composable
private fun ShortTile(short: VideoItem, modifier: Modifier, palette: ArtworkPalette, onClick: () -> Unit) {
    Column(modifier.pressable(pressedScale = 0.97f, onClick = onClick)) {
        Thumbnail(short.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), radius = 16, videoId = short.id, portrait = true)
        Spacer(Modifier.height(8.dp))
        Text(
            short.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (short.views >= 0) Text(
            viewsLine(short.views),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How many videos a playlist holds, from YouTube's English "12 videos" (just the number, in Latin digits). */
private fun playlistCount(p: PlaylistRef): String? = p.subtitle?.let { Regex("\\d[\\d,]*").find(it)?.value?.replace(",", "") }

/** The playlist mark and its count, over the thumbnail's corner. */
@Composable
private fun PlaylistBadge(p: PlaylistRef, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        playlistCount(p)?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * List is the row. Grid is the home page's card: inset thumbnail, avatar, title, meta.
 * One column on a phone, more on a wide window, same as the app home.
 */
private fun LazyListScope.channelVideoItems(
    videos: List<VideoItem>,
    columns: Int,
    grid: Boolean,
    palette: ArtworkPalette,
    onOpen: (VideoItem) -> Unit,
) {
    if (!grid) {
        items(videos, key = { it.id }) { video -> ChannelVideoRow(video, palette) { onOpen(video) } }
        return
    }
    if (columns <= 1) {
        items(videos, key = { it.id }) { video -> ChannelFeedVideo(video, palette) { onOpen(video) } }
        return
    }
    val rowCount = (videos.size + columns - 1) / columns
    items(rowCount, key = { row -> videos[row * columns].id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
            repeat(columns) { column ->
                val video = videos.getOrNull(row * columns + column)
                if (video != null) {
                    Box(Modifier.weight(1f)) { ChannelFeedVideo(video, palette) { onOpen(video) } }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** List is the row. Grid is the same inset card as a video, with the count on the thumbnail. */
private fun LazyListScope.channelPlaylistItems(
    playlists: List<PlaylistRef>,
    columns: Int,
    grid: Boolean,
    palette: ArtworkPalette,
    onOpen: (PlaylistRef) -> Unit,
) {
    if (!grid) {
        items(playlists, key = { it.id }) { p -> PlaylistRow(p, palette) { onOpen(p) } }
        return
    }
    if (columns <= 1) {
        items(playlists, key = { it.id }) { p -> ChannelPlaylistCard(p, palette) { onOpen(p) } }
        return
    }
    val rowCount = (playlists.size + columns - 1) / columns
    items(rowCount, key = { row -> playlists[row * columns].id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
            repeat(columns) { column ->
                val playlist = playlists.getOrNull(row * columns + column)
                if (playlist != null) {
                    Box(Modifier.weight(1f)) { ChannelPlaylistCard(playlist, palette) { onOpen(playlist) } }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A playlist in the grid: the home card's inset thumbnail and type, with the count badge. */
@Composable
private fun ChannelPlaylistCard(p: PlaylistRef, palette: ArtworkPalette, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).pressable(pressedScale = 0.97f, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = PageGutter).clip(RoundedCornerShape(FeedVideoThumbRadius.dp))) {
            Thumbnail(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = FeedVideoThumbRadius)
            PlaylistBadge(p, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        Column(Modifier.padding(horizontal = PageGutter, vertical = 12.dp)) {
            Text(
                p.title,
                color = palette.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            p.subtitle?.let {
                Spacer(Modifier.height(1.dp))
                Text(it, color = palette.onBackgroundVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The Playlists tab's row: thumbnail with the count, the playlist's name beside it. */
@Composable
private fun PlaylistRow(p: PlaylistRef, palette: ArtworkPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.97f, onClick = onClick).padding(start = PageGutter, end = PageGutter, bottom = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(142.dp)) {
            Thumbnail(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
            PlaylistBadge(p, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                p.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            p.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A featured channel, as a row: picture and name. A tap opens the channel. */
@Composable
private fun ShelfChannelRow(ch: ChannelRef, palette: ArtworkPalette) {
    val open = LocalChannelOpener.current
    Row(
        Modifier.fillMaxWidth().pressable { open("https://www.youtube.com/channel/${ch.id}") }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(ch.avatar, ch.name, size = 40)
        Spacer(Modifier.width(12.dp))
        Text(ch.name, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}