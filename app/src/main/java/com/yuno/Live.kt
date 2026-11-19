package com.yuno

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** A running livestream's manifest. A finished one stays on the ordinary streams. */
internal sealed class LiveSource {
    data class Dash(val url: String) : LiveSource()
    data class Hls(val url: String) : LiveSource()
}

/**
 * MeeTube plays a live video from its manifest URL: DASH first (their live route), HLS when
 * that URL is missing. Past streams are [StreamType.VIDEO_STREAM] and are left alone.
 */
internal fun StreamInfo.liveSource(): LiveSource? {
    if (streamType != StreamType.LIVE_STREAM && streamType != StreamType.AUDIO_LIVE_STREAM) return null
    val dash = runCatching { dashMpdUrl }.getOrNull()?.trimEnd('?', '&')?.takeIf { it.isNotBlank() }
    if (dash != null) return LiveSource.Dash(dash)
    val hls = runCatching { hlsUrl }.getOrNull()?.trimEnd('?', '&')?.takeIf { it.isNotBlank() }
    return hls?.let { LiveSource.Hls(it) }
}

data class LiveMessage(val id: String, val author: String, val avatar: String?, val text: String)

/**
 * Live chat, as MeeTube polls it: every message (not Top chat), the wait YouTube asks for,
 * and a growing pause when the connection drops. The watching line comes off the same watch page.
 */
class LiveChat(private val scope: CoroutineScope) {
    var messages by mutableStateOf<List<LiveMessage>>(emptyList())
        private set
    var watching by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    private var videoId: String? = null
    private var job: Job? = null
    private var watchedAt = 0L

    fun start(id: String) {
        if (videoId == id && job?.isActive == true) return
        stop()
        videoId = id
        loading = true
        job = scope.launch(Dispatchers.IO) {
            val backoff = LiveChatBackoff()
            while (isActive) {
                val wait = try {
                    val good = session(id)
                    if (good > 0) RECONNECT_MS else max(RECONNECT_MS, backoff.nextDelayMs())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    backoff.nextDelayMs()
                }
                delay(wait)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        videoId = null
        messages = emptyList()
        watching = null
        loading = false
        draft = ""
        sending = false
        sendError = null
        watchedAt = 0L
    }

    /** MeeTube's send: the same params and `live_chat/send_message` body. Needs a signed-in session. */
    fun send(channelId: String?) {
        val id = videoId ?: return
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        if (!Account.signedIn || channelId.isNullOrBlank()) {
            sendError = "Sign in to chat."
            return
        }
        if (text.length > 200) return
        sending = true
        sendError = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("params", liveChatSendParams(channelId, id))
                    .put("clientMessageId", UUID.randomUUID().toString())
                    .put("richMessage", JSONObject().put("textSegments", org.json.JSONArray().put(JSONObject().put("text", text))))
                InnerTube.post("live_chat/send_message", body)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    if (videoId == id) { draft = ""; sending = false }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) {
                    if (videoId == id) { sending = false; sendError = "Couldn't send that." }
                }
            }
        }
    }

    private suspend fun session(id: String): Int {
        val page = InnerTube.postAny("next", JSONObject().put("videoId", id))
        noteWatching(id, page.watchingLabel())
        val key = page.liveChatKey() ?: return 0
        val allKey = liveChatAllMessages(key)
        var continuation: String? = allKey
        var good = 0
        while (coroutineContext.isActive && !continuation.isNullOrEmpty()) {
            if (videoId != id) return good
            maybeRefreshWatching(id)
            val used = continuation
            var result = runCatching { chatPage(used) }.getOrNull()
            if (good == 0 && used == allKey && allKey != key && result?.chatContinuation()?.first.isNullOrEmpty()) {
                result = runCatching { chatPage(key) }.getOrNull()
            }
            val next = result?.chatContinuation()
            continuation = next?.first
            if (!continuation.isNullOrEmpty()) good++
            val fresh = result?.chatMessages().orEmpty().filter { keep(it) }
            if (fresh.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (videoId == id) {
                        val seen = messages.mapTo(HashSet()) { it.id }
                        messages = (messages + fresh.filter { seen.add(it.id) }).takeLast(400)
                        loading = false
                    }
                }
            } else if (good == 0) {
                withContext(Dispatchers.Main) { if (videoId == id) loading = false }
            }
            val timeout = next?.second ?: break
            delay(timeout.toLong())
        }
        return good
    }

    private suspend fun maybeRefreshWatching(id: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - watchedAt < 20_000) return
        val page = runCatching { InnerTube.postAny("next", JSONObject().put("videoId", id)) }.getOrNull() ?: return
        noteWatching(id, page.watchingLabel())
    }

    private suspend fun noteWatching(id: String, label: String?) {
        watchedAt = android.os.SystemClock.elapsedRealtime()
        if (label.isNullOrBlank()) return
        withContext(Dispatchers.Main) { if (videoId == id) watching = label }
    }

    private fun chatPage(key: String) = InnerTube.postAny("live_chat/get_live_chat", JSONObject().put("continuation", key))

    private fun keep(item: LiveMessage): Boolean {
        val name = item.author.lowercase()
        return SPAM.none { name.contains(it.lowercase()) }
    }

    private companion object {
        const val RECONNECT_MS = 5_000L
        val SPAM = arrayOf(". XYZ", ". ХYZ", "⠄XYZ", "⠄ХYZ", "Ricardo Merlino", "⠄СОM", ".COM", ".СОM", ". COM")
    }
}

/** Field 16's chat type 4 (Top chat) becomes 1 (every message). Unrecognised keys are unchanged. */
internal fun liveChatAllMessages(chatKey: String): String {
    val bytes = runCatching {
        Base64.getDecoder().decode(pad(URLDecoder.decode(chatKey, "UTF-8").replace('-', '+').replace('_', '/')))
    }.getOrNull() ?: return chatKey
    for (i in 0 until bytes.size - 4) {
        if (bytes[i] == 0x82.toByte() && bytes[i + 1] == 0x01.toByte()
            && bytes[i + 3] == 0x08.toByte() && bytes[i + 4] == 0x04.toByte()
        ) {
            bytes[i + 4] = 0x01
            return Base64.getEncoder().encodeToString(bytes).replace("=", "%3D")
        }
    }
    return chatKey
}

/** MeeTube's send params: channel id and video id, wrapped the way YouTube's own params start. */
internal fun liveChatSendParams(channelId: String, videoId: String): String {
    fun field(number: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((number shl 3) or 2)
        var remaining = payload.size
        while (remaining >= 128) {
            out.write((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        out.write(remaining)
        out.write(payload)
        return out.toByteArray()
    }
    val ids = field(1, channelId.toByteArray()) + field(2, videoId.toByteArray())
    val encoded = field(1, field(5, ids)) + byteArrayOf(0x10, 0x01, 0x18, 0x04)
    val inner = java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(encoded), "UTF-8")
    return Base64.getEncoder().encodeToString(inner.toByteArray())
}

internal class LiveChatBackoff(private val initialMs: Long = 1_000L, private val maxMs: Long = 30_000L) {
    private var nextMs = initialMs
    fun nextDelayMs(): Long {
        val delayMs = nextMs
        nextMs = min(nextMs * 2, maxMs)
        return delayMs
    }
}

private fun pad(b64: String): String {
    val trimmed = b64.trimEnd('=')
    return trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
}

internal fun JSONObject.liveChatKey(): String? {
    var key: String? = null
    forEachObject { obj ->
        if (key != null || !obj.has("liveChatRenderer")) return@forEachObject
        val cont = obj.optJSONObject("liveChatRenderer")?.optJSONArray("continuations")?.optJSONObject(0)
        key = cont?.optJSONObject("reloadContinuationData")?.optString("continuation")?.ifBlank { null }
            ?: cont?.optJSONObject("timedContinuationData")?.optString("continuation")?.ifBlank { null }
    }
    return key
}

internal fun JSONObject.watchingLabel(): String? {
    var label: String? = null
    forEachObject { obj ->
        if (label != null) return@forEachObject
        obj.optJSONObject("videoViewCountRenderer")?.text("viewCount")?.takeIf { it.contains("watching", true) }?.let { label = it }
        if (label == null && obj.has("concurrentViewers")) {
            val digits = obj.opt("concurrentViewers")?.toString()?.filter(Char::isDigit)?.toLongOrNull()
            if (digits != null) label = "%,d watching".format(digits)
        }
    }
    return label
}

private fun JSONObject.chatContinuation(): Pair<String?, Int>? {
    val cont = optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
        ?.optJSONArray("continuations")?.optJSONObject(0) ?: return null
    val timed = cont.optJSONObject("timedContinuationData") ?: cont.optJSONObject("invalidationContinuationData") ?: return null
    return timed.optString("continuation").ifBlank { null } to timed.optInt("timeoutMs", 5_000)
}

private fun JSONObject.chatMessages(): List<LiveMessage> {
    val actions = optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")?.optJSONArray("actions") ?: return emptyList()
    return buildList {
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val renderer = action.optJSONObject("addChatItemAction")?.optJSONObject("item")?.optJSONObject("liveChatTextMessageRenderer")
                ?: action.optJSONObject("addBannerToLiveChatCommand")?.optJSONObject("bannerRenderer")
                    ?.optJSONObject("liveChatBannerRenderer")?.optJSONObject("contents")?.optJSONObject("liveChatTextMessageRenderer")
                ?: continue
            val id = renderer.optString("id").ifBlank { null } ?: continue
            val text = renderer.text("message") ?: continue
            val author = renderer.text("authorName") ?: continue
            add(LiveMessage(id, author, renderer.optJSONObject("authorPhoto")?.thumbnails()?.lastOrNull(), text))
        }
    }
}

@Composable
fun LiveChatCard(chat: LiveChat, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val latest = chat.messages.lastOrNull()
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onOpen)
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(stringResource(R.string.live_chat), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        when {
            latest != null -> Row {
                ChannelAvatar(latest.avatar, latest.author, size = 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(latest.author, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(latest.text, color = c.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            chat.loading -> CommentSkeleton(avatar = 30.dp)
            else -> Text(stringResource(R.string.live_chat_empty), color = c.onSurfaceVariant, fontSize = 13.5.sp)
        }
    }
}

@Composable
fun LiveChatPanel(chat: LiveChat, channelId: String?, partialTop: Dp, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    PlayerPanel(
        partialTop = partialTop,
        onClose = onClose,
        closeLabel = stringResource(R.string.live_chat_close),
        title = { Text(stringResource(R.string.live_chat), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp) },
    ) { listModifier ->
        ChatList(chat, listModifier)
        if (Account.signedIn) ChatComposer(chat, channelId) else Text(
            stringResource(R.string.live_chat_sign_in),
            color = c.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Spacer(Modifier.fillMaxWidth().background(c.surfaceContainer).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun ColumnScope.ChatList(chat: LiveChat, listModifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        val last = chat.messages.lastIndex
        if (last >= 0) list.scrollToItem(last)
    }
    LazyColumn(state = list, modifier = listModifier) {
        items(chat.messages, key = { it.id }) { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChannelAvatar(message.avatar, message.author, size = 32)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.author, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(message.text, color = c.onSurface, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (chat.messages.isEmpty()) item {
            Text(
                stringResource(if (chat.loading) R.string.live_chat else R.string.live_chat_empty),
                color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun ChatComposer(chat: LiveChat, channelId: String?) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = 16.dp, vertical = 10.dp)) {
        chat.sendError?.let { Text(it, color = c.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = chat.draft,
                onValueChange = { if (!chat.sending && it.length <= 200) chat.draft = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = c.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(c.primary),
                decorationBox = { inner ->
                    if (chat.draft.isEmpty()) Text(stringResource(R.string.live_chat_hint), color = c.onSurfaceVariant, fontSize = 15.sp)
                    inner()
                },
            )
            Text(
                "Send",
                color = if (chat.draft.isBlank() || chat.sending) c.onSurfaceVariant else c.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.pressable(enabled = chat.draft.isNotBlank() && !chat.sending) { chat.send(channelId) }.padding(8.dp),
            )
        }
    }
}
