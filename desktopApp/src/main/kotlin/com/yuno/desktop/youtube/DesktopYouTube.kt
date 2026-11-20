package com.yuno.desktop.youtube

import com.yuno.desktop.account.DesktopAccount
import com.yuno.desktop.model.DesktopVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DesktopVideoDetails(
    val video: DesktopVideo,
    val description: String,
    val quality: String?,
    val videoUrl: String?,
    val audioUrl: String?,
)

object DesktopYouTube {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    @Volatile
    private var initialized = false

    private val downloader by lazy {
        object : Downloader() {
            override fun execute(request: Request): Response {
                val body = request.dataToSend()?.toRequestBody()
                val builder = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), body)
                    .header("User-Agent", USER_AGENT)

                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }

                if (request.url().contains("youtube.com", ignoreCase = true)) {
                    DesktopAccount.cookieHeader().takeIf { it.isNotBlank() }?.let {
                        builder.header("Cookie", it)
                    }
                    DesktopAccount.authorization()?.let {
                        builder.header("Authorization", it)
                    }
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 429) {
                        throw IOException("YouTube temporarily rate-limited this connection (HTTP 429). Try again later.")
                    }
                    DesktopAccount.absorbSetCookies(response.headers("Set-Cookie"))
                    val text = response.body?.string()
                    return Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        text,
                        response.request.url.toString(),
                    )
                }
            }
        }
    }

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(downloader)
            YoutubeStreamExtractor.setFetchIosClient(false)
            initialized = true
        }
    }

    suspend fun search(query: String): List<DesktopVideo> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter something to search for." }
        initialize()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(
            query.trim(),
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            "",
        )
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull()
                    ?: return@mapNotNull null
                val thumbnail = item.thumbnails.orEmpty()
                    .filter { it.width > 0 }
                    .minByOrNull { kotlin.math.abs(it.width - 640) }?.url
                    ?: item.thumbnails.orEmpty().firstOrNull()?.url
                DesktopVideo(
                    id = id,
                    title = item.name,
                    channel = item.uploaderName.orEmpty(),
                    thumbnail = thumbnail,
                    durationSeconds = item.duration,
                    views = item.viewCount,
                )
            }
            .distinctBy { it.id }
    }

    suspend fun details(video: DesktopVideo): DesktopVideoDetails = withContext(Dispatchers.IO) {
        initialize()
        val service = ServiceList.YouTube
        val link = service.streamLHFactory.fromUrl(watchUrl(video.id))
        val info = StreamInfo.getInfo(service, link)
        val muxed = info.videoStreams.orEmpty()
            .filter { it.isUrl() && !it.isVideoOnly() }
            .maxByOrNull { it.height }
        val separateVideo = info.videoOnlyStreams.orEmpty()
            .filter { it.isUrl() }
            .maxByOrNull { it.height }
        val audio = info.audioStreams.orEmpty()
            .filter { it.isUrl() }
            .firstOrNull()

        val videoUrl = separateVideo?.content ?: muxed?.content
        val audioUrl = if (separateVideo != null) audio?.content else null
        val quality = separateVideo?.resolution ?: muxed?.resolution

        DesktopVideoDetails(
            video = video.copy(
                title = info.name,
                channel = info.uploaderName.orEmpty(),
                durationSeconds = info.duration,
                views = info.viewCount,
            ),
            description = info.description?.content.orEmpty(),
            quality = quality,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
    }

    fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"
}
