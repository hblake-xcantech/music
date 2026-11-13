package io.music_assistant.client.surfaces

import com.xcantech.music.wire.MusicWire
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import com.xcantech.music.wire.MusicWire.WirePlayer
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.utils.AuthProcessState
import io.music_assistant.client.utils.SessionState
import kotlin.math.roundToInt

/**
 * Music fork (MUSIC.md): the one snapshot every surface outside the app UI shows — the
 * home-screen widgets and the watch. Kept free of Android types so the mapping is unit-tested.
 */
internal data class PlayerView(
    val id: String,
    val name: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val imageUrl: String? = null,
    val playing: Boolean = false,
    val hasItem: Boolean = false,
    /** 0..100, null when the player has no usable volume control. */
    val volume: Int? = null,
    val muted: Boolean = false,
)

internal fun PlayerData.toView() = PlayerView(
    id = playerId,
    name = player.nameAndSuffix,
    title = player.currentMedia?.title,
    artist = player.currentMedia?.artist,
    album = player.currentMedia?.album,
    imageUrl = player.currentMedia?.imageUrl,
    playing = player.isPlaying,
    hasItem = queueInfo?.currentItem != null || player.currentMedia != null,
    volume = player.currentVolume
        ?.takeIf { player.isVolumeSliderAccessible }
        ?.roundToInt()?.coerceIn(0, 100),
    muted = player.currentMuteState,
)

/**
 * Where the phone stands with the server. [ready] is `ServiceClient.isReadyForCommands`;
 * [hasSavedToken] tells a sign-in that is about to happen by itself from one only the user can do.
 */
internal fun linkOf(state: SessionState, ready: Boolean, hasSavedToken: Boolean): Link = when (state) {
    is SessionState.Connected -> when {
        ready -> Link.OK
        else -> when (state.connectionData.authProcessState) {
            AuthProcessState.InProgress -> Link.CONNECTING
            AuthProcessState.NotStarted -> if (hasSavedToken || state.connectionData.serverInfo == null) {
                Link.CONNECTING
            } else {
                Link.SIGNED_OUT
            }

            AuthProcessState.LoggedOut, is AuthProcessState.Failed -> Link.SIGNED_OUT
        }
    }

    SessionState.Connecting, is SessionState.Reconnecting, SessionState.Disconnected.Initial -> Link.CONNECTING
    SessionState.Disconnected.Backgrounded -> Link.ASLEEP
    is SessionState.Disconnected.Error -> Link.OFFLINE
    SessionState.Disconnected.ByUser, SessionState.Disconnected.NoServerData -> Link.SIGNED_OUT
}

/** The player the surfaces control: the app's selected one, else one that plays, else the first. */
internal fun pickPlayer(players: List<PlayerView>, selectedId: String?): PlayerView? =
    players.firstOrNull { it.id == selectedId }
        ?: players.firstOrNull { it.playing }
        ?: players.firstOrNull()

/**
 * [previous] is the last snapshot built from live data. While the link is not [Link.OK] and the
 * app holds no players (a cold process that is still connecting), the surfaces keep showing it
 * under the new link instead of going blank. Signed out shows nothing: there is nothing to control.
 */
internal fun buildNowPlaying(
    link: Link,
    players: List<PlayerView>,
    selectedId: String?,
    previous: NowPlaying?,
): NowPlaying {
    if (link == Link.SIGNED_OUT) return NowPlaying(link)
    val player = pickPlayer(players, selectedId)
        ?: return previous?.takeIf { link != Link.OK }?.copy(link = link) ?: NowPlaying(link)
    return NowPlaying(
        link = link,
        playerId = player.id,
        playerName = player.name,
        title = player.title.orEmpty(),
        artist = player.artist.orEmpty(),
        album = player.album.orEmpty(),
        playing = player.playing,
        hasItem = player.hasItem,
        volume = player.volume ?: MusicWire.VOLUME_UNKNOWN,
        muted = player.muted,
        artKey = player.imageUrl?.hashCode()?.toUInt()?.toString(radix = 16).orEmpty(),
        players = players.map { WirePlayer(it.id, it.name, it.playing) },
    )
}

/** The line under the title on a widget or the watch when there is no track to name. */
internal fun statusLine(state: NowPlaying): String = when (state.link) {
    Link.SIGNED_OUT -> "Open Music to sign in"
    Link.CONNECTING -> if (state.hasPlayer) state.artist else "Connecting…"
    Link.OFFLINE -> if (state.hasPlayer && state.artist.isNotEmpty()) state.artist else "Server not reachable"
    Link.OK, Link.ASLEEP -> state.artist
}

internal fun titleLine(state: NowPlaying): String = when {
    state.title.isNotEmpty() -> state.title
    state.link == Link.SIGNED_OUT -> "Music"
    state.hasPlayer -> "Nothing playing"
    else -> "Music"
}
