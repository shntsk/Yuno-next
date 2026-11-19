package com.yuno

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholders the shape of the content they're standing in for. One shimmer runs for the
 * whole group; with reduced motion the bones sit still.
 */
private val LocalSkeletonShift = compositionLocalOf { -1f }

@Composable
private fun SkeletonGroup(content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val shift by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonShift",
    )
    CompositionLocalProvider(LocalSkeletonShift provides if (reduced) -1f else shift, content = content)
}

/** A soft block. The page tone is the fill; a faint gleam travels across it. */
private fun Modifier.bone(shape: Shape = RoundedCornerShape(6.dp)): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val gleam = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val shift = LocalSkeletonShift.current
    clip(shape).drawBehind {
        drawRect(base)
        if (shift < 0f) return@drawBehind
        val span = size.height.coerceAtLeast(1f)
        val x = shift * (size.width + span) - span
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to gleam,
                1f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + span, span),
            ),
        )
    }
}

private fun Modifier.textBone(fraction: Float) =
    fillMaxWidth(fraction).height(12.dp).bone()

@Composable
fun FeedSkeletons(count: Int = 4) {
    SkeletonGroup {
        Column {
            repeat(count) { VideoCardSkeleton() }
        }
    }
}

/** Home, search, subscriptions, channel uploads. */
@Composable
fun VideoCardSkeleton() {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(
            Modifier
                .padding(horizontal = FeedVideoThumbInset)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .bone(RoundedCornerShape(FeedVideoThumbRadius.dp)),
        )
        Row(Modifier.padding(horizontal = PageGutter, vertical = 12.dp)) {
            Box(Modifier.size(40.dp).bone(CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.textBone(0.92f))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.textBone(0.64f))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.textBone(0.38f))
            }
        }
    }
}

/** "More videos" under the player. */
@Composable
fun MoreVideoSkeletons(count: Int = 3) {
    SkeletonGroup {
        Column {
            repeat(count) { VideoRowSkeleton() }
        }
    }
}

@Composable
private fun VideoRowSkeleton() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(16.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.textBone(0.9f))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.textBone(0.55f))
        }
    }
}

/** Library history strip. */
@Composable
fun HistorySkeletons() {
    SkeletonGroup {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) {
                Column(Modifier.width(168.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .bone(RoundedCornerShape(FeedVideoThumbRadius.dp)),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.textBone(0.92f))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.textBone(0.6f))
                }
            }
        }
    }
}

@Composable
fun CommentSkeletons(count: Int = 6, avatar: Dp = 36.dp, start: Dp = 0.dp) {
    SkeletonGroup {
        Column {
            repeat(count) { CommentSkeleton(avatar = avatar, start = start) }
        }
    }
}

@Composable
fun CommentSkeleton(avatar: Dp = 36.dp, start: Dp = 0.dp, lines: Int = 2) {
    Row(Modifier.fillMaxWidth().padding(start = start, top = 10.dp, bottom = 6.dp)) {
        Box(Modifier.size(avatar).bone(CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.textBone(0.32f))
            Spacer(Modifier.height(8.dp))
            repeat(lines) { line ->
                Box(Modifier.textBone(if (line == lines - 1) 0.72f else 1f))
                if (line != lines - 1) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** Which channel tab a placeholder is standing in for. */
internal enum class ChannelBone { VideoRow, VideoCard, Shorts, PlaylistRow, PlaylistCard, Post }

/**
 * Channel page before the banner and the tabs arrive: the header, the tab row, the page gap,
 * then the list rows.
 */
@Composable
fun ChannelSkeletons() {
    SkeletonGroup {
        Column {
            ChannelHeaderBone()
            ChannelTabBone()
            Spacer(Modifier.height(PageGutter))
            repeat(3) { ChannelVideoCardBone() }
        }
    }
}

/** A tab's own placeholders. [page] is the first load; a further page is a single row. */
@Composable
internal fun ChannelTabSkeleton(kind: ChannelBone, page: Boolean) {
    val count = when (kind) {
        ChannelBone.VideoRow, ChannelBone.PlaylistRow -> if (page) 6 else 1
        ChannelBone.VideoCard, ChannelBone.PlaylistCard -> if (page) 3 else 1
        ChannelBone.Shorts, ChannelBone.Post -> if (page) 2 else 1
    }
    SkeletonGroup {
        when (kind) {
            ChannelBone.VideoRow -> Column { repeat(count) { ChannelVideoRowBone() } }
            ChannelBone.VideoCard -> Column { repeat(count) { ChannelVideoCardBone() } }
            ChannelBone.PlaylistRow -> Column { repeat(count) { ChannelPlaylistRowBone() } }
            ChannelBone.PlaylistCard -> Column { repeat(count) { ChannelPlaylistCardBone() } }
            ChannelBone.Shorts -> ChannelShortsBone(count)
            ChannelBone.Post -> Column { repeat(count) { ChannelPostBone() } }
        }
    }
}

@Composable
private fun ChannelHeaderBone() {
    Column(Modifier.padding(horizontal = PageGutter)) {
        Box(Modifier.fillMaxWidth().aspectRatio(5.6f / 1.55f).bone(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).bone(CircleShape))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).height(72.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                Box(Modifier.fillMaxWidth(0.62f).height(16.dp).bone(RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.36f).height(12.dp).bone(RoundedCornerShape(6.dp)))
                Box(Modifier.textBone(0.42f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).bone(CircleShape))
    }
}

/** The tab row under the name: a few labels, then the hairline. */
@Composable
private fun ChannelTabBone() {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        listOf(56.dp, 64.dp, 52.dp, 72.dp).forEach { width ->
            Box(Modifier.width(width).height(12.dp).bone())
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).bone(RoundedCornerShape(0.dp)))
}

/** Videos and Live, list: the 168dp row, with the page gutter under it. */
@Composable
private fun ChannelVideoRowBone() {
    Row(
        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = PageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(168.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.textBone(0.92f))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.textBone(0.7f))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.textBone(0.4f))
        }
    }
}

/** Videos and Live, grid: the home card, avatar included, title and one meta line. */
@Composable
private fun ChannelVideoCardBone() {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(
            Modifier
                .padding(horizontal = PageGutter)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .bone(RoundedCornerShape(FeedVideoThumbRadius.dp)),
        )
        Row(Modifier.padding(horizontal = PageGutter, vertical = 12.dp)) {
            Box(Modifier.size(40.dp).bone(CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.textBone(0.92f))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.textBone(0.5f))
            }
        }
    }
}

/** Playlists, Shows and Podcasts, list: the 142dp row. */
@Composable
private fun ChannelPlaylistRowBone() {
    Row(
        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = PageGutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(142.dp).aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.textBone(0.8f))
            Box(Modifier.textBone(0.36f))
        }
    }
}

/** Playlists, Shows and Podcasts, grid: the inset card, title and subtitle, no avatar. */
@Composable
private fun ChannelPlaylistCardBone() {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(
            Modifier
                .padding(horizontal = PageGutter)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .bone(RoundedCornerShape(FeedVideoThumbRadius.dp)),
        )
        Column(Modifier.padding(horizontal = PageGutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.textBone(0.72f))
            Box(Modifier.textBone(0.4f))
        }
    }
}

/** Shorts: the 160dp grid, title under the poster, the same gaps as the tab. */
@Composable
private fun ChannelShortsBone(rows: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = ((maxWidth - PageGutter * 2 + 12.dp) / (160.dp + 12.dp)).toInt().coerceAtLeast(1)
        Column {
            repeat(rows) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(cols) {
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).bone(RoundedCornerShape(16.dp)))
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.textBone(0.9f))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.textBone(0.45f))
                        }
                    }
                }
            }
        }
    }
}

/** A post: the 12dp gap above it, the side inset, a picture, then the hairline. */
@Composable
private fun ChannelPostBone() {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).bone(CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.textBone(0.4f))
                Box(Modifier.textBone(0.22f))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.textBone(1f))
            Box(Modifier.textBone(0.86f))
            Box(Modifier.textBone(0.5f))
        }
        Box(Modifier.fillMaxWidth().height(160.dp).bone(RoundedCornerShape(0.dp)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(20.dp).bone(CircleShape))
            Box(Modifier.width(36.dp).height(12.dp).bone())
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).bone(RoundedCornerShape(0.dp)))
    }
}

/** Save-to-playlist sheet, before the account's playlists arrive. */
@Composable
fun PlaylistChoiceSkeletons(count: Int = 5) {
    SkeletonGroup {
        Column {
            repeat(count) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).bone(CircleShape))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.textBone(0.48f))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.textBone(0.26f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(26.dp).bone(CircleShape))
                }
            }
        }
    }
}
