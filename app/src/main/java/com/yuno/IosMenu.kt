package com.yuno

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.yuno.backdrop.drawBackdrop
import com.yuno.backdrop.backdrops.SnapshotBackdrop
import com.yuno.backdrop.effects.blur
import com.yuno.backdrop.effects.colorControls
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * BitChord's song ⋮ menu (`IosMenu`). A charcoal or frost slab with its own
 * label greys, rather than the theme's surface roles — the panel floats over
 * artwork as often as over a page.
 */
@Immutable
data class IosMenuColors(
    val dark: Boolean,
    val fill: Color,
    val rim: Color,
    val label: Color,
    val secondaryLabel: Color,
    val separator: Color,
    /** The rounded fill under the row a finger is on. */
    val pressed: Color,
)

private val DarkMenu = IosMenuColors(
    dark = true,
    fill = Color(0xFF1C1C1E),
    rim = Color.White.copy(alpha = 0.10f),
    // Below full strength for [additiveChrome]: solid white would stay solid white under it.
    label = Color.White.copy(alpha = 0.75f),
    secondaryLabel = Color.White.copy(alpha = 0.60f),
    separator = Color.White.copy(alpha = 0.10f),
    pressed = Color.White.copy(alpha = 0.10f),
)

private val LightMenu = IosMenuColors(
    dark = false,
    fill = Color(0xFFF6F6F7),
    rim = Color.Black.copy(alpha = 0.06f),
    label = Color(0xFF1A1A1A),
    secondaryLabel = Color(0xFF1A1A1A).copy(alpha = 0.55f),
    separator = Color.Black.copy(alpha = 0.09f),
    pressed = Color.Black.copy(alpha = 0.07f),
)

/** The panel colours for the theme in force. */
@Composable
fun iosMenuColors(): IosMenuColors =
    if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) LightMenu else DarkMenu

/** The panel's silhouette: a large, continuously curved corner. */
val IosMenuShape: Shape = RoundedCornerShape(30.dp).continuous()

/** [IosMenuShape]'s plain rounded-rect twin, for a shadow drawn while it scales. */
private val IosMenuShadowShape: Shape = RoundedCornerShape(30.dp)

/** Wide enough for a song-menu line on one line or two. */
val IosMenuWidth = 256.dp

/**
 * Shadow, slab and rim — the menu's material, without its contents.
 *
 * [translucent] lets what is behind show through the slab, as iOS menus do.
 * Something behind has to be blurred for that to read as frost rather than as
 * a page seen through tinted glass: [backdrop], a still of the page, or else
 * [hazeState], the page's own haze.
 */
@Composable
fun Modifier.iosMenuPanel(
    colors: IosMenuColors = iosMenuColors(),
    translucent: Boolean = false,
    backdrop: SnapshotBackdrop? = null,
    hazeState: HazeState? = null,
    /** False where the caller casts [iosMenuShadow] itself, apart from the panel. */
    castsShadow: Boolean = true,
): Modifier {
    val blurs = translucent
    val backdropFrost = blurs && backdrop != null && isGlassSupported()
    val hazeFrost = blurs && !backdropFrost && hazeState != null
    val fill = if (backdropFrost || hazeFrost) {
        // Thinner on a dark panel while its rows are blended, which takes them
        // further into the colour behind.
        colors.fill.copy(alpha = if (!colors.dark) 0.72f else 0.60f)
    } else {
        colors.fill
    }
    val shadowed = if (castsShadow) iosMenuShadow() else this
    val surface = if (hazeFrost) {
        val style = remember(colors, fill) { menuFrost(colors.fill, alpha = fill.alpha) }
        shadowed.clip(IosMenuShape).optimizedHazeEffect(state = hazeState, style = style)
    } else if (backdropFrost) {
        shadowed.menuBackdropFrost(backdrop, IosMenuShape, fill)
    } else {
        shadowed.clip(IosMenuShape).background(fill)
    }
    return surface
        .clip(IosMenuShape)
        .border(0.5.dp, colors.rim, IosMenuShape)
}

/** The shadow the menu panel casts. */
fun Modifier.iosMenuShadow(shape: Shape = IosMenuShape): Modifier = shadow(
    elevation = 24.dp,
    shape = shape,
    ambientColor = Color.Black.copy(alpha = 0.30f),
    spotColor = Color.Black.copy(alpha = 0.30f),
)

/**
 * The menu panel's frost drawn from a recorded [backdrop], in [shape] under
 * [fill]. The still stays put while the panel scales over it.
 */
fun Modifier.menuBackdropFrost(backdrop: SnapshotBackdrop, shape: Shape, fill: Color): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        // iOS menus carry the page's hue through more strongly than the page has it.
        colorControls(saturation = 1.8f)
        blur(MENU_BLUR.toPx() * MENU_BACKDROP_SCALE)
    },
    highlight = null,
    shadow = null,
    onDrawSurface = { drawRect(fill) },
    backdropScale = MENU_BACKDROP_SCALE,
)

private val MENU_BLUR = 24.dp
private const val MENU_BACKDROP_SCALE = 0.25f

/** The longest a menu waits for its frost's still before growing without it. */
private const val STILL_WAIT_MS = 150L

/** How much of the slab covers the frost when the blur is drawn from haze. */
private const val MENU_FROST_ALPHA = 0.80f

/**
 * The frost of the iOS menu panel: what is behind blurred well past legibility,
 * under [fill] at the alpha the panel's slab takes.
 */
fun menuFrost(fill: Color, alpha: Float = MENU_FROST_ALPHA): HazeStyle = HazeStyle(
    backgroundColor = fill,
    tints = listOf(HazeTint(fill.copy(alpha = alpha))),
    blurRadius = 24.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(fill),
)

/** Haze sampled at a third. The blur hides the upscale. */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.optimizedHazeEffect(
    state: HazeState,
    style: HazeStyle = HazeStyle.Unspecified,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier = hazeEffect(state, style) {
    inputScale = HazeInputScale.Fixed(0.33f)
    block?.invoke(this)
}

/**
 * Adds the content onto the backdrop, so the page's colour comes through the
 * glyph as a tinted white instead of the glyph covering it. Solid white stays
 * solid white under it: the effect is in content drawn below full strength.
 */
fun Modifier.additiveChrome(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            val layer = obtainGraphicsLayer().apply { blendMode = BlendMode.Plus }
            onDrawWithContent {
                layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(layer)
            }
        }
    }

/**
 * One line of the song menu: the glyph leading in a fixed column, so the
 * labels line up whether a row has one or not, then the label.
 *
 * Pressing paints a rounded fill under the row rather than a ripple.
 */
@Composable
fun IosMenuRow(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = iosMenuColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha = if (enabled) 1f else 0.4f
    val foreground = colors.label
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) colors.pressed else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .heightIn(min = 44.dp)
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = foreground.copy(alpha = foreground.alpha * alpha),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = foreground.copy(alpha = foreground.alpha * alpha),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                lineHeight = 21.sp,
                letterSpacing = (-0.3).sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                color = colors.secondaryLabel.copy(alpha = colors.secondaryLabel.alpha * alpha),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A menu hung off a button rather than lifted out of a list — every ⋮ in
 * BitChord opens its menu this way. The panel lines its trailing edge up with
 * [anchor]'s and grows out of the corner nearest the button: one in the upper
 * half hangs down from the button's top; one lower settles at the bottom.
 *
 * [item] is non-null while the menu is wanted. The last one is kept so the
 * panel can play its way out after the caller has already let go of it.
 */
@Composable
fun <T : Any> IosMenuPopover(
    item: T?,
    anchor: Rect?,
    onDismiss: () -> Unit,
    backdrop: SnapshotBackdrop? = null,
    hazeState: HazeState? = null,
    /** Sit under the button. The corner it grows from is the one lined up with the button. */
    dropBelow: Boolean = false,
    /** Line the panel's trailing edge up with the button and grow from the top right. */
    alignEnd: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf<Pair<T, Rect?>?>(null) }
    val requested = item?.let { it to anchor }
    if (requested != null && requested.first != last?.first) {
        SideEffect { last = requested }
    }
    val shown = requested ?: last ?: return
    val (menuItem, menuAnchor) = shown
    val open = item != null

    val reduceMotion = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            // The opening frame is the heaviest the menu has. Grown from that
            // frame, the spring jumps across it, so the panel waits, unseen.
            if (progress.value == 0f) {
                withFrameNanos { }
                withFrameNanos { }
                backdrop?.takeIf { isGlassSupported() }?.let { still ->
                    withTimeoutOrNull(STILL_WAIT_MS) { snapshotFlow { still.hasStill }.first { it } }
                }
            }
            progress.animateTo(
                1f,
                if (reduceMotion) {
                    tween(durationMillis = 120, easing = LinearEasing)
                } else {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)
                },
            )
        } else {
            progress.animateTo(0f, tween(durationMillis = if (reduceMotion) 100 else 170, easing = FastOutSlowInEasing))
            last = null
        }
    }

    BackHandler(enabled = open, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrim = Color.Black.copy(alpha = if (dark) 0.30f else 0.14f)
    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            .containMenuGestures(),
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .background(scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onDismiss,
                    ),
            )
            // The panel's shadow, cast by a plate of its own behind it: the
            // panel is drawn into a layer of its own size, which would cut a
            // shadow of its own off at its edges. A plain rounded rect, so the
            // GPU draws it straight off.
            Box(Modifier.iosMenuShadow(IosMenuShadowShape))
            Box(
                Modifier
                    .pointerInput(Unit) {}
                    .iosMenuPanel(
                        translucent = true,
                        backdrop = backdrop,
                        hazeState = hazeState,
                        castsShadow = false,
                    ),
                propagateMinConstraints = true,
            ) {
                // The rows in a layer of their own, so the frost is not
                // redrawn with every label on each frame of the growth. On a
                // dark panel that layer is added onto the frost.
                Column(
                    Modifier
                        .then(
                            if (dark) {
                                Modifier.additiveChrome(true)
                            } else {
                                Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            },
                        )
                        .padding(vertical = 8.dp),
                ) {
                    content(menuItem)
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 14.dp.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = height - insets.getBottom(this) - 12.dp.roundToPx()

        val scrimPlaceable = measurables[0].measure(Constraints.fixed(width, height))
        val panelWidth = IosMenuWidth.roundToPx().coerceAtMost(width - margin * 2)
        val panel = measurables[2].measure(
            Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = (bottom - top).coerceAtLeast(0)),
        )
        val shadowPlate = measurables[1].measure(Constraints.fixed(panel.width, panel.height))
        val anchorBounds = menuAnchor?.translate(-overlayOffset)
        val right = anchorBounds?.right?.roundToInt() ?: (width - margin)
        val lowest = (bottom - panel.height).coerceAtLeast(top)
        val hangsDown = anchorBounds != null && anchorBounds.center.y < height / 2f
        val gap = 6.dp.roundToPx()
        val maxX = (width - margin - panel.width).coerceAtLeast(margin)
        val panelX = if (dropBelow && anchorBounds != null && !alignEnd) {
            anchorBounds.left.roundToInt().coerceIn(margin, maxX)
        } else {
            (right - panel.width).coerceIn(margin, maxX)
        }
        val panelY = if (dropBelow && anchorBounds != null) {
            (anchorBounds.bottom.roundToInt() + gap).coerceIn(top, lowest)
        } else if (hangsDown) {
            anchorBounds.top.roundToInt().coerceIn(top, lowest)
        } else {
            lowest
        }

        val growth: GraphicsLayerScope.() -> Unit = {
            val p = progress.value
            alpha = (p * 2.5f).coerceIn(0f, 1f)
            if (!reduceMotion) {
                transformOrigin = if (dropBelow && !alignEnd) {
                    TransformOrigin(0f, 0f)
                } else {
                    TransformOrigin(1f, if (dropBelow || hangsDown) 0f else 1f)
                }
                val scale = lerp(0.3f, 1f, p)
                scaleX = scale
                scaleY = scale
            }
        }
        layout(width, height) {
            scrimPlaceable.place(0, 0)
            shadowPlate.placeWithLayer(panelX, panelY, layerBlock = growth)
            panel.placeWithLayer(panelX, panelY) {
                growth()
                // Drawn full size into a layer of its own, and only that
                // picture scaled. The continuous corners are rasterized once.
                compositingStrategy = CompositingStrategy.Offscreen
            }
        }
    }
}

/**
 * Keeps a drag that starts on the menu from scrolling the page under it.
 * Taps, which never cross the slop, still reach the scrim's dismiss.
 */
private fun Modifier.containMenuGestures(): Modifier =
    nestedScroll(SwallowMenuScroll).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            drag(start.id) { it.consume() }
        }
    }

private object SwallowMenuScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}
