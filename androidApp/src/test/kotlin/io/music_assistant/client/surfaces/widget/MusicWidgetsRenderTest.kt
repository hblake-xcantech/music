package io.music_assistant.client.surfaces.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import io.music_assistant.client.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Inflates every widget layout at every declared size: a missing id or a bad attribute fails here, not on the home screen. */
@RunWith(RobolectricTestRunner::class)
class MusicWidgetsRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val widgets = MusicWidgets(context)
    private val playing = NowPlaying(
        link = Link.OK,
        playerId = "kitchen",
        playerName = "Kitchen",
        title = "Song",
        artist = "Artist",
        playing = true,
        hasItem = true,
        volume = 40,
    )

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    // The one-row layout has no volume buttons at all; RemoteViews skips an id a layout does not have.
    private fun View.visible(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE

    @Test
    fun nowPlayingInflatesAtEverySizeAndShowsTheTrack() {
        val art = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        NOW_PLAYING_SIZES.forEach { (w, h) ->
            val view = widgets.nowPlaying(playing, art, w, h).apply(context, FrameLayout(context))
            assertEquals("Song", view.text(R.id.music_title))
            assertEquals("Artist", view.text(R.id.music_subtitle))
            assertEquals("Kitchen", view.text(R.id.music_player))
            assertEquals(nowPlayingSpec(w, h).showVolume, view.visible(R.id.music_vol_up))
            assertEquals(nowPlayingSpec(w, h).showPrevious, view.visible(R.id.music_prev))
        }
    }

    @Test
    fun controlsInflateAndHideVolumeForAPlayerWithoutIt() {
        CONTROLS_SIZES.forEach { (w, _) ->
            val view = widgets.controls(playing, w).apply(context, FrameLayout(context))
            assertEquals("Song · Artist", view.text(R.id.music_title))
            assertEquals(controlsSpec(w).showVolume, view.visible(R.id.music_vol_down))
            val noVolume = widgets.controls(playing.copy(volume = -1), w).apply(context, FrameLayout(context))
            assertEquals(false, noVolume.visible(R.id.music_vol_down))
        }
    }

    @Test
    fun theResponsiveSetAndroid12PicksFromBuildsAndInflates() {
        val art = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val nowPlaying = widgets.responsive(NOW_PLAYING_SIZES) { w, h -> widgets.nowPlaying(playing, art, w, h) }
        assertEquals("Song", nowPlaying.apply(context, FrameLayout(context)).text(R.id.music_title))
        val controls = widgets.responsive(CONTROLS_SIZES) { w, _ -> widgets.controls(playing, w) }
        assertEquals("Kitchen", controls.apply(context, FrameLayout(context)).text(R.id.music_player))
    }

    @Test
    fun signedOutPointsAtTheApp() {
        val view = widgets.nowPlaying(NowPlaying(Link.SIGNED_OUT), null, 300, 200).apply(context, FrameLayout(context))
        assertEquals("Music", view.text(R.id.music_title))
        assertEquals("Open Music to sign in", view.text(R.id.music_subtitle))
        assertEquals(false, view.visible(R.id.music_player))
    }
}
