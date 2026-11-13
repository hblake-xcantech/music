package com.xcantech.music.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.xcantech.music.wire.MusicWire
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.NowPlaying
import com.xcantech.music.wire.MusicWire.Shortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** How the watch stands with the Music app on the phone. */
enum class PhoneLink {
    /** Nothing asked yet since the app started. */
    UNKNOWN,
    OK,

    /** No paired phone with Music in reach (out of Bluetooth range, phone off, app not installed). */
    UNREACHABLE,

    /** The phone answered in another wire version: one of the two apps needs updating. */
    OUTDATED,
}

/**
 * Everything the watch knows, in one place: the last snapshot the phone sent, its artwork, the
 * shortcuts, and whether the phone answers. Screens, the tile and the complication all read it.
 *
 * The watch never talks to the Music Assistant server. State arrives as Data Layer items
 * ([PhoneListener], also while the app is closed) and every tap is one request to the phone app
 * ([send]). Away from the phone the last snapshot stays on screen, marked as not reachable.
 */
object Store {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { app.getSharedPreferences("music", Context.MODE_PRIVATE) }
    private val artFile by lazy { File(app.filesDir, "art.jpg") }
    private var resync: Job? = null

    var state by mutableStateOf<NowPlaying?>(null)
        private set
    var art by mutableStateOf<ImageBitmap?>(null)
        private set
    var shortcuts by mutableStateOf<List<Shortcut>>(emptyList())
        private set
    var phone by mutableStateOf(PhoneLink.UNKNOWN)
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        state = bytes(KEY_STATE)?.let(MusicWire::decodeState)
        shortcuts = bytes(KEY_SHORTCUTS)?.let(MusicWire::decodeShortcuts).orEmpty()
        art = runCatching {
            artFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }.getOrNull()
    }

    /** The snapshot for a tile or complication, which may run before any screen did. */
    fun current(context: Context): NowPlaying? {
        init(context)
        return state
    }

    /* ---- phone -> watch ---- */

    fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            onItem(event.dataItem)
        }
    }

    private fun onItem(item: DataItem) {
        when (item.uri.path) {
            MusicWire.STATE -> {
                val data = item.data ?: return
                val decoded = MusicWire.decodeState(data) ?: return
                val artChanged = decoded.artKey != state?.artKey || art == null
                save(KEY_STATE, data)
                apply(decoded)
                if (artChanged) loadArt(item, decoded.artKey)
            }

            MusicWire.SHORTCUTS -> {
                val data = item.data ?: return
                shortcuts = MusicWire.decodeShortcuts(data) ?: return
                save(KEY_SHORTCUTS, data)
            }
        }
    }

    private fun loadArt(item: DataItem, artKey: String) {
        val asset = item.assets[MusicWire.KEY_ART]
        if (artKey.isEmpty() || asset == null) {
            art = null
            artFile.delete()
            return
        }
        scope.launch {
            runCatching {
                val bytes = Tasks.await(
                    Wearable.getDataClient(app).getFdForAsset(asset),
                    ASSET_TIMEOUT_S,
                    TimeUnit.SECONDS,
                )
                    .inputStream.use { it.readBytes() }
                artFile.writeBytes(bytes)
                // The track may have moved on while the picture travelled.
                if (state?.artKey == artKey) art = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }

    private fun apply(next: NowPlaying) {
        val changed = next != state
        state = next
        if (changed) refreshGlances()
    }

    private fun refreshGlances() {
        runCatching { TileService.getUpdater(app).requestUpdate(NowPlayingTile::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, NowPlayingComplication::class.java))
                .requestUpdateAll()
        }
    }

    /* ---- watch -> phone ---- */

    /**
     * One tap. [optimistic] changes the screen at once; the phone's reply and its next state item
     * correct it. A re-read a moment later settles the case where the tap changed nothing.
     */
    fun send(command: Command, optimistic: (NowPlaying) -> NowPlaying = { it }) {
        state?.let { apply(optimistic(it)) }
        scope.launch {
            val reply = runCatching { request(command) }.getOrNull()
            when {
                reply == null -> phone = PhoneLink.UNREACHABLE
                reply.isEmpty() -> phone = PhoneLink.OUTDATED
                else -> MusicWire.decodeState(reply)?.let { answered ->
                    phone = PhoneLink.OK
                    // A live phone confirms through the state item; its reply was taken before the
                    // server answered and would undo the optimistic change for a moment.
                    val keepOptimistic = answered.link == MusicWire.Link.OK &&
                        command != Command.Refresh && command !is Command.SelectPlayer && state != null
                    if (!keepOptimistic) apply(answered)
                } ?: run { phone = PhoneLink.OUTDATED }
            }
            scheduleResync()
        }
    }

    /** The crown moved but this step is throttled: show it now, [send] it when the dial settles. */
    fun showVolume(level: Int) {
        state = state?.copy(volume = level)
    }

    private fun request(command: Command): ByteArray? {
        val node = phoneNode() ?: return null
        return Tasks.await(
            Wearable.getMessageClient(app).sendRequest(node, MusicWire.COMMAND, MusicWire.encode(command)),
            REQUEST_TIMEOUT_S,
            TimeUnit.SECONDS,
        )
    }

    /** The paired phone that has Music installed; a nearby (Bluetooth) node first. */
    private fun phoneNode(): String? {
        val nodes = Tasks.await(
            Wearable.getCapabilityClient(
                app,
            ).getCapability(MusicWire.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
            NODE_TIMEOUT_S,
            TimeUnit.SECONDS,
        ).nodes
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }

    private fun scheduleResync() {
        resync?.cancel()
        resync = scope.launch {
            delay(RESYNC_AFTER_MS)
            readItems()
        }
    }

    /** Reads what the phone last published; the truth after the optimistic changes. */
    fun readItems() {
        scope.launch {
            runCatching {
                val items = Tasks.await(
                    Wearable.getDataClient(
                        app,
                    ).getDataItems(
                        Uri.parse("wear://*${MusicWire.PATH_PREFIX}"),
                        com.google.android.gms.wearable.DataClient.FILTER_PREFIX,
                    ),
                    NODE_TIMEOUT_S,
                    TimeUnit.SECONDS,
                )
                try {
                    items.forEach(::onItem)
                } finally {
                    items.release()
                }
            }
        }
    }

    private fun save(key: String, data: ByteArray) =
        prefs.edit().putString(key, Base64.encodeToString(data, Base64.NO_WRAP)).apply()

    private fun bytes(key: String): ByteArray? =
        runCatching { prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) } }.getOrNull()

    private const val KEY_STATE = "state"
    private const val KEY_SHORTCUTS = "shortcuts"
    private const val REQUEST_TIMEOUT_S = 20L
    private const val NODE_TIMEOUT_S = 4L
    private const val ASSET_TIMEOUT_S = 20L
    private const val RESYNC_AFTER_MS = 2_500L
}

/** Phone -> watch over the Data Layer. Google Play services starts this even when the app is closed. */
class PhoneListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        Store.init(applicationContext)
        Store.onDataChanged(events)
    }
}

/** What the watch says under the title: the phone's trouble first, then the server's, then the artist. */
fun statusText(state: NowPlaying?, phone: PhoneLink): String = when {
    phone == PhoneLink.OUTDATED -> "Update Music on phone and watch"
    phone == PhoneLink.UNREACHABLE -> "Phone not reachable"
    state == null -> "Waiting for the phone"
    state.link == MusicWire.Link.SIGNED_OUT -> "Open Music on the phone"
    state.link == MusicWire.Link.OFFLINE -> "Phone can't reach the server"
    state.link == MusicWire.Link.CONNECTING && !state.hasPlayer -> "Connecting…"
    else -> state.artist
}

fun titleText(state: NowPlaying?): String = when {
    state == null -> "Music"
    state.title.isNotEmpty() -> state.title
    state.hasPlayer -> "Nothing playing"
    else -> "Music"
}
