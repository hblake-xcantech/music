package io.music_assistant.client.surfaces.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.Link
import io.music_assistant.client.MainActivity
import io.music_assistant.client.R
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.surfaces.SurfaceHub
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Music fork (MUSIC.md): the small "Play on" list a widget opens from its player name. Choosing
 * a player selects it in the app too — the widgets, the watch and the app share one selection.
 * A system dialog over a transparent activity: no layout of its own, follows light and dark.
 */
class PlayerPickerActivity : Activity(), KoinComponent {
    private val hub: SurfaceHub by inject()
    private val dataSource: MainDataSource by inject()
    private val scope = MainScope()
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hub.state.value.link == Link.SIGNED_OUT) {
            openApp()
            return
        }
        hub.wake()
        dataSource.displayedPlayers.value.takeIf { it.isNotEmpty() }?.let(::showPlayers) ?: run {
            show(builder().setMessage(R.string.music_picker_connecting))
            scope.launch {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { dataSource.displayedPlayers.first { it.isNotEmpty() } }
                    ?.let(::showPlayers)
                    ?: show(
                        builder().setMessage(R.string.music_picker_unreachable)
                            .setPositiveButton(R.string.music_picker_open_app) { _, _ -> openApp() },
                    )
            }
        }
    }

    private fun showPlayers(players: List<PlayerData>) {
        val current = hub.state.value.playerId
        val labels = players.map { data ->
            val name = data.player.nameAndSuffix
            if (data.player.isPlaying) getString(R.string.music_picker_playing, name) else name
        }
        show(
            builder().setTitle(R.string.music_picker_title)
                .setSingleChoiceItems(
                    labels.toTypedArray(),
                    players.indexOfFirst { it.playerId == current },
                ) { _, which ->
                    hub.launchCommand(Command.SelectPlayer(players[which].playerId))
                    finish()
                },
        )
    }

    private fun builder(): AlertDialog.Builder {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return AlertDialog.Builder(
            this,
            if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
        )
    }

    private fun show(builder: AlertDialog.Builder) {
        if (isFinishing || isDestroyed) return
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = builder.setOnDismissListener { finish() }.show()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
