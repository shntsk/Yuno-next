package com.yuno.desktop.data

import com.yuno.desktop.model.DesktopVideo
import java.util.Base64
import java.util.prefs.Preferences

object DesktopLibrary {
    private val preferences = Preferences.userRoot().node("com/yuno/desktop/library")
    private const val PREFIX = "video."

    fun savedVideos(): List<DesktopVideo> =
        runCatching {
            preferences.keys().filter { it.startsWith(PREFIX) }
                .mapNotNull { key -> preferences.get(key, null)?.let(::decode) }
                .sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())

    fun isSaved(video: DesktopVideo): Boolean = preferences.get(PREFIX + video.id, null) != null

    fun save(video: DesktopVideo) {
        preferences.put(PREFIX + video.id, encode(video))
        preferences.flush()
    }

    fun remove(video: DesktopVideo) {
        preferences.remove(PREFIX + video.id)
        preferences.flush()
    }

    fun recentSearches(): List<String> =
        preferences.get("searches", "").split('\n').filter(String::isNotBlank).take(12)

    fun rememberSearch(query: String) {
        val searches = (listOf(query.trim()) + recentSearches().filterNot { it.equals(query.trim(), true) })
            .take(12)
        preferences.put("searches", searches.joinToString("\n"))
        preferences.flush()
    }

    private fun encode(video: DesktopVideo): String = listOf(
        video.id, video.title, video.channel, video.thumbnail.orEmpty(),
        video.durationSeconds.toString(), video.views.toString(),
    ).joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }

    private fun decode(value: String): DesktopVideo? = runCatching {
        val fields = value.split('.').map { String(Base64.getUrlDecoder().decode(it)) }
        DesktopVideo(
            id = fields[0],
            title = fields[1],
            channel = fields[2],
            thumbnail = fields[3].ifBlank { null },
            durationSeconds = fields[4].toLong(),
            views = fields[5].toLong(),
        )
    }.getOrNull()
}
