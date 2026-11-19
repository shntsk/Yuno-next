package com.yuno

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * How far the sheet rises before the page starts to appear.
 *
 * The page is the thing covering the tab buttons. At 0.15 they washed out in
 * the first part of the pull and only came back once the video had nearly
 * landed in the mini player. Holding the page clear until halfway keeps the
 * buttons up while the video leaves, and brings them back while it is still
 * on the way down.
 */
internal const val PAGE_FADE_LEAD = 0.5f

/**
 * The mini player the expanded player docks into.
 * Screen-space rects bridge the modal sheet and the bar beneath it.
 */
@Stable
class PlayerDock {
    private var miniArt: LayoutCoordinates? = null
    private var miniArtCorner: Dp = MiniThumbRadius

    var sheetOffset: (() -> Float)? by mutableStateOf(null)
    var sheetTravel by mutableFloatStateOf(0f)
    var attached by mutableStateOf(false)
        internal set

    /** The expanded player is on screen. The slot stays up until the flying picture covers it. */
    var sheetUp by mutableStateOf(false)

    /**
     * A dock measurement taken after the sheet was actually placed. The first
     * one is still the window's unsettled position, and drawing it puts a
     * second copy of the frame just under the mini player.
     */
    var poseReady by mutableStateOf(false)
        private set

    /**
     * The page may draw only after the sheet has actually been at the bottom.
     * A first frame that thinks it is fully open otherwise paints the solid
     * page down there and then snaps it clear.
     */
    var fadeArmed = false

    fun reportMiniArt(coordinates: LayoutCoordinates, corner: Dp) {
        miniArt = coordinates
        miniArtCorner = corner
    }

    fun releaseMiniArt(coordinates: LayoutCoordinates) {
        if (miniArt === coordinates) miniArt = null
    }

    internal fun hasMiniArt(): Boolean = miniArt?.isAttached == true

    internal fun miniArtOnScreen(): Rect? {
        val coordinates = miniArt?.takeIf { it.isAttached } ?: return null
        val size = coordinates.size.toSize()
        return Rect(
            coordinates.localToScreen(Offset.Zero),
            coordinates.localToScreen(Offset(size.width, size.height)),
        )
    }

    internal fun miniArtCorner(): Dp = miniArtCorner

    fun notePose() {
        poseReady = true
    }

    fun resetPose() {
        poseReady = false
    }

    /**
     * Thumbnail the open animation grows out of, in screen pixels. Set for a tap on a
     * video; null when the player opens with no picture to start from (a notification,
     * Play on a playlist). Cleared once that open has settled.
     */
    var launchFrom by mutableStateOf<Rect?>(null)
    var launchRadiusPx by mutableFloatStateOf(0f)

    fun armFromPage(rect: Rect?, radiusPx: Float) {
        launchFrom = rect
        launchRadiusPx = radiusPx
    }

    fun finishLaunch() {
        launchFrom = null
        launchRadiusPx = 0f
    }

    internal fun offset(): Float? = sheetOffset?.invoke()?.takeUnless { it.isNaN() }

    fun openFraction(): Float {
        val travel = sheetTravel
        val offset = offset() ?: return 0f
        if (travel <= 0f) return 0f
        return (1f - offset / travel).coerceIn(0f, 1f)
    }

    /** 0 while the page should stay clear, 1 when the player is fully open. */
    fun pageAlpha(holding: Boolean): Float {
        // No travel or no position yet. That 0 is "not ready", not "at the bottom",
        // and must not arm the fade — the next frame would paint the solid page.
        if (sheetTravel <= 0f) return 0f
        val at = offset() ?: return 0f
        val open = (1f - at / sheetTravel).coerceIn(0f, 1f)
        if (open < PAGE_FADE_LEAD) fadeArmed = true
        if (!holding && open >= 1f) fadeArmed = true
        if (!fadeArmed) return 0f
        if (open >= 1f) return 1f
        return ((open - PAGE_FADE_LEAD) / (1f - PAGE_FADE_LEAD)).coerceIn(0f, 1f)
    }

    val coversMiniArt: Boolean
        get() = coversMiniArtState.value

    private val coversMiniArtState = derivedStateOf {
        sheetUp && poseReady && openFraction() > 0f
    }
}

val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }

/**
 * The thumbnail a tap is about to open, recorded before [PlayerState.open] so the
 * player can grow out of that picture instead of the mini player.
 */
object VideoLaunch {
    private var rect: Rect? = null
    private var radiusPx = 0f

    fun offer(rect: Rect, radiusPx: Float) {
        this.rect = rect
        this.radiusPx = radiusPx
    }

    fun take(): Pair<Rect, Float>? {
        val taken = rect ?: return null
        val radius = radiusPx
        rect = null
        return taken to radius
    }

    fun clear() {
        rect = null
    }
}
