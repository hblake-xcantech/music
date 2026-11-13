package io.music_assistant.client.surfaces

import com.xcantech.music.wire.MusicWire
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import io.music_assistant.client.utils.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMapperTest {
    private val kitchen = PlayerView(
        id = "kitchen",
        name = "Kitchen",
        title = "Song",
        artist = "Artist",
        album = "Album",
        imageUrl = "http://server/art/1",
        playing = true,
        hasItem = true,
        volume = 30,
    )
    private val office = PlayerView(id = "office", name = "Office")

    @Test
    fun selectedPlayerWinsOverThePlayingOne() {
        val state = buildNowPlaying(Link.OK, listOf(kitchen, office), selectedId = "office", previous = null)
        assertEquals("office", state.playerId)
        assertEquals("Office", state.playerName)
        assertFalse(state.playing)
        assertFalse(state.hasItem)
        assertEquals(MusicWire.VOLUME_UNKNOWN, state.volume)
        assertEquals("", state.artKey)
        assertEquals(listOf("kitchen" to true, "office" to false), state.players.map { it.id to it.playing })
    }

    @Test
    fun withoutASelectionThePlayingPlayerIsShownElseTheFirst() {
        assertEquals("kitchen", buildNowPlaying(Link.OK, listOf(office, kitchen), null, null).playerId)
        assertEquals(
            "office",
            buildNowPlaying(Link.OK, listOf(office, kitchen.copy(playing = false)), "gone", null).playerId,
        )
    }

    @Test
    fun trackAndVolumeAreCarried() {
        val state = buildNowPlaying(Link.OK, listOf(kitchen), "kitchen", null)
        assertEquals("Song", state.title)
        assertEquals("Artist", state.artist)
        assertEquals("Album", state.album)
        assertTrue(state.playing)
        assertEquals(30, state.volume)
        assertTrue(state.artKey.isNotEmpty())
        assertNotEquals(
            state.artKey,
            buildNowPlaying(Link.OK, listOf(kitchen.copy(imageUrl = "http://server/art/2")), "kitchen", null).artKey,
        )
    }

    @Test
    fun aColdProcessKeepsTheLastSnapshotUnderTheNewLink() {
        val last = buildNowPlaying(Link.OK, listOf(kitchen), "kitchen", null)
        assertEquals(last.copy(link = Link.CONNECTING), buildNowPlaying(Link.CONNECTING, emptyList(), "kitchen", last))
        assertEquals(last.copy(link = Link.OFFLINE), buildNowPlaying(Link.OFFLINE, emptyList(), null, last))
        assertEquals(NowPlaying(Link.CONNECTING), buildNowPlaying(Link.CONNECTING, emptyList(), null, null))
    }

    @Test
    fun aLiveSessionWithoutPlayersDoesNotShowTheOldSnapshot() {
        val last = buildNowPlaying(Link.OK, listOf(kitchen), "kitchen", null)
        assertEquals(NowPlaying(Link.OK), buildNowPlaying(Link.OK, emptyList(), "kitchen", last))
    }

    @Test
    fun signedOutShowsNothingToControl() {
        val last = buildNowPlaying(Link.OK, listOf(kitchen), "kitchen", null)
        val state = buildNowPlaying(Link.SIGNED_OUT, listOf(kitchen), "kitchen", last)
        assertEquals(NowPlaying(Link.SIGNED_OUT), state)
        assertEquals("Music", titleLine(state))
        assertEquals("Open Music to sign in", statusLine(state))
    }

    @Test
    fun linesSayWhatIsGoingOn() {
        val live = buildNowPlaying(Link.OK, listOf(kitchen), "kitchen", null)
        assertEquals("Song", titleLine(live))
        assertEquals("Artist", statusLine(live))
        val idle = buildNowPlaying(Link.OK, listOf(office), "office", null)
        assertEquals("Nothing playing", titleLine(idle))
        assertEquals("", statusLine(idle))
        assertEquals("Connecting…", statusLine(NowPlaying(Link.CONNECTING)))
        assertEquals("Server not reachable", statusLine(idle.copy(link = Link.OFFLINE)))
        assertEquals("Artist", statusLine(live.copy(link = Link.OFFLINE)))
    }

    @Test
    fun disconnectedSessionStatesMapToLinks() {
        assertEquals(Link.CONNECTING, linkOf(SessionState.Disconnected.Initial, ready = false, hasSavedToken = false))
        assertEquals(Link.CONNECTING, linkOf(SessionState.Connecting, ready = false, hasSavedToken = false))
        assertEquals(Link.ASLEEP, linkOf(SessionState.Disconnected.Backgrounded, ready = false, hasSavedToken = true))
        assertEquals(Link.OFFLINE, linkOf(SessionState.Disconnected.Error(null), ready = false, hasSavedToken = true))
        assertEquals(
            Link.SIGNED_OUT,
            linkOf(SessionState.Disconnected.NoServerData, ready = false, hasSavedToken = false),
        )
        assertEquals(Link.SIGNED_OUT, linkOf(SessionState.Disconnected.ByUser, ready = false, hasSavedToken = true))
    }
}
