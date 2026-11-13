package io.music_assistant.client.surfaces.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import io.music_assistant.client.MainActivity
import io.music_assistant.client.R
import io.music_assistant.client.surfaces.SurfaceHub
import io.music_assistant.client.surfaces.statusLine
import io.music_assistant.client.surfaces.titleLine

/**
 * Music fork (MUSIC.md): draws the home-screen widgets from the [SurfaceHub] snapshot.
 *
 * RemoteViews, not Glance: the app's Compose comes from Compose Multiplatform, and Glance would
 * add a second Compose runtime consumer that must be kept in step with it on every upstream
 * rebase. Plain layouts have no such coupling and nothing here needs more than they offer.
 */
class MusicWidgets(private val context: Context) : SurfaceHub.Surface {
    private val manager: AppWidgetManager? get() = AppWidgetManager.getInstance(context)

    override fun show(state: NowPlaying, art: Bitmap?) {
        val manager = manager ?: return
        manager.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java)).forEach { id ->
            manager.updateAppWidget(id, sized(manager, id, NOW_PLAYING_SIZES) { w, h -> nowPlaying(state, art, w, h) })
        }
        manager.getAppWidgetIds(ComponentName(context, ControlsWidgetProvider::class.java)).forEach { id ->
            manager.updateAppWidget(id, sized(manager, id, CONTROLS_SIZES) { w, _ -> controls(state, w) })
        }
    }

    /** Android 12+ gets one layout per size and picks; before that, the widget's current size decides. */
    private fun sized(
        manager: AppWidgetManager,
        id: Int,
        sizes: List<Pair<Int, Int>>,
        build: (widthDp: Int, heightDp: Int) -> RemoteViews,
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return responsive(sizes, build)
        val options = manager.getAppWidgetOptions(id)
        return build(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_HEIGHT_DP),
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun responsive(
        sizes: List<Pair<Int, Int>>,
        build: (widthDp: Int, heightDp: Int) -> RemoteViews,
    ) = RemoteViews(sizes.associate { (w, h) -> SizeF(w.toFloat(), h.toFloat()) to build(w, h) })

    internal fun nowPlaying(state: NowPlaying, art: Bitmap?, widthDp: Int, heightDp: Int): RemoteViews {
        val spec = nowPlayingSpec(widthDp, heightDp)
        val views = RemoteViews(
            context.packageName,
            when (spec.layout) {
                NowPlayingLayout.ROW -> R.layout.music_widget_np_row
                NowPlayingLayout.CARD -> R.layout.music_widget_np_card
                NowPlayingLayout.TALL -> R.layout.music_widget_np_tall
            },
        )
        bindCommon(
            views,
            state,
            showText = spec.showText,
            showPrevious = spec.showPrevious,
            showVolume = spec.showVolume,
        )
        views.setTextViewText(R.id.music_subtitle, statusLine(state))
        views.setViewVisibility(R.id.music_subtitle, visibleIf(statusLine(state).isNotEmpty()))
        views.setViewVisibility(R.id.music_art, visibleIf(spec.showArt))
        art?.takeIf { state.title.isNotEmpty() && state.link != Link.SIGNED_OUT }
            ?.let { views.setImageViewBitmap(R.id.music_art, it) }
            ?: views.setImageViewResource(R.id.music_art, R.drawable.music_art_placeholder)
        if (spec.artWidthDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(R.id.music_art, spec.artWidthDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }
        views.setOnClickPendingIntent(R.id.music_art, openApp())
        return views
    }

    internal fun controls(state: NowPlaying, widthDp: Int): RemoteViews {
        val spec = controlsSpec(widthDp)
        val views = RemoteViews(context.packageName, R.layout.music_widget_controls)
        bindCommon(
            views,
            state,
            showText = spec.showText,
            showPrevious = spec.showPrevious,
            showVolume = spec.showVolume,
        )
        val line = state.headline.ifEmpty { statusLine(state).ifEmpty { titleLine(state) } }
        views.setTextViewText(R.id.music_title, line)
        return views
    }

    private fun bindCommon(
        views: RemoteViews,
        state: NowPlaying,
        showText: Boolean,
        showPrevious: Boolean,
        showVolume: Boolean,
    ) {
        val signedOut = state.link == Link.SIGNED_OUT
        views.setTextViewText(R.id.music_title, titleLine(state))
        views.setViewVisibility(R.id.music_text, visibleIf(showText))
        views.setTextViewText(
            R.id.music_player,
            state.playerName.ifEmpty { context.getString(R.string.music_widget_choose_player) },
        )
        views.setViewVisibility(R.id.music_player, visibleIf(!signedOut))
        views.setImageViewResource(
            R.id.music_play,
            if (state.playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
        )
        views.setViewVisibility(R.id.music_prev, visibleIf(showPrevious))
        views.setViewVisibility(R.id.music_vol_down, visibleIf(showVolume && state.hasVolume))
        views.setViewVisibility(R.id.music_vol_up, visibleIf(showVolume && state.hasVolume))

        // Signed out there is nothing to control: every tap opens the app, where the sign-in is.
        fun action(name: String) = if (signedOut) openApp() else WidgetActionReceiver.pendingIntent(context, name)
        views.setOnClickPendingIntent(android.R.id.background, openApp())
        views.setOnClickPendingIntent(R.id.music_text, openApp())
        views.setOnClickPendingIntent(R.id.music_player, if (signedOut) openApp() else choosePlayer())
        views.setOnClickPendingIntent(R.id.music_play, action(WidgetActionReceiver.ACTION_TOGGLE))
        views.setOnClickPendingIntent(R.id.music_prev, action(WidgetActionReceiver.ACTION_PREVIOUS))
        views.setOnClickPendingIntent(R.id.music_next, action(WidgetActionReceiver.ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.music_vol_down, action(WidgetActionReceiver.ACTION_VOLUME_DOWN))
        views.setOnClickPendingIntent(R.id.music_vol_up, action(WidgetActionReceiver.ACTION_VOLUME_UP))
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun choosePlayer(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CHOOSE_PLAYER,
        Intent(context, PlayerPickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_OPEN_APP = 7101
        const val REQUEST_CHOOSE_PLAYER = 7102
        const val DEFAULT_WIDTH_DP = 250
        const val DEFAULT_HEIGHT_DP = 110
    }
}
