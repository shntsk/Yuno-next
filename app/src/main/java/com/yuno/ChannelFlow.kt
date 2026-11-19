package com.yuno

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VideoLibrary
import com.yuno.icons.SfIcons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yuno.backdrop.backdrops.SnapshotBackdrop
import dev.chrisbanes.haze.HazeState

/** Latest / Popular / Oldest are one menu. Everything else (Members only, Public, …) is a chip. */
private val SortTitles = setOf("latest", "popular", "oldest")

/** Filters from the sheet parser, or the plain chips grouped the same way when the sheet was empty. */
internal fun channelFilterGroups(filters: List<ChannelFilterGroup>, chips: List<FeedChip>): List<ChannelFilterGroup> {
    if (filters.isNotEmpty()) return filters
    if (chips.isEmpty()) return emptyList()
    val sorts = chips.filter { it.title.lowercase() in SortTitles }
    val rest = chips.filter { it.title.lowercase() !in SortTitles }
    val asOption = { chip: FeedChip -> ChannelFilterOption(chip.title, chip.token, chip.token == null) }
    return buildList {
        if (sorts.size >= 2) add(ChannelFilterGroup(dropdown = true, options = sorts.map(asOption)))
        val plain = if (sorts.size >= 2) rest else chips
        if (plain.isNotEmpty()) add(ChannelFilterGroup(dropdown = false, options = plain.map(asOption)))
    }
}

/**
 * Flow's channel tab row: a 44dp scrollable row, a 2dp indicator, and a hairline under it.
 * Colours are the channel page's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelTabRow(
    selectedIndex: Int,
    tabs: List<String>,
    palette: ArtworkPalette,
    onTabSelected: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(palette.wash)) {
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 0.dp,
            containerColor = palette.wash,
            contentColor = palette.onBackground,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(selectedIndex),
                    height = 2.dp,
                    color = palette.onBackgroundVariant,
                )
            },
            divider = {},
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedIndex == index
                Tab(
                    selected = selected,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.height(44.dp),
                    selectedContentColor = palette.onBackground,
                    unselectedContentColor = palette.onBackgroundVariant,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        HorizontalDivider(color = palette.onBackgroundVariant.copy(alpha = 0.35f), thickness = 0.5.dp)
    }
}

/** The open sort menu. Drawn outside the page's blur source, the same way the hold menu is. */
internal class SortMenuHost {
    var request by mutableStateOf<SortMenuRequest?>(null)
}

internal class SortMenuRequest(
    val anchor: Rect,
    val labels: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit,
)

internal val LocalSortMenu = androidx.compose.runtime.staticCompositionLocalOf<SortMenuHost?> { null }

/**
 * The Videos filter row. It scrolls with the list. Search sits at the end. While search is open
 * the chips leave, the field takes their place, and the icon becomes an X.
 */
@Composable
internal fun ChannelFilterBar(
    groups: List<ChannelFilterGroup>,
    chosen: FeedChip?,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    showSearch: Boolean = true,
    labelOf: @Composable (String) -> String,
    onPick: (FeedChip) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = PageGutter, end = PageGutter, top = PageGutter, bottom = PageGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searching) {
            FilterField(query, onQuery, Modifier.weight(1f))
        } else {
            LazyRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                groups.forEachIndexed { index, group ->
                    if (group.dropdown) {
                        item(key = "sort-$index") { SortChip(group.options, chosen, labelOf, onPick) }
                    } else {
                        items(group.options, key = { it.label }) { option ->
                            GlassChip(labelOf(option.label), selected = chosen?.title == option.label) {
                                onPick(FeedChip(option.label, option.token))
                            }
                        }
                    }
                }
            }
        }
        if (showSearch) FilterSearch(close = searching, onClick = onToggleSearch)
    }
}

@Composable
private fun FilterField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, color = c.onSurface)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .focusRequester(focus)
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.channel_search_hint),
                        color = c.onSurface.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun FilterSearch(close: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (close) SfIcons.Close else SfIcons.Search,
            contentDescription = if (close) "Close" else "Search",
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SortChip(
    options: List<ChannelFilterOption>,
    chosen: FeedChip?,
    labelOf: @Composable (String) -> String,
    onPick: (FeedChip) -> Unit,
) {
    if (options.isEmpty()) return
    val host = LocalSortMenu.current
    val active = options.firstOrNull { it.label == chosen?.title }
        ?: options.firstOrNull { it.selected }
        ?: options.first()
    val labels = options.map { labelOf(it.label) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    GlassChip(
        labelOf(active.label),
        selected = chosen != null || active.selected,
        dropdown = true,
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
    ) {
        host?.request = SortMenuRequest(
            anchor = anchor,
            labels = labels,
            selected = options.indexOfFirst { it.label == active.label }.coerceAtLeast(0),
            onPick = { index -> options.getOrNull(index)?.let { onPick(FeedChip(it.label, it.token)) } },
        )
    }
}

/**
 * Latest / Popular / Oldest, as BitChord's song ⋮ menu. Drawn outside the page
 * recording, so the panel's frost has the page to sample.
 */
@Composable
internal fun ChannelSortMenuOverlay(haze: HazeState, backdrop: SnapshotBackdrop) {
    val host = LocalSortMenu.current ?: return
    val request = host.request
    IosMenuPopover(
        item = request,
        anchor = request?.anchor,
        onDismiss = { host.request = null },
        backdrop = backdrop,
        hazeState = haze,
        dropBelow = true,
    ) { menu ->
        menu.labels.forEachIndexed { index, label ->
            IosMenuRow(
                label = label,
                icon = if (index == menu.selected) SfIcons.Check else null,
                onClick = {
                    host.request = null
                    menu.onPick(index)
                },
            )
        }
    }
}

/** The subscribe pill, as a chip. Liquid glass draws the rim while that setting is on. */
@Composable
internal fun GlassChip(
    label: String,
    selected: Boolean,
    dropdown: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (selected) 0.16f else 0.07f), CircleShape)
            .liquidGlassRim(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected && !dropdown) Icon(Icons.Rounded.Check, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, color = ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (dropdown) Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
    }
}

/**
 * Flow's About panel: the description, then the channel's links, then one row per fact it actually has.
 */
@Composable
internal fun ChannelAboutPage(
    description: String,
    handle: String?,
    subscribers: String?,
    about: ChannelAboutInfo?,
    palette: ArtworkPalette,
) {
    val uriHandler = LocalUriHandler.current
    val rows = listOfNotNull(
        about?.canonical?.let { Icons.Outlined.Language to it.removePrefix("https://").removePrefix("http://") },
        handle?.let { Icons.Outlined.Person to it },
        subscribers?.let { Icons.Outlined.Person to it },
        about?.videos?.let { Icons.Outlined.VideoLibrary to it },
        about?.views?.let { Icons.AutoMirrored.Outlined.TrendingUp to it },
        about?.country?.let { Icons.Outlined.Public to it },
        about?.joined?.let { Icons.Outlined.CalendarMonth to it },
    )
    val links = about?.links.orEmpty()
    val line = palette.onBackgroundVariant.copy(alpha = 0.35f)
    Column(
        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = PageGutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (description.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.channel_about_heading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground)
            }
        }
        if (links.isNotEmpty()) {
            if (description.isNotBlank()) HorizontalDivider(color = line, thickness = 0.5.dp)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.channel_about_links), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                links.forEach { link ->
                    Row(
                        Modifier.fillMaxWidth().clickable { runCatching { uriHandler.openUri(link.url) } },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (link.icon.isNullOrBlank()) {
                            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(24.dp), tint = palette.onBackgroundVariant)
                        } else {
                            AsyncImage(model = link.icon, contentDescription = null, modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(link.title, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(link.display, style = MaterialTheme.typography.bodyMedium, color = palette.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            if (description.isNotBlank() || links.isNotEmpty()) HorizontalDivider(color = line, thickness = 0.5.dp)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.channel_about_more), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                rows.forEach { (icon, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = palette.onBackgroundVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground)
                    }
                }
            }
        }
    }
}

/** One community post, in Flow's order: author, text, a full-width picture, then like, share and comments. */
@Composable
internal fun ChannelPostCard(post: CommunityPost, palette: ArtworkPalette) {
    val context = LocalContext.current
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var overflows by rememberSaveable(post.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelAvatar(post.avatar, post.author, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    post.author,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (post.ago.isNotBlank()) {
                    Text(post.ago, style = MaterialTheme.typography.bodySmall, color = palette.onBackgroundVariant)
                }
            }
        }
        if (post.text.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SelectionContainer {
                    Text(
                        post.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackground,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { overflows = it.hasVisualOverflow },
                    )
                }
                if (!expanded && overflows) {
                    TextButton(onClick = { expanded = true }) {
                        Text(stringResource(R.string.channel_post_more), color = palette.onBackgroundVariant)
                    }
                }
            }
        }
        post.image?.let { url ->
            AsyncImage(
                model = ThumbnailUrlResolver.resolveCommunityPostImage(url, uncropped = true),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 520.dp).background(palette.elevated),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ThumbUp, contentDescription = null, tint = palette.onBackgroundVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                post.likes.ifBlank { stringResource(R.string.player_like) },
                style = MaterialTheme.typography.labelLarge,
                color = palette.onBackgroundVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { shareLink(context, "https://www.youtube.com/post/${post.id}", post.author) }) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.ui_share), modifier = Modifier.size(20.dp), tint = palette.onBackgroundVariant)
            }
            if (post.comments.isNotBlank()) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(20.dp), tint = palette.onBackgroundVariant)
                Spacer(Modifier.width(8.dp))
                Text(post.comments, style = MaterialTheme.typography.labelLarge, color = palette.onBackgroundVariant)
            }
        }
        HorizontalDivider(color = palette.onBackgroundVariant.copy(alpha = 0.35f), thickness = 0.5.dp)
    }
}

/**
 * Grid view on Videos and Live, and the channel home's featured video. The home page's card:
 * inset thumbnail, then the avatar, the title and the meta line at the home page's sizes.
 */
@Composable
internal fun ChannelFeedVideo(video: VideoItem, palette: ArtworkPalette, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    val anchor = rememberVideoAnchor(FeedVideoThumbRadius.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .prefetchOnPress(video.id)
            .pressable(
                pressedScale = 0.97f,
                lift = true,
                liftPadding = PaddingValues(horizontal = PageGutter),
                onLongClick = { menu(video) },
                onClick = { anchor.play(onClick) },
            ),
    ) {
        Box(Modifier.padding(horizontal = PageGutter).videoAnchor(anchor).clip(RoundedCornerShape(FeedVideoThumbRadius.dp))) {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = FeedVideoThumbRadius, videoId = video.id)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp), live = video.live)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.padding(horizontal = PageGutter, vertical = 12.dp)) {
            ChannelAvatar(video.feedAvatar(), video.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FadingTitle(
                    text = video.title,
                    style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
                    color = palette.onBackground,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    metaLine(video.channel, video.views, video.uploaded),
                    color = palette.onBackgroundVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A video on the channel, as a row: thumbnail on the left, title and the detail line. No channel name. */
@Composable
internal fun ChannelVideoRow(video: VideoItem, palette: ArtworkPalette, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    val anchor = rememberVideoAnchor(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = PageGutter).prefetchOnPress(video.id)
            .pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = { anchor.play(onClick) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(168.dp).videoAnchor(anchor)) {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12, videoId = video.id)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true, live = video.live)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = metaLine("", video.views, video.uploaded)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = palette.onBackgroundVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
