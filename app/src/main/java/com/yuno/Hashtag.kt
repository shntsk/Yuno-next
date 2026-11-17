package com.yuno

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

/** A hashtag page keeps the regular video feed and Shorts feed independent, each with its own paging token. */
class HashtagState(val tag: String, scope: CoroutineScope) {
    val videos = FeedState(scope) { token -> InnerTube.hashtagFeed(tag, type = 1, continuation = token) }
    val shorts = FeedState(scope) { token -> InnerTube.hashtagFeed(tag, type = 2, continuation = token) }

    var selectedTab by mutableIntStateOf(0)
        private set

    val feed: FeedState get() = if (selectedTab == 0) videos else shorts

    fun selectTab(index: Int) {
        if (index !in 0..1 || index == selectedTab) return
        selectedTab = index
    }
}

@Composable
fun HashtagScreen(
    state: HashtagState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val feed = state.feed
    val tabLabels = listOf(
        stringResource(R.string.hashtag_tab_all),
        stringResource(R.string.hashtag_tab_shorts),
    )

    LaunchedEffect(state.selectedTab) {
        list.scrollToItem(0)
        state.feed.ensureLoaded()
    }

    val nearEnd by remember(feed) {
        derivedStateOf {
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, feed.videos.size, feed.loading, feed.hasMore) {
        if (nearEnd && feed.videos.isNotEmpty() && feed.hasMore) feed.loadMore()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = contentPadding.withTop(pageContentTop()),
        ) {
            item(key = "hashtag-title") {
                Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
                    LargePageTitle("#${state.tag}", fontSize = LargePageTitleCompactFontSize)
                    Text(
                        stringResource(R.string.hashtag_page_subtitle),
                        color = c.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.padding(bottom = 8.dp))
                }
            }
            item(key = "hashtag-tabs") {
                UnderlineTabs(
                    labels = tabLabels,
                    selected = state.selectedTab,
                    onPick = state::selectTab,
                    modifier = Modifier.padding(horizontal = PageGutter),
                )
            }

            if (feed.videos.isNotEmpty()) {
                items(feed.videos, key = { "hashtag-${state.selectedTab}-${it.id}" }) { video ->
                    VideoCard(video, onClick = { onOpen(video) })
                }
            }

            if (feed.waiting || feed.loading && feed.videos.isEmpty()) {
                item(key = "hashtag-loading") {
                    Box(Modifier.fillMaxWidth().padding(42.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (feed.error != null && feed.videos.isEmpty()) {
                item(key = "hashtag-error") {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.hashtag_error_title),
                        body = feed.error.orEmpty(),
                        action = { PillButton(stringResource(R.string.ui_try_again), feed::refresh) },
                    )
                }
            } else if (feed.loadedEmpty) {
                item(key = "hashtag-empty") {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.hashtag_empty_title),
                        body = stringResource(R.string.hashtag_empty_body),
                    )
                }
            } else if (feed.loading || feed.hasMore) {
                item(key = "hashtag-more") {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        PageTopFade(list)
    }
}
