package com.yuno

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colours an artist page paints itself in. Brought from BitChord's
 * [ArtworkPalette] (`sharedUi/.../theme/ArtworkPalette.kt`).
 *
 * The page tint, the wash the photograph dissolves into, and the accent are
 * read off the picture. The same picture is a near-black tint in dark mode and
 * a pale wash of the same hue in light mode.
 */
@Immutable
data class ArtworkPalette(
    /** The page's background wash. */
    val background: Color,
    /**
     * The colour the artwork's own bottom edge blurs down to.
     *
     * An artist page is this colour, flat, all the way down under its photograph.
     */
    val wash: Color,
    /** Fill for the glass buttons and chips that sit on [background]. */
    val elevated: Color,
    /** The artwork's own colour, contrast-corrected. */
    val accent: Color,
    val onBackground: Color,
    val onBackgroundVariant: Color,
    val divider: Color,
)

/**
 * Pulls [ArtworkPalette] out of the artwork at [imageUrl].
 *
 * [alternateUrl] is a second picture of the same subject, such as a channel
 * banner beside its profile picture. A real hue in either one paints the page,
 * so a white icon does not hide the colour sitting in the banner.
 *
 * Artwork that has already been read once is tinted on the very first frame.
 * Only a picture genuinely being seen for the first time starts from the
 * theme's own colours and warms into the artwork's. "Reduce animation" turns
 * that crossfade into a cut.
 */
@Composable
fun rememberArtworkPalette(imageUrl: String?, alternateUrl: String? = null): ArtworkPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val reduceAnimation = rememberReducedMotion()
    val primary = rememberArtworkSeed(imageUrl)
    val alternate = rememberArtworkSeed(alternateUrl?.takeIf { it.isNotEmpty() && it != imageUrl })
    val decoded = pickReadySeed(primary, alternate)
    val knownUpFront = remember(imageUrl, alternateUrl) { decoded != null }
    val target = decoded?.toPalette(dark) ?: ArtworkPalette(
        background = scheme.background,
        wash = scheme.background,
        elevated = scheme.surfaceVariant,
        accent = scheme.primary,
        onBackground = scheme.onBackground,
        onBackgroundVariant = scheme.onSurfaceVariant,
        divider = scheme.outline,
    )
    val spec: AnimationSpec<Color> = if (reduceAnimation || knownUpFront) snap() else tween(TINT_FADE_MS)
    return ArtworkPalette(
        background = animateColorAsState(target.background, spec, label = "tintBackground").value,
        wash = animateColorAsState(target.wash, spec, label = "tintWash").value,
        elevated = animateColorAsState(target.elevated, spec, label = "tintElevated").value,
        accent = animateColorAsState(target.accent, spec, label = "tintAccent").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "tintOn").value,
        onBackgroundVariant = animateColorAsState(target.onBackgroundVariant, spec, label = "tintOnVariant").value,
        divider = animateColorAsState(target.divider, spec, label = "tintDivider").value,
    )
}

private class ArtRead(val seed: Seed?, val ready: Boolean)

@Composable
private fun rememberArtworkSeed(imageUrl: String?): ArtRead {
    val context = LocalContext.current
    var read by remember(imageUrl) {
        mutableStateOf(
            when {
                imageUrl == null -> ArtRead(null, ready = true)
                seedCache.containsKey(imageUrl) -> ArtRead(seedCache[imageUrl], ready = true)
                else -> ArtRead(null, ready = false)
            }
        )
    }
    LaunchedEffect(imageUrl) {
        if (imageUrl == null || read.ready) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(PALETTE_PX)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.drawable as? BitmapDrawable
        val copy = bitmap?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        val found = if (copy == null) {
            null
        } else {
            withContext(Dispatchers.Default) { seedOf(copy) }
        }
        seedCache[imageUrl] = found
        read = ArtRead(found, ready = true)
    }
    return read
}

/**
 * A colourful picture shows as soon as it has been read. A white or grey one
 * waits for the other picture, so padding does not flash onto the page before
 * the banner's own colour arrives.
 */
private fun pickReadySeed(primary: ArtRead, alternate: ArtRead): Seed? {
    if (primary.ready && alternate.ready) return pickSeed(primary.seed, alternate.seed)
    return listOf(primary, alternate)
        .filter { it.ready }
        .mapNotNull { it.seed }
        .filter { it.chroma > 0f }
        .maxByOrNull { it.chroma }
}

/**
 * Whichever picture's colour actually shows up more. A close call stays with
 * the profile picture.
 */
private fun pickSeed(primary: Seed?, alternate: Seed?): Seed? {
    if (primary == null) return alternate
    if (alternate == null) return primary
    if (alternate.chroma > primary.chroma * STRONGER_HUE) return alternate
    return primary
}

private const val STRONGER_HUE = 1.1f

private val seedCache = object : LinkedHashMap<String, Seed?>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Seed?>) = size > SEED_CACHE_ENTRIES
}

private const val SEED_CACHE_ENTRIES = 128
private const val PALETTE_PX = 128
private const val TINT_FADE_MS = 260

private data class Seed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
    /** How much of the picture this hue covers, weighted by how coloured it is. */
    val chroma: Float,
)

private fun seedOf(bitmap: Bitmap): Seed? {
    val brand = chromaticBrand(bitmap) ?: return null
    val color = Color(brand.argb).asBrandHue()
    return Seed(color, color, color, chroma = brand.score)
}

/**
 * The hue that shows up the most, once white, black, and grey are set aside.
 *
 * Every hue is scored the same way: how much of the picture it covers, times
 * how coloured those pixels are. A speck does not count. A picture that is
 * only white, black, or grey has no colour, and the page stays plain.
 */
private fun chromaticBrand(bitmap: Bitmap): Brand? {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val count = IntArray(HUE_BINS)
    val satSum = FloatArray(HUE_BINS)
    val redSum = LongArray(HUE_BINS)
    val greenSum = LongArray(HUE_BINS)
    val blueSum = LongArray(HUE_BINS)
    var used = 0
    val hsl = FloatArray(3)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        used++
        ColorUtils.colorToHSL(pixel, hsl)
        val saturation = hsl[1]
        val lightness = hsl[2]
        if (saturation < HUE_MIN_SATURATION || lightness < 0.08f || lightness > 0.94f) continue
        val bin = ((hsl[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
        count[bin]++
        satSum[bin] += saturation
        redSum[bin] += (pixel shr 16) and 0xFF
        greenSum[bin] += (pixel shr 8) and 0xFF
        blueSum[bin] += pixel and 0xFF
    }
    if (used == 0) return null
    var best = -1
    var bestScore = 0f
    for (i in 0 until HUE_BINS) {
        val score = hueScore(count, satSum, used, i)
        if (score > bestScore) {
            bestScore = score
            best = i
        }
    }
    if (best < 0) return null
    val main = hueGroup(count, satSum, redSum, greenSum, blueSum, used, best)
    // Enough of the picture, and coloured enough to be that hue. Anything
    // smaller is a speck; anything greyer is not a colour.
    if (main.presence < 0.08f || main.saturation < 0.14f) return null
    return Brand(main.argb, main.presence * main.saturation)
}

private data class Brand(val argb: Int, val score: Float)

private data class HueGroup(val argb: Int, val presence: Float, val saturation: Float)

private const val HUE_BINS = 36
private const val HUE_MIN_SATURATION = 0.10f

private fun hueIndex(bin: Int): Int = (bin % HUE_BINS + HUE_BINS) % HUE_BINS

private fun hueScore(count: IntArray, satSum: FloatArray, used: Int, center: Int): Float {
    var amount = 0
    var sat = 0.0
    for (delta in -1..1) {
        val bin = hueIndex(center + delta)
        amount += count[bin]
        sat += satSum[bin]
    }
    if (amount == 0) return 0f
    return amount.toFloat() / used * (sat / amount).toFloat()
}

private fun hueGroup(
    count: IntArray,
    satSum: FloatArray,
    redSum: LongArray,
    greenSum: LongArray,
    blueSum: LongArray,
    used: Int,
    center: Int,
): HueGroup {
    var amount = 0
    var sat = 0.0
    var red = 0L
    var green = 0L
    var blue = 0L
    for (delta in -1..1) {
        val bin = hueIndex(center + delta)
        amount += count[bin]
        sat += satSum[bin]
        red += redSum[bin]
        green += greenSum[bin]
        blue += blueSum[bin]
    }
    val safe = amount.coerceAtLeast(1)
    return HueGroup(
        argb = (0xFF shl 24) or ((red / safe).toInt() shl 16) or ((green / safe).toInt() shl 8) or (blue / safe).toInt(),
        presence = amount.toFloat() / used,
        saturation = (sat / safe).toFloat(),
    )
}

private fun Seed.toPalette(dark: Boolean): ArtworkPalette = if (dark) {
    ArtworkPalette(
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.34f, maximum = 0.72f) },
            lightness = { 0.26f },
        ),
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.38f, maximum = 0.74f) },
            lightness = { 0.32f },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.34f, maximum = 0.72f) },
            lightness = { 0.40f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.62f, 0.78f) },
        ),
        onBackground = Color.White,
        onBackgroundVariant = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.50f) },
            lightness = { 0.80f },
        ),
        divider = Color.White.copy(alpha = 0.12f),
    )
} else {
    ArtworkPalette(
        background = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.91f },
        ),
        wash = edge.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.12f, maximum = 0.46f) },
            lightness = { it.coerceIn(0.78f, 0.90f) },
        ),
        elevated = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.14f, maximum = 0.50f) },
            lightness = { 0.83f },
        ),
        accent = vibrant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.55f, maximum = 1f) },
            lightness = { it.coerceIn(0.30f, 0.44f) },
        ),
        onBackground = Color.Black,
        onBackgroundVariant = dominant.withHsl(
            saturation = { adaptedArtworkSaturation(it, minimum = 0.22f, maximum = 0.55f) },
            lightness = { 0.34f },
        ),
        divider = Color.Black.copy(alpha = 0.10f),
    )
}

/**
 * Keeps neutral artwork neutral instead of inventing a hue for it.
 *
 * HSL represents grey with hue zero. Raising that grey to a saturation floor
 * therefore manufactures red. A small real amount of colour is kept as-is.
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) saturation else saturation.coerceIn(minimum, maximum)
}

private const val CHROMATIC_SATURATION_THRESHOLD = 0.12f

/** A chosen brand hue has to clear the neutral line, or a pale tint stays grey. */
private fun Color.asBrandHue(): Color = withHsl(
    saturation = {
        if (it < CHROMATIC_SATURATION_THRESHOLD) CHROMATIC_SATURATION_THRESHOLD + 0.02f else it
    },
)

private fun Color.withHsl(
    saturation: (Float) -> Float = { it },
    lightness: (Float) -> Float = { it },
): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }
    hsl[1] = saturation(hsl[1]).coerceIn(0f, 1f)
    hsl[2] = lightness(hsl[2]).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Channel and playlist pages take their colour from their pictures. Off until turned on. */
object ChannelColorSetting {
    private const val PREFS = "appearance"
    private const val KEY = "channel_colors"
    private var prefs: SharedPreferences? = null
    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        enabled = p.getBoolean(KEY, false)
    }

    fun set(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }
}
