package com.xcantech.music.wire

import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import com.xcantech.music.wire.MusicWire.Shortcut
import com.xcantech.music.wire.MusicWire.WirePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicWireTest {
    private val full = NowPlaying(
        link = Link.OK,
        playerId = "ma_kitchen",
        playerName = "Kitchen",
        title = "Añoranza — 夜",
        artist = "Someone",
        album = "An Album",
        playing = true,
        hasItem = true,
        volume = 37,
        muted = false,
        artKey = "9f3a",
        players = listOf(WirePlayer("ma_kitchen", "Kitchen", true), WirePlayer("ma_office", "Office", false)),
    )

    @Test
    fun stateRoundTrips() {
        assertEquals(full, MusicWire.decodeState(MusicWire.encode(full)))
    }

    @Test
    fun emptyStateRoundTripsForEveryLink() {
        Link.entries.forEach { link ->
            val state = NowPlaying(link = link)
            assertEquals(state, MusicWire.decodeState(MusicWire.encode(state)))
        }
    }

    @Test
    fun unknownVolumeSurvives() {
        val state = full.copy(volume = MusicWire.VOLUME_UNKNOWN)
        val decoded = MusicWire.decodeState(MusicWire.encode(state))
        assertEquals(MusicWire.VOLUME_UNKNOWN, decoded?.volume)
        assertFalse(decoded!!.hasVolume)
    }

    @Test
    fun outOfRangeVolumeIsClamped() {
        assertEquals(100, MusicWire.decodeState(MusicWire.encode(full.copy(volume = 250)))?.volume)
    }

    @Test
    fun longDisplayTextIsCutButIdsAreNot() {
        val longId = "player-" + "x".repeat(400)
        val decoded = MusicWire.decodeState(
            MusicWire.encode(full.copy(playerId = longId, title = "t".repeat(500))),
        )!!
        assertEquals(longId, decoded.playerId)
        assertEquals(120, decoded.title.length)
        assertTrue(decoded.title.endsWith("…"))
    }

    @Test
    fun anotherVersionOrGarbageDecodesToNull() {
        val bytes = MusicWire.encode(full)
        bytes[0] = (MusicWire.VERSION + 1).toByte()
        assertNull(MusicWire.decodeState(bytes))
        assertNull(MusicWire.decodeState(null))
        assertNull(MusicWire.decodeState(ByteArray(0)))
        assertNull(MusicWire.decodeState(byteArrayOf(MusicWire.VERSION.toByte(), 0, 0)))
        assertNull(MusicWire.decodeCommand(byteArrayOf(MusicWire.VERSION.toByte(), 99)))
    }

    @Test
    fun everyCommandRoundTrips() {
        val commands = listOf(
            Command.Toggle,
            Command.Next,
            Command.Previous,
            Command.VolumeStep(up = true),
            Command.VolumeStep(up = false),
            Command.VolumeSet(0),
            Command.VolumeSet(64),
            Command.VolumeSet(100),
            Command.SelectPlayer("ma_office"),
            Command.Play("library://playlist/12"),
            Command.Refresh,
        )
        commands.forEach { assertEquals(it, MusicWire.decodeCommand(MusicWire.encode(it))) }
    }

    @Test
    fun volumeSetIsClampedOnTheWire() {
        assertEquals(Command.VolumeSet(100), MusicWire.decodeCommand(MusicWire.encode(Command.VolumeSet(400))))
        assertEquals(Command.VolumeSet(0), MusicWire.decodeCommand(MusicWire.encode(Command.VolumeSet(-5))))
    }

    @Test
    fun shortcutsRoundTripAndAreCapped() {
        val list = List(40) { Shortcut("library://album/$it", "Album $it", "Artist") }
        val decoded = MusicWire.decodeShortcuts(MusicWire.encode(list))!!
        assertEquals(24, decoded.size)
        assertEquals(list.take(24), decoded)
        assertEquals(emptyList<Shortcut>(), MusicWire.decodeShortcuts(MusicWire.encode(emptyList<Shortcut>())))
    }

    @Test
    fun headlineJoinsTitleAndArtist() {
        assertEquals("Añoranza — 夜 · Someone", full.headline)
        assertEquals("Only", NowPlaying(Link.OK, title = "Only").headline)
        assertEquals("", NowPlaying(Link.OK).headline)
    }

    @Test
    fun dialTurnsPixelsIntoStepsAndKeepsTheRemainder() {
        val dial = VolumeDial(pixelsPerStep = 20f)
        assertEquals(50, dial.turn(50, 15f))
        assertEquals(51, dial.turn(50, 10f))
        assertEquals(48, dial.turn(51, -65f))
        assertEquals(100, dial.turn(99, 400f))
        assertEquals(0, dial.turn(1, -400f))
    }

    @Test
    fun dialSendsAtMostOnePerWindowAndFlushesTheLastLevel() {
        val dial = VolumeDial(sendEveryMs = 250)
        assertTrue(dial.shouldSend(40, nowMs = 1_000))
        assertFalse(dial.shouldSend(41, nowMs = 1_100))
        assertFalse(dial.shouldSend(42, nowMs = 1_200))
        assertTrue(dial.shouldSend(43, nowMs = 1_260))
        assertFalse(dial.shouldSend(43, nowMs = 2_000))
        assertFalse(dial.shouldSend(44, nowMs = 1_300))
        assertEquals(44, dial.flush(44, nowMs = 1_400))
        assertNull(dial.flush(44, nowMs = 1_500))
    }
}
