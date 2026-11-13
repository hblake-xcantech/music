package io.music_assistant.client.surfaces.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.xcantech.music.wire.MusicWire.Command
import io.music_assistant.client.R
import io.music_assistant.client.surfaces.SurfaceHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Music fork (MUSIC.md): a tap on a widget button. Not exported: only this app's own
 * PendingIntents reach it. The command runs in [SurfaceHub]'s scope; the receiver only waits
 * (bounded) so Android keeps the process awake while the session reconnects.
 */
class WidgetActionReceiver : BroadcastReceiver(), KoinComponent {
    private val hub: SurfaceHub by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val command = commandFor(intent.action) ?: return
        val pending = goAsync()
        val job = hub.launchCommand(command)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val done = withTimeoutOrNull(RECEIVER_BUDGET_MS) { job.await() }
                // Null: still reconnecting; the command stays queued in the hub and may yet go out.
                if (done == false) {
                    Toast.makeText(context.applicationContext, R.string.music_not_reachable, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.xcantech.music.widget.TOGGLE"
        const val ACTION_NEXT = "com.xcantech.music.widget.NEXT"
        const val ACTION_PREVIOUS = "com.xcantech.music.widget.PREVIOUS"
        const val ACTION_VOLUME_UP = "com.xcantech.music.widget.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.xcantech.music.widget.VOLUME_DOWN"

        // A manifest receiver must finish well inside Android's 10 s limit for foreground broadcasts.
        private const val RECEIVER_BUDGET_MS = 8_000L

        internal fun commandFor(action: String?): Command? = when (action) {
            ACTION_TOGGLE -> Command.Toggle
            ACTION_NEXT -> Command.Next
            ACTION_PREVIOUS -> Command.Previous
            ACTION_VOLUME_UP -> Command.VolumeStep(up = true)
            ACTION_VOLUME_DOWN -> Command.VolumeStep(up = false)
            else -> null
        }

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
