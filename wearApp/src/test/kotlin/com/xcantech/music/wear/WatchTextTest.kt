package com.xcantech.music.wear

import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchTextTest {
    private val playing =
        NowPlaying(Link.OK, playerId = "k", playerName = "Kitchen", title = "Song", artist = "Artist", playing = true)

    @Test
    fun aLivePhoneShowsTheTrack() {
        assertEquals("Song", titleText(playing))
        assertEquals("Artist", statusText(playing, PhoneLink.OK))
        assertEquals("Artist", statusText(playing, PhoneLink.UNKNOWN))
    }

    @Test
    fun thePhonesTroubleComesBeforeTheTrack() {
        assertEquals("Phone not reachable", statusText(playing, PhoneLink.UNREACHABLE))
        assertEquals("Update Music on phone and watch", statusText(playing, PhoneLink.OUTDATED))
        assertEquals("Song", titleText(playing))
    }

    @Test
    fun theServersTroubleIsNamed() {
        assertEquals("Open Music on the phone", statusText(NowPlaying(Link.SIGNED_OUT), PhoneLink.OK))
        assertEquals("Phone can't reach the server", statusText(playing.copy(link = Link.OFFLINE), PhoneLink.OK))
        assertEquals("Connecting…", statusText(NowPlaying(Link.CONNECTING), PhoneLink.OK))
        assertEquals("Artist", statusText(playing.copy(link = Link.CONNECTING), PhoneLink.OK))
        assertEquals("Artist", statusText(playing.copy(link = Link.ASLEEP), PhoneLink.OK))
    }

    @Test
    fun nothingFromThePhoneYet() {
        assertEquals("Music", titleText(null))
        assertEquals("Waiting for the phone", statusText(null, PhoneLink.UNKNOWN))
        assertEquals("Nothing playing", titleText(NowPlaying(Link.OK, playerId = "k", playerName = "Kitchen")))
    }
}
