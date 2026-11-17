package com.yuno

import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString

val LocalVideoLinkOpener = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalHashtagOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

private const val LINK_TAG = "yuno-link"
private val URL_PATTERN = Regex("""(?i)(?<![A-Za-z0-9_@])(?:(?:https?://|www\.)[^\s<>"']+|(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?:/[^\s<>"']*)?)""")
private val HASHTAG_PATTERN = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
private val HANDLE_PATTERN = Regex("""(?<![A-Za-z0-9._%+-])@[A-Za-z0-9][A-Za-z0-9._-]{1,28}[A-Za-z0-9]""")
private val VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

private data class LinkRange(val start: Int, val end: Int, val target: String)

@Composable
fun LinkedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    links: List<TextLinkRange> = emptyList(),
) {
    val uriHandler = LocalUriHandler.current
    val openChannel = LocalChannelOpener.current
    val openVideo = LocalVideoLinkOpener.current
    val openHashtag = LocalHashtagOpener.current
    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    val linkColor = if (background.luminance() < 0.5f) Color(0xFF3EA6FF) else Color(0xFF065FD4)
    val annotated = remember(text, links, linkColor) {
        val ranges = buildList {
            links.forEach { link ->
                if (link.start in 0 until text.length && link.end in 1..text.length &&
                    link.start < link.end && link.url.isNotBlank()
                ) add(LinkRange(link.start, link.end, link.url))
            }
            URL_PATTERN.findAll(text).forEach { match ->
                val clean = match.value.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}')
                if (clean.isNotEmpty()) add(LinkRange(match.range.first, match.range.first + clean.length, clean))
            }
            HASHTAG_PATTERN.findAll(text).forEach { match ->
                add(LinkRange(match.range.first, match.range.last + 1, match.value))
            }
            HANDLE_PATTERN.findAll(text).forEach { match ->
                val handle = match.value
                add(LinkRange(match.range.first, match.range.last + 1, "https://www.youtube.com/$handle"))
            }
        }.sortedWith(compareBy<LinkRange> { it.start }.thenByDescending { it.end - it.start })

        val accepted = mutableListOf<LinkRange>()
        for (range in ranges) {
            if (range.start >= 0 && range.end <= text.length &&
                accepted.none { range.start < it.end && range.end > it.start }
            ) accepted += range
        }
        buildAnnotatedString {
            var cursor = 0
            accepted.sortedBy { it.start }.forEach { range ->
                append(text.substring(cursor, range.start))
                pushStringAnnotation(LINK_TAG, range.target)
                pushStyle(
                    SpanStyle(color = linkColor),
                )
                append(text.substring(range.start, range.end))
                pop()
                pop()
                cursor = range.end
            }
            append(text.substring(cursor))
        }
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style,
        onClick = { offset ->
            val target = annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item
                ?: return@ClickableText
            when (val destination = resolveLink(target)) {
                is LinkDestination.Video -> openVideo(destination.id)
                is LinkDestination.Channel -> openChannel(destination.url)
                is LinkDestination.Hashtag -> openHashtag(destination.tag)
                is LinkDestination.External -> uriHandler.openUri(destination.url)
            }
        },
    )
}

private sealed interface LinkDestination {
    data class Video(val id: String) : LinkDestination
    data class Channel(val url: String) : LinkDestination
    data class Hashtag(val tag: String) : LinkDestination
    data class External(val url: String) : LinkDestination
}

private fun resolveLink(raw: String): LinkDestination {
    if (raw.startsWith("#") && raw.length > 1) return LinkDestination.Hashtag(raw.drop(1))
    val url = when {
        raw.startsWith("www.", ignoreCase = true) -> "https://$raw"
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("mailto:", ignoreCase = true) || raw.startsWith("tel:", ignoreCase = true) -> raw
        raw.contains("://") -> raw
        else -> "https://$raw"
    }
    val uri = Uri.parse(url)
    val host = uri.host.orEmpty().lowercase()
    val path = uri.pathSegments
    val youtubeHost = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")

    if (youtubeHost) {
        val first = path.firstOrNull().orEmpty()
        if (first == "hashtag" && path.getOrNull(1).orEmpty().isNotBlank()) {
            return LinkDestination.Hashtag(path[1])
        }
        val videoId = when {
            host == "youtu.be" -> first
            first == "watch" -> uri.getQueryParameter("v")
            first in setOf("shorts", "live", "embed", "v") -> path.getOrNull(1)
            else -> null
        }?.takeIf { VIDEO_ID_PATTERN.matches(it) }
        if (videoId != null) return LinkDestination.Video(videoId)

        if ((first.startsWith("@") && first.length > 1) ||
            first in setOf("channel", "c", "user")
        ) return LinkDestination.Channel(url)
    }

    return LinkDestination.External(url)
}
