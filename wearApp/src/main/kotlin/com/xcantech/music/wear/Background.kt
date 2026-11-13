package com.xcantech.music.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.NowPlaying

/* ---- tile: what plays and the three transport buttons, one swipe from the watch face ---- */

class NowPlayingTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Store.init(this)
        // A tap on a tile button reloads the tile with the button's id: send it, then draw.
        when (requestParams.currentState.lastClickableId) {
            ID_TOGGLE -> Store.send(Command.Toggle) { it.copy(playing = !it.playing) }
            ID_NEXT -> Store.send(Command.Next)
            ID_PREVIOUS -> Store.send(Command.Previous)
        }
        val state = Store.state
        val status = statusText(state, Store.phone)
        val column = LayoutElementBuilders.Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(titleText(state), TITLE_SP, TEXT, bold = true))
        if (status.isNotEmpty()) column.addContent(text(status, STATUS_SP, DIM))
        column.addContent(spacer(GAP_DP))
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(button(ID_PREVIOUS, RES_PREVIOUS, SMALL_DP, filled = false))
                    .addContent(spacerWidth(GAP_DP))
                    .addContent(
                        button(ID_TOGGLE, if (state?.playing == true) RES_PAUSE else RES_PLAY, LARGE_DP, filled = true),
                    )
                    .addContent(spacerWidth(GAP_DP))
                    .addContent(button(ID_NEXT, RES_NEXT, SMALL_DP, filled = false))
                    .build(),
            )
        state?.playerName?.takeIf { it.isNotEmpty() }?.let {
            column.addContent(spacer(GAP_DP)).addContent(text(it, STATUS_SP, ACCENT, bold = true))
        }
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name).build(),
            ).build(),
        ).build()
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(open)
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(EDGE_DP)).setEnd(dp(EDGE_DP)).build())
                    .build(),
            )
            .addContent(column.build()).build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        fun image(id: Int) = ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build(),
        ).build()
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(RES_PLAY, image(R.drawable.ic_play))
                .addIdToImageMapping(RES_PAUSE, image(R.drawable.ic_pause))
                .addIdToImageMapping(RES_NEXT, image(R.drawable.ic_next))
                .addIdToImageMapping(RES_PREVIOUS, image(R.drawable.ic_previous))
                .build(),
        )
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                    )
                    .build(),
            ).build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()
    private fun spacerWidth(width: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).build()

    private fun button(id: String, resource: String, size: Float, filled: Boolean): LayoutElementBuilders.LayoutElement {
        val modifiers = ModifiersBuilders.Modifiers.Builder().setClickable(
            ModifiersBuilders.Clickable.Builder().setId(
                id,
            ).setOnClick(ActionBuilders.LoadAction.Builder().build()).build(),
        )
        if (filled) {
            modifiers.setBackground(
                ModifiersBuilders.Background.Builder().setColor(argb(ACCENT))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build()).build(),
            )
        }
        return LayoutElementBuilders.Box.Builder().setWidth(dp(size)).setHeight(dp(size))
            .setModifiers(modifiers.build())
            .addContent(
                LayoutElementBuilders.Image.Builder().setResourceId(resource)
                    .setWidth(dp(size * ICON_FRACTION)).setHeight(dp(size * ICON_FRACTION))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder().setTint(
                            argb(if (filled) ON_ACCENT else TEXT),
                        ).build(),
                    ).build(),
            ).build()
    }

    private companion object {
        const val ID_TOGGLE = "toggle"
        const val ID_NEXT = "next"
        const val ID_PREVIOUS = "previous"
        const val RES_PLAY = "play"
        const val RES_PAUSE = "pause"
        const val RES_NEXT = "next"
        const val RES_PREVIOUS = "previous"
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 15 * 60_000L
        const val TITLE_SP = 16f
        const val STATUS_SP = 13f
        const val GAP_DP = 8f
        const val EDGE_DP = 24f
        const val SMALL_DP = 40f
        const val LARGE_DP = 52f
        const val ICON_FRACTION = 0.55f
        const val TEXT = 0xFFF5F5F7.toInt()
        const val DIM = 0xFFB4AEC4.toInt()
        const val ACCENT = 0xFFB9A8FF.toInt()
        const val ON_ACCENT = 0xFF20124F.toInt()
    }
}

/* ---- complication: the track on the watch face; a tap opens the controls ---- */

class NowPlayingComplication : SuspendingComplicationDataSourceService() {
    private fun data(type: ComplicationType, state: NowPlaying?): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = titleText(state)
        val artist = state?.artist.orEmpty()
        val description = PlainComplicationText.Builder(
            listOf("Music", title, artist).filter { it.isNotEmpty() }.distinct().joinToString(", "),
        ).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_stat)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(if (state?.playing == true) "Playing" else "Music").build(),
                description,
            ).setMonochromaticImage(icon).setTapAction(tap).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(title).build(),
                description,
            )
                .apply { if (artist.isNotEmpty()) setTitle(PlainComplicationText.Builder(artist).build()) }
                .setMonochromaticImage(icon).setTapAction(tap).build()

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(
        type,
        NowPlaying(
            link = com.xcantech.music.wire.MusicWire.Link.OK,
            title = "Song title",
            artist = "Artist",
            playing = true,
        ),
    )

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, Store.current(this))
}
