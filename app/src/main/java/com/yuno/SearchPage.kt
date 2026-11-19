package com.yuno

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuno.floatingtabbar.FloatingTabBarScrollConnection
import com.yuno.backdrop.backdrops.SnapshotBackdrop
import com.yuno.icons.SfIcons
import dev.chrisbanes.haze.HazeState

/** Newest history rows drawn before "Show all". From Flow's `SEARCH_HISTORY_LIMIT`. */
private const val HistoryShown = 8

/**
 * Search, as Flow's screen: history and suggestions together while typing, then the type chips
 * over the results. The field stays in the shared top bar.
 */
@Composable
fun SearchScreen(
    state: SearchState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    chipScroll: FloatingTabBarScrollConnection,
    scrollToTop: Int = 0,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect {
        state.onLink = { target ->
            when (target) {
                is YoutubeTarget.Video -> onOpen(stubVideo(target.id))
                is YoutubeTarget.Short -> onOpenShorts(ShortsList(listOf(stubVideo(target.id)), 0))
                is YoutubeTarget.Channel -> onOpenChannel(target.url)
                is YoutubeTarget.Playlist -> onOpenPlaylist(PlaylistRef(target.id, target.id, null, null))
            }
        }
    }
    LaunchedEffect(state.showingResults) {
        if (state.showingResults) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    // The bar and the chips float. The list fills the screen and scrolls behind them,
    // so the page colour is not a slab under the sticky chrome.
    val underBar = topBarHeight() + 8.dp
    val underChips = topBarHeight() + 6.dp + 40.dp + 12.dp
    val watched = remember(WatchHistory.videos) { WatchHistory.videos.mapTo(HashSet()) { it.id } }
    val shown = remember(state.hits, state.filter.watchState, watched) {
        state.hits.watchFiltered(state.filter.watchState, watched)
    }
    // Same spring as the chip fold, so the first result moves up with the row.
    val topInset by animateDpAsState(
        if (state.showingResults && !chipScroll.isInline) underChips else underBar,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "searchChipInset",
    )
    val inset = PaddingValues(top = topInset, bottom = contentPadding.calculateBottomPadding())
    val pending = shown.isEmpty() && state.loading
    Box(
        Modifier
            .fillMaxSize()
            .overlayTouchBlock()
            .then(if (state.showingResults) Modifier.nestedScroll(chipScroll) else Modifier),
    ) {
        when {
            !state.showingResults -> SearchSuggestions(
                query = state.query,
                history = SearchHistory.matching(state.query),
                suggestions = state.suggestions,
                contentPadding = inset,
                onSubmit = { text ->
                    state.updateQuery(text)
                    dismissAndSearch(state, focus, keyboard)
                },
                onFill = state::updateQuery,
            )
            // Loading wins over the error. A cancelled page used to set the error while the next page was still coming.
            !pending && state.error != null && shown.isEmpty() -> EmptyState(
                icon = SfIcons.CloudOff,
                title = stringResource(R.string.ui_search_error_title),
                body = state.error.orEmpty(),
                action = { PillButton(stringResource(R.string.ui_try_again), state::submit) },
            )
            state.submitted.isEmpty() -> EmptyLine(stringResource(R.string.ui_search_intro_body))
            shown.isEmpty() && !pending -> EmptyLine(stringResource(R.string.ui_search_no_match, state.submitted))
            state.filter.contentType == ContentType.SHORTS -> SearchShorts(
                videos = shown.filterIsInstance<SearchHit.Video>().map { it.item },
                loading = pending,
                contentPadding = inset,
                list = state.shortsGrid,
                onOpen = onOpenShorts,
                onMore = state::loadMore,
                hasMore = state.hasMore,
            )
            else -> SearchResults(
                hits = shown,
                loading = pending,
                contentPadding = inset,
                scrollToTop = scrollToTop,
                generation = state.generation,
                savedList = state.resultsList,
                onOpen = onOpen,
                onOpenChannel = onOpenChannel,
                onOpenPlaylist = onOpenPlaylist,
                onOpenShorts = onOpenShorts,
                onMore = state::loadMore,
                hasMore = state.hasMore,
            )
        }
    }
}

/** Flow's `SearchSuggestionsPanel`: history first, then what YouTube suggests. */
@Composable
private fun SearchSuggestions(
    query: String,
    history: List<String>,
    suggestions: List<String>,
    contentPadding: PaddingValues,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll || query.isNotBlank()) history else history.take(HistoryShown)
    if (query.isBlank() && history.isEmpty() && suggestions.isEmpty()) {
        EmptyLine(stringResource(R.string.ui_search_intro_body))
        return
    }
    LazyColumn(contentPadding = contentPadding) {
        if (history.isNotEmpty()) {
            item(key = "history:header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = PageGutter, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ui_recent_searches),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.ui_clear_all)) }
                }
            }
            items(shown, key = { "history:$it" }) { item ->
                SuggestionRow(
                    text = item,
                    query = query,
                    icon = SfIcons.History,
                    trailing = SfIcons.Close,
                    trailingDescription = stringResource(R.string.ui_remove_search),
                    onClick = { onSubmit(item) },
                    onTrailing = { SearchHistory.remove(item) },
                )
            }
            if (!showAll && query.isBlank() && history.size > shown.size) {
                item(key = "history:more") {
                    TextButton(onClick = { showAll = true }, modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.ui_search_show_all))
                    }
                }
            }
        }
        items(suggestions, key = { "suggestion:$it" }) { suggestion ->
            SuggestionRow(
                text = suggestion,
                query = query,
                icon = SfIcons.Search,
                trailing = Icons.Rounded.NorthWest,
                trailingDescription = stringResource(R.string.ui_search_fill),
                onClick = { onSubmit(suggestion) },
                onTrailing = { onFill(suggestion) },
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.ui_search_clear_title)) },
            text = { Text(stringResource(R.string.ui_search_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    SearchHistory.clear()
                }) { Text(stringResource(R.string.ui_search_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }
}

/** Flow's `FlowSuggestionRow`: the part not yet typed is bold, and the corner button fills or removes. */
@Composable
private fun SuggestionRow(
    text: String,
    query: String,
    icon: ImageVector,
    trailing: ImageVector,
    trailingDescription: String,
    onClick: () -> Unit,
    onTrailing: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).pressable(onClick = onClick).padding(start = PageGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                emphasiseCompletion(text, query),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(44.dp).pressable(onClick = onTrailing), contentAlignment = Alignment.Center) {
            Icon(trailing, contentDescription = trailingDescription, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** The type chips above the results, in the order YouTube shows them. From Flow's `SearchFilterBar`. */
@Composable
internal fun SearchFilterBar(
    filter: SearchFilter,
    shortsEnabled: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val types = remember(shortsEnabled) { ChipOrder.filterNot { it == ContentType.SHORTS && !shortsEnabled } }
    val videoFilters = filter.contentType.supportsVideoFilters()
    val compact = LocalSearchChipsCompact.current
    val scroll = rememberScrollState()
    Row(
        (if (compact) modifier else modifier.fillMaxWidth().horizontalScroll(scroll))
            .padding(horizontal = if (compact) 0.dp else PageGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        types.forEach { type ->
            val selected = filter.contentType == type
            // The folded bar keeps this one chip. A second selected filter leaves with the rest.
            FilterChipSlot(keep = selected, compact = compact) {
                SearchChromeChip(stringResource(type.label()), selected, onExpand, matchButtons = selected) {
                    onFilterChange(filter.copy(contentType = type))
                }
            }
        }
        if (videoFilters) {
            val recent = filter.uploadDate == UploadDate.THIS_WEEK
            FilterChipSlot(keep = false, compact = compact) {
                SearchChromeChip(stringResource(R.string.ui_search_recent), recent, onExpand) {
                    onFilterChange(filter.copy(uploadDate = if (recent) UploadDate.ANY else UploadDate.THIS_WEEK))
                }
            }
            val unwatched = filter.watchState == WatchState.UNWATCHED
            FilterChipSlot(keep = false, compact = compact) {
                SearchChromeChip(stringResource(R.string.ui_search_unwatched), unwatched, onExpand) {
                    onFilterChange(filter.copy(watchState = if (unwatched) WatchState.ANY else WatchState.UNWATCHED))
                }
            }
            val watched = filter.watchState == WatchState.WATCHED
            FilterChipSlot(keep = false, compact = compact) {
                SearchChromeChip(stringResource(R.string.ui_search_watched), watched, onExpand) {
                    onFilterChange(filter.copy(watchState = if (watched) WatchState.ANY else WatchState.WATCHED))
                }
            }
        }
    }
}

/**
 * The parent fold already moves and clips the whole chip row. Individual chips
 * only fade, avoiding a second set of width animations and per-chip blur layers.
 */
@Composable
private fun FilterChipSlot(
    keep: Boolean,
    compact: Boolean,
    content: @Composable () -> Unit,
) {
    if (keep) {
        content()
        return
    }
    val reduced = rememberReducedMotion()
    val motion = if (reduced) snap() else tween<Float>(110, easing = FastOutSlowInEasing)
    AnimatedVisibility(
        visible = !compact,
        enter = fadeIn(motion),
        exit = fadeOut(motion),
    ) {
        content()
    }
}

/**
 * The search field's material: liquid glass while that is on, the same frost when it is off.
 * Drawn from the top chrome. Glass inside the page recording has nothing behind it to sample.
 */
@Composable
private fun SearchChromeChip(
    label: String,
    selected: Boolean,
    onExpand: () -> Unit,
    matchButtons: Boolean = false,
    onClick: () -> Unit,
) {
    val glass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val ink = if (glass) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val compact = LocalSearchChipsCompact.current
    // The chip that stays in the bar grows to the back button and the field. The row under the bar stays 40dp.
    val height by animateDpAsState(
        if (compact && matchButtons) TopControl else 40.dp,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "searchChipHeight",
    )
    Row(
        Modifier
            .height(height)
            .floatingChrome(CircleShape)
            .pressable(onClick = { if (compact) onExpand() else onClick() })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, color = ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchResults(
    hits: List<SearchHit>,
    loading: Boolean,
    contentPadding: PaddingValues,
    scrollToTop: Int,
    generation: Int,
    savedList: LazyListState,
    onOpen: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onMore: () -> Unit,
    hasMore: Boolean,
) {
    val list = rememberScrollToTop(scrollToTop, savedList)
    // A new search starts at the top. Coming back to this one does not: the first value is the search already open.
    val openedAt = remember { generation }
    LaunchedEffect(generation) { if (generation != openedAt) list.scrollToItem(0) }
    LoadMore(list, hits.isNotEmpty() && hasMore, onMore)
    LazyColumn(state = list, contentPadding = contentPadding) {
        if (loading) items(4) { FeedSkeletons(1) }
        items(hits.size, key = { hits[it].identityKey() }) { index ->
            when (val hit = hits[index]) {
                is SearchHit.Video -> if (hit.short) {
                    // A short that arrived inside an ordinary search opens with the shorts player.
                    VideoCard(hit.item, onClick = { onOpenShorts(ShortsList(listOf(hit.item), 0)) })
                } else VideoCard(hit.item, onClick = { onOpen(hit.item) })
                is SearchHit.Channel -> SearchChannelRow(hit, onOpen, onOpenChannel)
                is SearchHit.Playlist -> SearchPlaylistCard(hit.ref) { onOpenPlaylist(hit.ref) }
                is SearchHit.Shelf -> SearchShelfBlock(
                    hit,
                    showRuleAbove = hits.getOrNull(index - 1) !is SearchHit.Shelf,
                    spaceUnder = hits.getOrNull(index + 1) !is SearchHit.Shelf,
                    onOpen = onOpen,
                    onOpenShorts = onOpenShorts,
                )
            }
        }
        if (hasMore && hits.isNotEmpty()) item { FeedSkeletons(1) }
    }
}

@Composable
private fun SearchShorts(
    videos: List<VideoItem>,
    loading: Boolean,
    contentPadding: PaddingValues,
    list: LazyGridState,
    onOpen: (ShortsList) -> Unit,
    onMore: () -> Unit,
    hasMore: Boolean,
) {
    val nearEnd by remember(list) {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
    }
    LaunchedEffect(nearEnd, videos.isNotEmpty(), hasMore) { if (nearEnd && videos.isNotEmpty() && hasMore) onMore() }
    if (loading) {
        Box(Modifier.padding(top = contentPadding.calculateTopPadding())) { FeedSkeletons(4) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = list,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = PageGutter),
    ) {
        gridItemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
            Column(Modifier.pressable(pressedScale = 0.97f) { onOpen(ShortsList(videos, index)) }) {
                Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), radius = 16, videoId = video.id, portrait = true)
                Spacer(Modifier.height(8.dp))
                Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Flow's channel block: the creator, then the "latest" shelf YouTube sent directly after the card.
 * The picture row is 12dp above and below. A latest strip under it keeps 16dp beneath the posters.
 */
@Composable
private fun SearchChannelRow(
    hit: SearchHit.Channel,
    onOpen: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val open = { onOpenChannel("https://www.youtube.com/channel/${hit.ref.id}") }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(
                    pressedScale = 0.97f,
                    lift = true,
                    liftPadding = PaddingValues(horizontal = PageGutter),
                    onClick = open,
                )
                .padding(horizontal = PageGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelAvatar(hit.ref.avatar, hit.ref.name, size = 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FadingTitle(
                    text = hit.ref.name,
                    style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
                    maxLines = 1,
                )
                hit.meta?.let {
                    Spacer(Modifier.height(1.dp))
                    Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (hit.latestVideos.isNotEmpty()) {
            hit.latestTitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp).pressable(onClick = open),
                )
            }
            LazyRow(
                contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = PageGutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hit.latestVideos, key = { it.id }) { video ->
                    val anchor = rememberVideoAnchor(FeedVideoThumbRadius.dp)
                    Column(Modifier.width(168.dp).pressable(pressedScale = 0.97f) { anchor.play { onOpen(video) } }) {
                        Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f).videoAnchor(anchor), radius = FeedVideoThumbRadius, videoId = video.id)
                        Spacer(Modifier.height(6.dp))
                        FadingTitle(
                            text = video.title,
                            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Flow's `SearchShelf`. One rule above and one below. The rule above is left off when the row
 * before this is also a shelf, so two strips share one line. The line keeps a gutter before the
 * next video, channel, or playlist, so it does not sit on that row.
 */
@Composable
private fun SearchShelfBlock(
    shelf: SearchHit.Shelf,
    showRuleAbove: Boolean,
    spaceUnder: Boolean,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (showRuleAbove) HorizontalDivider(color = c.outlineVariant)
        when (shelf.kind) {
            SearchShelfKind.SHORTS -> {
                Text(
                    shelf.title ?: stringResource(R.string.ui_shorts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = PageGutter, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = PageGutter),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(shelf.videos, key = { _, video -> video.id }) { index, video ->
                        Column(Modifier.width(160.dp).pressable(pressedScale = 0.97f) { onOpenShorts(ShortsList(shelf.videos, index)) }) {
                            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), radius = 16, videoId = video.id, portrait = true)
                            Spacer(Modifier.height(8.dp))
                            FadingTitle(
                                text = video.title,
                                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
                            )
                            if (video.views >= 0) {
                                Text(viewsLine(video.views), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            SearchShelfKind.POSTS -> {
                shelf.title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = PageGutter, bottom = 8.dp),
                    )
                }
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .height(IntrinsicSize.Max)
                        .padding(
                            start = PageGutter,
                            end = PageGutter,
                            top = if (shelf.title == null) PageGutter else 0.dp,
                            bottom = PageGutter,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shelf.posts.forEach { post -> SearchPostCard(post) }
                }
            }
            SearchShelfKind.VIDEOS -> {
                shelf.title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = PageGutter, bottom = 8.dp),
                    )
                } ?: Spacer(Modifier.height(PageGutter))
                var expanded by rememberSaveable(shelf.id) { mutableStateOf(false) }
                val collapsed = shelf.collapsedItemCount ?: shelf.videos.size
                val shown = if (expanded) shelf.videos else shelf.videos.take(collapsed)
                shown.forEach { video -> VideoCard(video, onClick = { onOpen(video) }) }
                if (shelf.videos.size > collapsed) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = PageGutter),
                    ) {
                        Text(stringResource(if (expanded) R.string.ui_search_shelf_less else R.string.ui_search_shelf_more))
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 6.dp).size(18.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = c.outlineVariant)
        // The next shelf brings its own gap under this shared line. A video, channel, or playlist does not.
        if (spaceUnder) Spacer(Modifier.height(PageGutter))
    }
}

/** A post in the search strip, in the channel page's order: author, text, picture, then likes and comments. */
@Composable
private fun SearchPostCard(post: CommunityPost) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var overflows by rememberSaveable(post.id) { mutableStateOf(false) }
    Column(
        Modifier.width(300.dp).fillMaxHeight().clip(shape).background(c.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(post.avatar, post.author, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    post.author,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (post.ago.isNotBlank()) {
                    Text(post.ago, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (post.text.isNotBlank()) {
            Text(
                post.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflows = it.hasVisualOverflow },
            )
            if (!expanded && overflows) {
                Text(
                    stringResource(R.string.channel_post_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.pressable { expanded = true },
                )
            }
        }
        post.image?.let { url ->
            AsyncImage(
                model = ThumbnailUrlResolver.resolveCommunityPostImage(url, uncropped = true),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceContainerHighest),
            )
        }
        }
        Column {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ThumbUp, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                post.likes.ifBlank { stringResource(R.string.player_like) },
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.Share,
                contentDescription = stringResource(R.string.ui_share),
                tint = c.onSurfaceVariant,
                modifier = Modifier.size(18.dp).pressable { shareLink(context, "https://www.youtube.com/post/${post.id}", post.author) },
            )
            if (post.comments.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(post.comments, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, maxLines = 1)
            }
            }
        }
    }
}

/** A playlist in the results, as the grid card: the thumbnail, then the title under it. */
@Composable
private fun SearchPlaylistCard(playlist: PlaylistRef, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .pressable(
                pressedScale = 0.97f,
                lift = true,
                liftPadding = PaddingValues(horizontal = PageGutter),
                onClick = onClick,
            ),
    ) {
        Box(Modifier.padding(horizontal = PageGutter).clip(RoundedCornerShape(FeedVideoThumbRadius.dp))) {
            Thumbnail(playlist.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = FeedVideoThumbRadius)
            SearchPlaylistBadge(playlist, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp)) {
            FadingTitle(
                text = playlist.title,
                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
            )
            playlist.subtitle?.let {
                Spacer(Modifier.height(1.dp))
                Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SearchPlaylistBadge(playlist: PlaylistRef, modifier: Modifier) {
    val count = playlist.subtitle
        ?.takeIf { it.contains("video", ignoreCase = true) }
        ?.let { Regex("\\d[\\d,]*").find(it)?.value?.replace(",", "") }
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SfIcons.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        count?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private enum class FilterPane { Root, Type, Duration, Upload, Watch, Sort, Features }

/**
 * The search filters, in BitChord's song menu. Hung under the filter button,
 * grown from its top-right corner. A group opens its own list; a tap applies it.
 */
@Composable
internal fun SearchFilterMenu(
    open: Boolean,
    anchor: Rect,
    filter: SearchFilter,
    shortsEnabled: Boolean,
    onChange: (SearchFilter) -> Unit,
    onDismiss: () -> Unit,
    haze: HazeState,
    backdrop: SnapshotBackdrop,
) {
    var pane by remember { mutableStateOf(FilterPane.Root) }
    val video = filter.contentType.supportsVideoFilters()
    LaunchedEffect(open) { if (!open) pane = FilterPane.Root }
    LaunchedEffect(video) {
        if (!video && pane != FilterPane.Root && pane != FilterPane.Type && pane != FilterPane.Sort) pane = FilterPane.Root
    }
    IosMenuPopover(
        item = filter.takeIf { open },
        anchor = anchor,
        onDismiss = onDismiss,
        backdrop = backdrop,
        hazeState = haze,
        dropBelow = true,
        alignEnd = true,
    ) { current ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (pane) {
                FilterPane.Root -> {
                    FilterGroup(stringResource(R.string.ui_search_type), SfIcons.List, stringResource(current.contentType.label())) { pane = FilterPane.Type }
                    if (video) {
                        FilterGroup(stringResource(R.string.ui_search_duration), SfIcons.Clock, stringResource(current.duration.label())) { pane = FilterPane.Duration }
                        FilterGroup(stringResource(R.string.ui_search_upload_date), SfIcons.History, stringResource(current.uploadDate.label())) { pane = FilterPane.Upload }
                        FilterGroup(stringResource(R.string.ui_search_watched), SfIcons.PlayCircle, stringResource(current.watchState.label())) { pane = FilterPane.Watch }
                    }
                    FilterGroup(stringResource(R.string.ui_search_sort), SfIcons.Reorder, stringResource(current.sortType.label())) { pane = FilterPane.Sort }
                    if (video) {
                        FilterGroup(
                            stringResource(R.string.ui_search_features),
                            SfIcons.Tune,
                            if (current.features.isEmpty()) stringResource(R.string.ui_search_any) else current.features.size.toString(),
                        ) { pane = FilterPane.Features }
                    }
                }
                FilterPane.Type -> FilterChoices(
                    stringResource(R.string.ui_search_type),
                    ChipOrder.filterNot { it == ContentType.SHORTS && !shortsEnabled },
                    current.contentType,
                    { stringResource(it.label()) },
                    onBack = { pane = FilterPane.Root },
                ) {
                    onChange(current.copy(contentType = it))
                    pane = FilterPane.Root
                }
                FilterPane.Duration -> FilterChoices(
                    stringResource(R.string.ui_search_duration),
                    Duration.entries,
                    current.duration,
                    { stringResource(it.label()) },
                    onBack = { pane = FilterPane.Root },
                ) {
                    onChange(current.copy(duration = it))
                    pane = FilterPane.Root
                }
                FilterPane.Upload -> FilterChoices(
                    stringResource(R.string.ui_search_upload_date),
                    UploadDate.entries,
                    current.uploadDate,
                    { stringResource(it.label()) },
                    onBack = { pane = FilterPane.Root },
                ) {
                    onChange(current.copy(uploadDate = it))
                    pane = FilterPane.Root
                }
                FilterPane.Watch -> FilterChoices(
                    stringResource(R.string.ui_search_watched),
                    WatchState.entries,
                    current.watchState,
                    { stringResource(it.label()) },
                    onBack = { pane = FilterPane.Root },
                ) {
                    onChange(current.copy(watchState = it))
                    pane = FilterPane.Root
                }
                FilterPane.Sort -> FilterChoices(
                    stringResource(R.string.ui_search_sort),
                    SortType.entries,
                    current.sortType,
                    { stringResource(it.label()) },
                    onBack = { pane = FilterPane.Root },
                ) {
                    onChange(current.copy(sortType = it))
                    pane = FilterPane.Root
                }
                FilterPane.Features -> {
                    IosMenuRow(stringResource(R.string.ui_search_features), SfIcons.ArrowBack, onClick = { pane = FilterPane.Root })
                    SearchFeature.entries.forEach { feature ->
                        val on = feature in current.features
                        IosMenuRow(stringResource(feature.label()), if (on) SfIcons.Check else null) {
                            onChange(current.copy(features = if (on) current.features - feature else current.features + feature))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(label: String, icon: ImageVector, value: String, onClick: () -> Unit) {
    IosMenuRow(label, icon, onClick = onClick, value = value)
}

@Composable
private fun <T> FilterChoices(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onBack: () -> Unit,
    onPick: (T) -> Unit,
) {
    IosMenuRow(title, SfIcons.ArrowBack, onClick = onBack)
    options.forEach { option ->
        IosMenuRow(label(option), if (option == selected) SfIcons.Check else null) { onPick(option) }
    }
}

@Composable
private fun LoadMore(list: LazyListState, ready: Boolean, onMore: () -> Unit) {
    val nearEnd by remember(list) {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
    }
    LaunchedEffect(nearEnd, ready) { if (nearEnd && ready) onMore() }
}

private fun emphasiseCompletion(text: String, query: String): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || !text.startsWith(trimmed, ignoreCase = true)) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, trimmed.length))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(trimmed.length)) }
    }
}

/**
 * Flow's `withWatchState`. Watched is a list of videos: channels, playlists and a shelf with
 * nothing left are dropped. Unwatched keeps those, and drops the videos already watched.
 */
private fun List<SearchHit>.watchFiltered(state: WatchState, watched: Set<String>): List<SearchHit> {
    if (state == WatchState.ANY) return this
    val keepWatched = state == WatchState.WATCHED
    fun VideoItem.kept() = (id in watched) == keepWatched
    return mapNotNull { hit ->
        when (hit) {
            is SearchHit.Video -> hit.takeIf { hit.item.kept() }
            is SearchHit.Channel, is SearchHit.Playlist -> hit.takeUnless { keepWatched }
            is SearchHit.Shelf -> when {
                hit.kind == SearchShelfKind.POSTS -> hit.takeUnless { keepWatched }
                else -> {
                    val videos = hit.videos.filter { it.kept() }
                    if (videos.isEmpty()) null else hit.copy(videos = videos)
                }
            }
        }
    }
}

private fun stubVideo(id: String) = VideoItem(id, "", "", null, null, null, 0, -1, null)

private val ChipOrder = listOf(
    ContentType.ALL,
    ContentType.SHORTS,
    ContentType.VIDEOS,
    ContentType.LIVE,
    ContentType.CHANNELS,
    ContentType.PLAYLISTS,
    ContentType.MOVIES,
)

@Composable
private fun ContentType.label(): Int = when (this) {
    ContentType.ALL -> R.string.ui_search_all
    ContentType.VIDEOS -> R.string.ui_videos
    ContentType.SHORTS -> R.string.ui_shorts
    ContentType.CHANNELS -> R.string.ui_search_channels
    ContentType.PLAYLISTS -> R.string.ui_playlists
    ContentType.MOVIES -> R.string.ui_search_movies
    ContentType.LIVE -> R.string.ui_search_live
}

@Composable
private fun Duration.label(): Int = when (this) {
    Duration.ANY -> R.string.ui_search_any
    Duration.UNDER_3_MINUTES -> R.string.ui_search_under_3
    Duration.THREE_TO_20_MINUTES -> R.string.ui_search_3_to_20
    Duration.OVER_20_MINUTES -> R.string.ui_search_over_20
}

@Composable
private fun UploadDate.label(): Int = when (this) {
    UploadDate.ANY -> R.string.ui_search_any
    UploadDate.LAST_HOUR -> R.string.ui_search_last_hour
    UploadDate.TODAY -> R.string.ui_search_today
    UploadDate.THIS_WEEK -> R.string.ui_search_this_week
    UploadDate.THIS_MONTH -> R.string.ui_search_this_month
    UploadDate.THIS_YEAR -> R.string.ui_search_this_year
}

@Composable
private fun WatchState.label(): Int = when (this) {
    WatchState.ANY -> R.string.ui_search_any
    WatchState.UNWATCHED -> R.string.ui_search_unwatched
    WatchState.WATCHED -> R.string.ui_search_watched
}

@Composable
private fun SortType.label(): Int = when (this) {
    SortType.RELEVANCE -> R.string.ui_search_relevance
    SortType.VIEW_COUNT -> R.string.ui_search_view_count
}

@Composable
private fun SearchFeature.label(): Int = when (this) {
    SearchFeature.HD -> R.string.ui_search_hd
    SearchFeature.FOUR_K -> R.string.ui_search_4k
    SearchFeature.HDR -> R.string.ui_search_hdr
    SearchFeature.SUBTITLES -> R.string.ui_search_subtitles
    SearchFeature.CREATIVE_COMMONS -> R.string.ui_search_creative_commons
    SearchFeature.THREE_SIXTY -> R.string.ui_search_360
    SearchFeature.VR180 -> R.string.ui_search_vr180
    SearchFeature.THREE_D -> R.string.ui_search_3d
    SearchFeature.LOCATION -> R.string.ui_search_location
    SearchFeature.PURCHASED -> R.string.ui_search_purchased
}

private fun dismissAndSearch(
    state: SearchState,
    focus: FocusManager,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    focus.clearFocus(force = true)
    keyboard?.hide()
    state.submit()
}
