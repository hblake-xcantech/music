package com.xcantech.music.wire

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The wire between the Music phone app and the Music watch app over the Wear OS Data Layer
 * (Bluetooth when the watch is near the phone; no internet, Tailscale or token on the watch).
 *
 * Compiled into BOTH apps (`:androidApp` and `:wearApp` add `wire/src` as a source directory),
 * so the two ends cannot drift. Pure JVM: no Android imports, so it is unit-tested on the Mac
 * (`wire/test`). Why (Blake, Nov 12 2026): "Set it up on the pixel and pixel watch."
 *
 * Phone -> watch: two DataItems, which reach the watch even while its app is closed (tile,
 * complication): [STATE] (a [NowPlaying]; the artwork rides along as the asset [KEY_ART]) and
 * [SHORTCUTS] (things to start).
 * Watch -> phone: one request per tap on [COMMAND] carrying a [Command]; the reply is the
 * phone's [NowPlaying] after it accepted the command.
 *
 * Every payload starts with [VERSION]. A reader that meets another version returns null and
 * shows "update the other app" instead of guessing.
 */
object MusicWire {
    const val VERSION = 1

    const val STATE = "/music/state"
    const val SHORTCUTS = "/music/shortcuts"
    const val COMMAND = "/music/cmd"
    const val PATH_PREFIX = "/music/"

    /** DataMap-free items: the payload is the raw data of the item, the artwork an asset. */
    const val KEY_ART = "art"

    /** Declared by the phone app (`res/values/wear.xml`); the watch finds the phone by it. */
    const val CAPABILITY_PHONE = "music_phone"

    const val VOLUME_UNKNOWN = -1
    private const val MAX_TEXT = 120
    private const val MAX_PLAYERS = 24
    private const val MAX_SHORTCUTS = 24

    /** How the phone app stands with the Music Assistant server. */
    enum class Link {
        /** Connected and signed in: the state is live. */
        OK,

        /** Connecting or signing in. The rest of the snapshot is the last known state. */
        CONNECTING,

        /** The phone let the connection sleep in the background. Last known state; a command wakes it. */
        ASLEEP,

        /** The server cannot be reached. Last known state. */
        OFFLINE,

        /** No server or no sign-in on the phone app yet: open Music on the phone. */
        SIGNED_OUT,
    }

    data class WirePlayer(
        val id: String,
        val name: String,
        val playing: Boolean,
    )

    /** What the selected player plays, plus the players to choose from. */
    data class NowPlaying(
        val link: Link,
        val playerId: String = "",
        val playerName: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val playing: Boolean = false,
        /** The player has a current item, so previous / next mean something. */
        val hasItem: Boolean = false,
        /** 0..100, or [VOLUME_UNKNOWN] when the player has no volume control. */
        val volume: Int = VOLUME_UNKNOWN,
        val muted: Boolean = false,
        /** Identity of the artwork (not a URL the watch could fetch): changes when the picture does. */
        val artKey: String = "",
        val players: List<WirePlayer> = emptyList(),
    ) {
        val hasPlayer: Boolean get() = playerId.isNotEmpty()
        val hasVolume: Boolean get() = volume != VOLUME_UNKNOWN

        /** One line for a tile, a complication or a compact widget. */
        val headline: String
            get() = when {
                title.isNotEmpty() && artist.isNotEmpty() -> "$title · $artist"
                title.isNotEmpty() -> title
                else -> ""
            }
    }

    /** Something the watch can start on the selected player. [uri] is a Music Assistant media uri. */
    data class Shortcut(val uri: String, val title: String, val subtitle: String = "")

    sealed interface Command {
        data object Toggle : Command
        data object Next : Command
        data object Previous : Command

        /** One server-side volume step, for a player whose level the watch does not know. */
        data class VolumeStep(val up: Boolean) : Command

        /** Absolute level 0..100 (crown / rotary). */
        data class VolumeSet(val level: Int) : Command
        data class SelectPlayer(val playerId: String) : Command
        data class Play(val uri: String) : Command

        /** "The watch app is on screen": reconnect if asleep, republish state and shortcuts. */
        data object Refresh : Command
    }

    /* ---- encoding ---- */

    fun encode(state: NowPlaying): ByteArray = write {
        writeByte(state.link.ordinal)
        text(state.playerId, limit = Int.MAX_VALUE)
        text(state.playerName)
        text(state.title)
        text(state.artist)
        text(state.album)
        writeBoolean(state.playing)
        writeBoolean(state.hasItem)
        writeByte(state.volume.coerceIn(VOLUME_UNKNOWN, 100))
        writeBoolean(state.muted)
        text(state.artKey)
        val players = state.players.take(MAX_PLAYERS)
        writeByte(players.size)
        players.forEach {
            text(it.id, limit = Int.MAX_VALUE)
            text(it.name)
            writeBoolean(it.playing)
        }
    }

    fun decodeState(bytes: ByteArray?): NowPlaying? = read(bytes) {
        val link = Link.entries.getOrNull(readUnsignedByte()) ?: return@read null
        NowPlaying(
            link = link,
            playerId = readUTF(),
            playerName = readUTF(),
            title = readUTF(),
            artist = readUTF(),
            album = readUTF(),
            playing = readBoolean(),
            hasItem = readBoolean(),
            volume = readByte().toInt(),
            muted = readBoolean(),
            artKey = readUTF(),
            players = List(readUnsignedByte()) { WirePlayer(readUTF(), readUTF(), readBoolean()) },
        )
    }

    fun encode(shortcuts: List<Shortcut>): ByteArray = write {
        val list = shortcuts.take(MAX_SHORTCUTS)
        writeByte(list.size)
        list.forEach {
            text(it.uri, limit = Int.MAX_VALUE)
            text(it.title)
            text(it.subtitle)
        }
    }

    fun decodeShortcuts(bytes: ByteArray?): List<Shortcut>? = read(bytes) {
        List(readUnsignedByte()) { Shortcut(readUTF(), readUTF(), readUTF()) }
    }

    fun encode(command: Command): ByteArray = write {
        when (command) {
            Command.Toggle -> writeByte(OP_TOGGLE)
            Command.Next -> writeByte(OP_NEXT)
            Command.Previous -> writeByte(OP_PREVIOUS)
            is Command.VolumeStep -> {
                writeByte(OP_VOLUME_STEP)
                writeBoolean(command.up)
            }

            is Command.VolumeSet -> {
                writeByte(OP_VOLUME_SET)
                writeByte(command.level.coerceIn(0, 100))
            }

            is Command.SelectPlayer -> {
                writeByte(OP_SELECT_PLAYER)
                text(command.playerId, limit = Int.MAX_VALUE)
            }

            is Command.Play -> {
                writeByte(OP_PLAY)
                text(command.uri, limit = Int.MAX_VALUE)
            }

            Command.Refresh -> writeByte(OP_REFRESH)
        }
    }

    fun decodeCommand(bytes: ByteArray?): Command? = read(bytes) {
        when (readUnsignedByte()) {
            OP_TOGGLE -> Command.Toggle
            OP_NEXT -> Command.Next
            OP_PREVIOUS -> Command.Previous
            OP_VOLUME_STEP -> Command.VolumeStep(readBoolean())
            OP_VOLUME_SET -> Command.VolumeSet(readUnsignedByte().coerceIn(0, 100))
            OP_SELECT_PLAYER -> Command.SelectPlayer(readUTF())
            OP_PLAY -> Command.Play(readUTF())
            OP_REFRESH -> Command.Refresh
            else -> null
        }
    }

    private const val OP_TOGGLE = 1
    private const val OP_NEXT = 2
    private const val OP_PREVIOUS = 3
    private const val OP_VOLUME_STEP = 4
    private const val OP_VOLUME_SET = 5
    private const val OP_SELECT_PLAYER = 6
    private const val OP_PLAY = 7
    private const val OP_REFRESH = 8

    private inline fun write(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeByte(VERSION)
            it.block()
        }
        return bytes.toByteArray()
    }

    /** Null for no data, another [VERSION], or bytes that are not a payload of ours. */
    private inline fun <T> read(bytes: ByteArray?, block: DataInputStream.() -> T?): T? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readUnsignedByte() != VERSION) null else it.block()
            }
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** Display text is cut to [limit] characters; ids and uris are never cut, only bounded by writeUTF. */
    private fun DataOutputStream.text(value: String, limit: Int = MAX_TEXT) {
        val cut = if (value.length > limit) value.take(limit - 1) + "…" else value
        // writeUTF carries at most 65535 encoded bytes; 16000 characters stay under it in any script.
        writeUTF(cut.take(MAX_UTF_CHARS))
    }

    private const val MAX_UTF_CHARS = 16_000
}

/**
 * Turns crown / rotary movement into volume levels. The watch shows the new level at once and
 * sends at most one [MusicWire.Command.VolumeSet] per [sendEveryMs], always ending on the last
 * level, so a spin does not flood the Bluetooth link or the server.
 */
class VolumeDial(
    private val pixelsPerStep: Float = 24f,
    private val sendEveryMs: Long = 250,
) {
    private var carry = 0f
    private var lastSentAt = Long.MIN_VALUE
    private var lastSent = MusicWire.VOLUME_UNKNOWN

    /** The level after [pixels] of rotation from [current]; positive pixels turn it up. */
    fun turn(current: Int, pixels: Float): Int {
        carry += pixels
        val steps = (carry / pixelsPerStep).toInt()
        carry -= steps * pixelsPerStep
        return (current + steps).coerceIn(0, 100)
    }

    /** True when [level] should go to the phone now; false when a later [flush] must send it. */
    fun shouldSend(level: Int, nowMs: Long): Boolean {
        if (level == lastSent) return false
        if (lastSentAt != Long.MIN_VALUE && nowMs - lastSentAt < sendEveryMs) return false
        lastSentAt = nowMs
        lastSent = level
        return true
    }

    /** The level still owed to the phone after the movement stopped, or null. */
    fun flush(level: Int, nowMs: Long): Int? {
        if (level == lastSent) return null
        lastSentAt = nowMs
        lastSent = level
        return level
    }
}
