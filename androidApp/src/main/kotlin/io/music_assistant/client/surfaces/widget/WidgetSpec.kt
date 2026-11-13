package io.music_assistant.client.surfaces.widget

/**
 * Music fork (MUSIC.md): which layout and which parts a widget shows at a size. Pure, so the
 * breakpoints are unit-tested. Sizes are in dp, as the launcher reports them.
 */
internal enum class NowPlayingLayout { ROW, CARD, TALL }

internal data class NowPlayingSpec(
    val layout: NowPlayingLayout,
    val showArt: Boolean,
    val showText: Boolean,
    val showPrevious: Boolean,
    val showVolume: Boolean,
    /** Width of the artwork in [NowPlayingLayout.CARD]; 0 elsewhere. */
    val artWidthDp: Int = 0,
)

internal data class ControlsSpec(
    val showText: Boolean,
    val showPrevious: Boolean,
    val showVolume: Boolean,
)

private const val ROW_MAX_HEIGHT = 120
private const val CARD_MIN_WIDTH = 250
private const val CARD_MAX_HEIGHT = 260
private const val CARD_PADDING = 24
private const val CARD_ART_MAX = 150
private const val CARD_ART_SHARE = 0.4f

internal fun nowPlayingSpec(widthDp: Int, heightDp: Int): NowPlayingSpec = when {
    heightDp < ROW_MAX_HEIGHT -> NowPlayingSpec(
        layout = NowPlayingLayout.ROW,
        showArt = widthDp >= 200,
        showText = widthDp >= 180,
        showPrevious = widthDp >= 250 || widthDp < 180,
        showVolume = false,
    )

    widthDp >= CARD_MIN_WIDTH && heightDp < CARD_MAX_HEIGHT -> NowPlayingSpec(
        layout = NowPlayingLayout.CARD,
        showArt = true,
        showText = true,
        showPrevious = true,
        showVolume = widthDp >= 280,
        artWidthDp = minOf(heightDp - CARD_PADDING, CARD_ART_MAX, (widthDp * CARD_ART_SHARE).toInt()),
    )

    else -> NowPlayingSpec(
        layout = NowPlayingLayout.TALL,
        showArt = true,
        showText = true,
        showPrevious = true,
        showVolume = widthDp >= 200,
    )
}

internal fun controlsSpec(widthDp: Int): ControlsSpec = ControlsSpec(
    showText = widthDp >= 230,
    showPrevious = true,
    showVolume = widthDp >= 290 || widthDp in 190 until 230,
)

/** The sizes each widget is drawn for on Android 12+; the launcher shows the largest that fits. */
internal val NOW_PLAYING_SIZES = listOf(
    110 to 56, 180 to 56, 200 to 56, 250 to 56,
    110 to 120, 200 to 120, 250 to 120, 280 to 120,
    200 to 260, 280 to 260,
)

internal val CONTROLS_SIZES = listOf(110 to 48, 190 to 48, 230 to 48, 290 to 48)
