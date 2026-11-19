package com.yuno

import android.net.Uri

/**
 * Everything the search feed can be narrowed by. From Flow's `SearchFilter`.
 * Every field except [watchState] maps to a server-side `params` token. Watched and unwatched
 * are applied to each page against local watch history, which is all YouTube honours for a
 * signed-out account.
 */
data class SearchFilter(
    val contentType: ContentType = ContentType.ALL,
    val duration: Duration = Duration.ANY,
    val uploadDate: UploadDate = UploadDate.ANY,
    val sortType: SortType = SortType.RELEVANCE,
    val features: Set<SearchFeature> = emptySet(),
    val watchState: WatchState = WatchState.ANY,
) {
    val activeCount: Int
        get() = listOf(
            contentType != ContentType.ALL,
            duration != Duration.ANY,
            uploadDate != UploadDate.ANY,
            sortType != SortType.RELEVANCE,
            watchState != WatchState.ANY,
        ).count { it } + features.size
}

enum class ContentType { ALL, VIDEOS, SHORTS, CHANNELS, PLAYLISTS, MOVIES, LIVE }

enum class SortType { RELEVANCE, VIEW_COUNT }

enum class Duration { ANY, UNDER_3_MINUTES, THREE_TO_20_MINUTES, OVER_20_MINUTES }

enum class UploadDate { ANY, LAST_HOUR, TODAY, THIS_WEEK, THIS_MONTH, THIS_YEAR }

enum class WatchState { ANY, UNWATCHED, WATCHED }

enum class SearchFeature { HD, FOUR_K, HDR, SUBTITLES, CREATIVE_COMMONS, THREE_SIXTY, VR180, THREE_D, LOCATION, PURCHASED }

/** Channels and playlists have no duration, date, or feature rows. From Flow's `supportsVideoFilters`. */
fun ContentType.supportsVideoFilters(): Boolean = this != ContentType.CHANNELS && this != ContentType.PLAYLISTS

/** Flow's `SearchFilter.toSearchParams`. Live is a feature flag; Shorts is a type. */
internal fun SearchFilter.toSearchParams(): String? =
    YouTubeSearchParams.build(
        sortBy = when (sortType) {
            SortType.RELEVANCE -> YouTubeSearchParams.SortBy.RELEVANCE
            SortType.VIEW_COUNT -> YouTubeSearchParams.SortBy.VIEW_COUNT
        },
        contentType = when (contentType) {
            ContentType.ALL -> null
            ContentType.VIDEOS, ContentType.LIVE -> YouTubeSearchParams.ContentType.VIDEO
            ContentType.SHORTS -> YouTubeSearchParams.ContentType.SHORTS
            ContentType.CHANNELS -> YouTubeSearchParams.ContentType.CHANNEL
            ContentType.PLAYLISTS -> YouTubeSearchParams.ContentType.PLAYLIST
            ContentType.MOVIES -> YouTubeSearchParams.ContentType.MOVIE
        },
        duration = when {
            !contentType.supportsVideoFilters() || duration == Duration.ANY -> null
            duration == Duration.UNDER_3_MINUTES -> YouTubeSearchParams.Duration.UNDER_3_MINUTES
            duration == Duration.THREE_TO_20_MINUTES -> YouTubeSearchParams.Duration.THREE_TO_20_MINUTES
            else -> YouTubeSearchParams.Duration.OVER_20_MINUTES
        },
        uploadDate = when {
            !contentType.supportsVideoFilters() || uploadDate == UploadDate.ANY -> null
            uploadDate == UploadDate.LAST_HOUR -> YouTubeSearchParams.UploadDate.LAST_HOUR
            uploadDate == UploadDate.TODAY -> YouTubeSearchParams.UploadDate.TODAY
            uploadDate == UploadDate.THIS_WEEK -> YouTubeSearchParams.UploadDate.THIS_WEEK
            uploadDate == UploadDate.THIS_MONTH -> YouTubeSearchParams.UploadDate.THIS_MONTH
            else -> YouTubeSearchParams.UploadDate.THIS_YEAR
        },
        features = buildSet {
            if (contentType == ContentType.LIVE) add(YouTubeSearchParams.Feature.LIVE)
            features.mapTo(this) { it.toParam() }
        },
    )

private fun SearchFeature.toParam(): YouTubeSearchParams.Feature = when (this) {
    SearchFeature.HD -> YouTubeSearchParams.Feature.HD
    SearchFeature.FOUR_K -> YouTubeSearchParams.Feature.FOUR_K
    SearchFeature.HDR -> YouTubeSearchParams.Feature.HDR
    SearchFeature.SUBTITLES -> YouTubeSearchParams.Feature.SUBTITLES
    SearchFeature.CREATIVE_COMMONS -> YouTubeSearchParams.Feature.CREATIVE_COMMONS
    SearchFeature.THREE_SIXTY -> YouTubeSearchParams.Feature.THREE_SIXTY
    SearchFeature.VR180 -> YouTubeSearchParams.Feature.VR180
    SearchFeature.THREE_D -> YouTubeSearchParams.Feature.THREE_D
    SearchFeature.LOCATION -> YouTubeSearchParams.Feature.LOCATION
    SearchFeature.PURCHASED -> YouTubeSearchParams.Feature.PURCHASED
}

/** A strip YouTube put between results. From Flow's `SearchShelfKind`. A grid shelf is Shorts. */
enum class SearchShelfKind { SHORTS, VIDEOS, POSTS }

/**
 * One row of the search feed, in the order YouTube sent it. From Flow's `SearchResultItem`:
 * a result, or a strip. A channel's "latest" videos ride on the channel, because YouTube
 * sends that strip as the next shelf and Flow draws the two as one block.
 */
sealed interface SearchHit {
    data class Video(val item: VideoItem, val short: Boolean = false) : SearchHit
    data class Channel(
        val ref: ChannelRef,
        val meta: String?,
        val latestTitle: String? = null,
        val latestVideos: List<VideoItem> = emptyList(),
    ) : SearchHit
    data class Playlist(val ref: PlaylistRef) : SearchHit
    data class Shelf(
        val id: String,
        val title: String?,
        val kind: SearchShelfKind,
        val videos: List<VideoItem>,
        val posts: List<CommunityPost> = emptyList(),
        val collapsedItemCount: Int? = null,
    ) : SearchHit
}

/** Flow's shelf id, position-qualified so two strips with the same title stay distinct. */
fun SearchHit.identityKey(): String = when (this) {
    is SearchHit.Video -> "v:${item.id}"
    is SearchHit.Channel -> "c:${ref.id}"
    is SearchHit.Playlist -> "p:${ref.id}"
    is SearchHit.Shelf -> "shelf:$id"
}

/**
 * Flow's fold of the videos strip that sits directly after a channel card.
 * The strip does not also appear on its own.
 */
fun List<SearchHit>.foldChannelLatest(): List<SearchHit> {
    val out = ArrayList<SearchHit>(size)
    var index = 0
    while (index < size) {
        val hit = this[index]
        val next = getOrNull(index + 1)
        if (hit is SearchHit.Channel && next is SearchHit.Shelf && next.kind == SearchShelfKind.VIDEOS) {
            out += hit.copy(latestTitle = next.title, latestVideos = next.videos)
            index += 2
        } else {
            out += hit
            index++
        }
    }
    return out
}

/**
 * Flow's `toShortsResults`. The Shorts chip's page is shelves of shorts; the grid wants
 * one short each, and anything that is not a short shelf is left out. The first copy of a
 * video wins, the way Flow's page de-duplicates.
 */
fun List<SearchHit>.asShortsResults(): List<SearchHit> {
    val out = LinkedHashMap<String, SearchHit>()
    for (hit in this) {
        val videos = when (hit) {
            is SearchHit.Video -> listOf(hit.item)
            is SearchHit.Shelf -> if (hit.kind == SearchShelfKind.SHORTS) hit.videos else emptyList()
            else -> emptyList()
        }
        for (video in videos) out.putIfAbsent(video.id, SearchHit.Video(video, short = true))
    }
    return out.values.toList()
}

data class SearchPage(val hits: List<SearchHit>, val next: String?)

/** A pasted YouTube link, opened instead of searched. From Flow's search submitter. */
sealed interface YoutubeTarget {
    data class Video(val id: String) : YoutubeTarget
    data class Short(val id: String) : YoutubeTarget
    data class Channel(val url: String) : YoutubeTarget
    data class Playlist(val id: String) : YoutubeTarget
}

fun parseYoutubeTarget(raw: String): YoutubeTarget? {
    val text = raw.trim()
    if (!text.contains("youtu", ignoreCase = true)) return null
    val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    if (host != "youtu.be" && !host.endsWith("youtube.com") && !host.endsWith("youtube-nocookie.com")) return null
    val path = uri.path.orEmpty()
    if (path.startsWith("/shorts/")) {
        return path.removePrefix("/shorts/").substringBefore('/').takeIf { it.length == 11 }?.let { YoutubeTarget.Short(it) }
    }
    if (path.startsWith("/playlist")) {
        return uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }?.let { YoutubeTarget.Playlist(it) }
    }
    if (path.startsWith("/channel/")) {
        val id = path.removePrefix("/channel/").substringBefore('/')
        if (id.startsWith("UC")) return YoutubeTarget.Channel("https://www.youtube.com/channel/$id")
    }
    if (path.startsWith("/@")) {
        val handle = path.trim('/').substringBefore('/')
        if (handle.startsWith("@")) return YoutubeTarget.Channel("https://www.youtube.com/$handle")
    }
    if (host == "youtu.be") {
        return path.trim('/').substringBefore('/').takeIf { it.length == 11 }?.let { YoutubeTarget.Video(it) }
    }
    uri.getQueryParameter("v")?.takeIf { it.length == 11 }?.let { return YoutubeTarget.Video(it) }
    return null
}
