package io.music_assistant.client.surfaces.wear

import android.content.Context
import android.graphics.Bitmap
import co.touchlab.kermit.Logger
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.xcantech.music.wire.MusicWire
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.NowPlaying
import io.music_assistant.client.surfaces.SurfaceHub
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream

/**
 * Music fork (MUSIC.md, "Watch"): the phone's end of Music on the Pixel Watch.
 *
 * The watch has no route to the Music Assistant server (no Tailscale, no token), so near the
 * phone it sends each tap here over the Wear OS Data Layer, which only connects the same app,
 * signed with the same key, on a watch paired to this phone. The command then runs through
 * [SurfaceHub] on the app's existing session — the watch can do exactly what a widget can.
 * Google Play services starts this service for a request even when the app is closed.
 */
class WearBridgeService : WearableListenerService(), KoinComponent {
    private val hub: SurfaceHub by inject()

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != MusicWire.COMMAND) return null
        // An empty reply tells the watch this phone app does not understand its wire version.
        val command = MusicWire.decodeCommand(request) ?: return Tasks.forResult(ByteArray(0))
        val reply = TaskCompletionSource<ByteArray>()
        hub.launch {
            runCatching { execute(command) }
                .onFailure { Logger.withTag("WearBridge").w(it) { "Watch command $command failed" } }
            reply.trySetResult(MusicWire.encode(state.value))
            if (command == Command.Refresh) runCatching { WearPublisher.publishShortcuts(applicationContext, this) }
        }
        return reply.task
    }
}

/**
 * Phone -> watch: the snapshot as a DataItem (with the artwork as an asset), so the watch's
 * tile and complication are current even while its app is closed. The Data Layer only sends an
 * item that changed, and delivers it when the watch is next in reach.
 */
class WearPublisher(private val context: Context) : SurfaceHub.Surface {
    private val log = Logger.withTag("WearPublisher")
    private var failureLogged = false

    override fun show(state: NowPlaying, art: Bitmap?) {
        val request = PutDataRequest.create(MusicWire.STATE).setData(MusicWire.encode(state))
        art?.takeIf { state.artKey.isNotEmpty() }?.let(::jpeg)?.let { bytes ->
            request.putAsset(MusicWire.KEY_ART, Asset.createFromBytes(bytes))
        }
        Wearable.getDataClient(context).putDataItem(request.setUrgent()).addOnFailureListener {
            // No Wear OS support on this phone, or Play services is not ready: say it once.
            if (!failureLogged) log.i { "Watch state not published: ${it.message}" }
            failureLogged = true
        }
    }

    companion object {
        private const val WATCH_ART_PX = 240
        private const val WATCH_ART_QUALITY = 70
        private const val SHORTCUTS_EVERY_MS = 5 * 60_000L

        @Volatile
        private var shortcutsAt = 0L

        /** A square small enough for a watch face-sized screen and one Bluetooth transfer. */
        internal fun jpeg(source: Bitmap): ByteArray {
            val scale = WATCH_ART_PX.toFloat() / maxOf(source.width, source.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                source
            }
            return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, WATCH_ART_QUALITY, it) }
                .toByteArray()
        }

        /** Things to start, fetched when the watch app opens; at most once per five minutes. */
        suspend fun publishShortcuts(context: Context, hub: SurfaceHub) {
            val now = System.currentTimeMillis()
            if (now - shortcutsAt < SHORTCUTS_EVERY_MS) return
            val shortcuts = hub.shortcuts().takeIf { it.isNotEmpty() } ?: return
            shortcutsAt = now
            Wearable.getDataClient(context).putDataItem(
                PutDataRequest.create(MusicWire.SHORTCUTS).setData(MusicWire.encode(shortcuts)).setUrgent(),
            )
        }
    }
}
