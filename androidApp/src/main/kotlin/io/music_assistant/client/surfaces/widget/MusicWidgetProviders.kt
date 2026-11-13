package io.music_assistant.client.surfaces.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.music_assistant.client.surfaces.SurfaceHub
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Music fork (MUSIC.md). The providers hold no state: [SurfaceHub] pushes every change. A system
 * update (widget added, phone restarted, the 30-minute period) only asks the hub to wake the
 * session and draw the snapshot it has.
 */
abstract class MusicWidgetProvider : AppWidgetProvider(), KoinComponent {
    private val hub: SurfaceHub by inject()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        hub.wake()
        hub.renderNow()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        hub.renderNow()
    }
}

/** Artwork, title, artist, player and controls for the selected player. */
class NowPlayingWidgetProvider : MusicWidgetProvider()

/** One row of controls. */
class ControlsWidgetProvider : MusicWidgetProvider()
