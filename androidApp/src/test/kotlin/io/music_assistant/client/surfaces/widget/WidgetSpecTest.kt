package io.music_assistant.client.surfaces.widget

import com.xcantech.music.wire.MusicWire.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSpecTest {
    @Test
    fun oneRowHighIsTheRowLayoutAndShedsPartsAsItNarrows() {
        val wide = nowPlayingSpec(300, 90)
        assertEquals(NowPlayingLayout.ROW, wide.layout)
        assertTrue(wide.showArt && wide.showText && wide.showPrevious)
        assertFalse(wide.showVolume)

        val medium = nowPlayingSpec(210, 90)
        assertTrue(medium.showArt && medium.showText)
        assertFalse(medium.showPrevious)

        val narrow = nowPlayingSpec(130, 90)
        assertFalse(narrow.showArt || narrow.showText)
        assertTrue(narrow.showPrevious)
    }

    @Test
    fun wideAndTwoRowsHighIsTheCardWithSquareArtThatFits() {
        val card = nowPlayingSpec(300, 200)
        assertEquals(NowPlayingLayout.CARD, card.layout)
        assertTrue(card.showVolume)
        assertEquals(120, card.artWidthDp)
        assertEquals(96, nowPlayingSpec(260, 120).artWidthDp)
        assertFalse(nowPlayingSpec(260, 120).showVolume)
    }

    @Test
    fun narrowOrVeryTallPutsTheArtworkOnTop() {
        assertEquals(NowPlayingLayout.TALL, nowPlayingSpec(150, 200).layout)
        assertFalse(nowPlayingSpec(150, 200).showVolume)
        assertEquals(NowPlayingLayout.TALL, nowPlayingSpec(300, 320).layout)
        assertTrue(nowPlayingSpec(300, 320).showVolume)
    }

    @Test
    fun everyDeclaredSizeHasASpec() {
        NOW_PLAYING_SIZES.forEach { (w, h) -> nowPlayingSpec(w, h) }
        assertEquals(listOf(false, false, true, true), CONTROLS_SIZES.map { (w, _) -> controlsSpec(w).showText })
        assertEquals(listOf(false, true, false, true), CONTROLS_SIZES.map { (w, _) -> controlsSpec(w).showVolume })
    }

    @Test
    fun widgetActionsMapToCommands() {
        assertEquals(Command.Toggle, WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_TOGGLE))
        assertEquals(Command.Next, WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_NEXT))
        assertEquals(Command.Previous, WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_PREVIOUS))
        assertEquals(Command.VolumeStep(true), WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_VOLUME_UP))
        assertEquals(
            Command.VolumeStep(false),
            WidgetActionReceiver.commandFor(WidgetActionReceiver.ACTION_VOLUME_DOWN),
        )
        assertNull(WidgetActionReceiver.commandFor("something.else"))
        assertNull(WidgetActionReceiver.commandFor(null))
    }
}
