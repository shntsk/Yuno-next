package com.yuno

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** TEMP: set true to show the raw cropped strip with no blur. */
internal const val VIDEO_TOP_DEBUG_RAW_STRIP = false

private const val BOX_BLUR_RADIUS = 3
private const val BOX_BLUR_PASSES = 2
// The status-bar strip does not need display-rate updates.
private const val STATUS_SAMPLE_INTERVAL_MS = 125L
private val ComposeBlurRadius = 12.dp

/**
 * Live blurred copy of the video's top edge for the open player's status-bar region.
 */
object VideoTopSampler {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var topLuminance by mutableFloatStateOf(0f)
        private set
    /** Status-bar height in px — strip crop matches this, not a fraction of the whole frame. */
    var statusBarPx by mutableIntStateOf(0)
    private var running = false
    private var scratch: Bitmap? = null
    private var lastSampleAtMs = 0L
    private var luminanceRow = IntArray(0)
    private var posterEpoch = 0
    /** Real frames stay hidden until this video's own first frame, so the last video's strip can't linger. */
    private var acceptLive = true

    fun start() {
        running = true
        lastSampleAtMs = 0L
    }

    fun stop() {
        running = false
    }

    /** Drop the strip on screen. The next picture is this video's thumbnail, then its own frame. */
    fun holdForPoster() {
        posterEpoch++
        acceptLive = false
        lastSampleAtMs = 0L
        frame = null
        topLuminance = 0f
    }

    fun currentPosterEpoch(): Int = posterEpoch

    /** The new video has drawn. The next texture update replaces the thumbnail strip immediately. */
    fun releaseLive() {
        acceptLive = true
        if (running && statusBarPx > 0) tick(force = true)
    }

    fun showPosterStrip(strip: Bitmap, epoch: Int) {
        if (epoch != posterEpoch || acceptLive) {
            strip.recycle()
            return
        }
        publish(strip)
    }

    /** Called at display rate, but samples at most eight frames per second. */
    fun onVideoFrame() {
        if (!running || !acceptLive || statusBarPx <= 0) return
        tick()
    }

    private fun tick(force: Boolean = false) {
        val barPx = statusBarPx
        if (barPx <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSampleAtMs < STATUS_SAMPLE_INTERVAL_MS) return
        lastSampleAtMs = now
        val fresh = SharedVideoTexture.captureStatusStrip(barPx) ?: return
        publish(fresh)
    }

    private fun publish(fresh: Bitmap) {
        val target = scratch?.takeIf { it.width == fresh.width && it.height == fresh.height }
            ?: Bitmap.createBitmap(fresh.width, fresh.height, Bitmap.Config.ARGB_8888).also { scratch = it }
        Canvas(target).drawBitmap(fresh, 0f, 0f, null)
        fresh.recycle()
        topLuminance = averageLuminance(target)
        if (!VIDEO_TOP_DEBUG_RAW_STRIP) {
            BitmapBoxBlur.apply(target, BOX_BLUR_RADIUS, BOX_BLUR_PASSES)
        }
        frame = target.asImageBitmap()
    }

    private fun averageLuminance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        if (n == 0) return 0f
        val row = luminanceRow.takeIf { it.size == w } ?: IntArray(w).also { luminanceRow = it }
        var sum = 0f
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8 and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return sum / n
    }
}


/**
 * The status-bar strip for a video that hasn't drawn yet: the top of its thumbnail.
 * [VideoTopSampler.releaseLive] swaps in the real frame with no fade.
 */
@Composable
internal fun BindVideoTopPoster(videoId: String?, url: String?, statusBarPx: Int, videoHeightPx: Int) {
    val context = LocalContext.current
    LaunchedEffect(videoId, url, statusBarPx, videoHeightPx) {
        if (videoId == null || url.isNullOrBlank() || statusBarPx <= 0 || videoHeightPx <= 0) return@LaunchedEffect
        val epoch = VideoTopSampler.currentPosterEpoch()
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        val source = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        if (source.width < 1 || source.height < 1) return@LaunchedEffect
        val fraction = (statusBarPx.toFloat() / videoHeightPx).coerceIn(0.02f, 0.45f)
        val band = (source.height * fraction).toInt().coerceIn(1, source.height)
        val strip = runCatching {
            val raw = Bitmap.createBitmap(source, 0, 0, source.width, band)
            val copy = raw.copy(Bitmap.Config.ARGB_8888, true)
            if (copy !== raw) raw.recycle()
            copy
        }.getOrNull() ?: return@LaunchedEffect
        VideoTopSampler.showPosterStrip(strip, epoch)
    }
}

/** Blurred duplicate of the video top, drawn only in the status-bar strip. */
@Composable
internal fun VideoTopStatusBlur(
    height: Dp,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val bitmap = VideoTopSampler.frame
    val controlsDim = VideoControlsOverlay.dimAlpha
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha() },
    ) {
        if (bitmap != null) {
            val blurMod = when {
                VIDEO_TOP_DEBUG_RAW_STRIP -> Modifier
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    Modifier.blur(ComposeBlurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                else -> Modifier
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().then(blurMod),
            )
            if (controlsDim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.alpha = controlsDim }
                        .background(VideoControlsOverlay.statusBarDimColor),
                )
            }
        }
    }
}

/** White status icons on a dark top; dark icons when the frame top is already bright. */
internal fun videoStatusUsesLightIcons(topLuminance: Float): Boolean = topLuminance < 0.42f
