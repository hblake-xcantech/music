package io.music_assistant.client.surfaces

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import co.touchlab.kermit.Logger
import coil3.SingletonImageLoader
import com.xcantech.music.wire.MusicWire
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.Link
import com.xcantech.music.wire.MusicWire.NowPlaying
import com.xcantech.music.wire.MusicWire.Shortcut
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.MainDataSource
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.QueueOption
import io.music_assistant.client.data.play.PlayDispatcher
import io.music_assistant.client.data.play.PlayRequest
import io.music_assistant.client.data.repository.MediaItemRepository
import io.music_assistant.client.data.repository.fetchRecommendationRowItems
import io.music_assistant.client.data.repository.fetchRecommendationRows
import io.music_assistant.client.services.MainMediaPlaybackService
import io.music_assistant.client.services.isAnyQueueItemPlaying
import io.music_assistant.client.services.loadCoilBitmap
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import io.music_assistant.client.utils.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Music fork (MUSIC.md): feeds everything that controls playback from outside the app UI — the
 * home-screen widgets and the watch — from the app's ONE server session.
 *
 * It opens no connection of its own. State comes from [MainDataSource] (the same flows the
 * media notification reads) and commands go through [MainDataSource.playerAction], so a tap on
 * a widget is the same request as a tap in the app. A command that finds the session asleep in
 * the background wakes it with [ServiceClient.requestCommandRecovery], like the app does.
 *
 * Process-scoped: started once from `MyApplication`. While the process is alive and connected
 * the surfaces follow the server live. When Android freezes or kills the idle process they keep
 * the last snapshot (persisted here, so a cold start does not blank them) until the next tap,
 * the next periodic widget update, or the next time the app or media service runs.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SurfaceHub(
    private val context: Context,
    private val dataSource: MainDataSource,
    private val apiClient: ServiceClient,
    private val settings: SettingsRepository,
    private val playDispatcher: PlayDispatcher,
    private val mediaItemRepository: MediaItemRepository,
) {
    /** Something that shows the snapshot: the widgets, the watch publisher. Called off the main thread. */
    fun interface Surface {
        fun show(state: NowPlaying, art: Bitmap?)
    }

    private val log = Logger.withTag("SurfaceHub")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val artFile by lazy { File(context.filesDir, "surface_art.jpg") }
    private val surfaces = CopyOnWriteArrayList<Surface>()
    private val started = AtomicBoolean(false)

    private val persisted: NowPlaying? = restore()

    /** The last snapshot built from a live session; what the surfaces fall back to. */
    @Volatile
    private var lastLive: NowPlaying? = persisted

    private val _state = MutableStateFlow(
        persisted?.copy(link = Link.CONNECTING) ?: NowPlaying(Link.CONNECTING),
    )
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _art = MutableStateFlow(restoreArt())
    val art: StateFlow<Bitmap?> = _art.asStateFlow()

    fun addSurface(surface: Surface) {
        surfaces += surface
    }

    /** Runs [command] in the hub's own scope, so it outlives the receiver or request that asked. */
    fun launchCommand(command: Command): Deferred<Boolean> = scope.async { execute(command) }

    fun launch(block: suspend SurfaceHub.() -> Unit): Job = scope.launch { block() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                apiClient.sessionState,
                apiClient.isReadyForCommands,
                dataSource.displayedPlayers,
                dataSource.selectedPlayerId,
            ) { session, ready, players, selectedId ->
                val serverId = (session as? SessionState.Connected)
                    ?.connectionData?.serverInfo?.serverId
                val link = linkOf(session, ready, serverId?.let(settings::getTokenForServer) != null)
                buildNowPlaying(link, players.map(PlayerData::toView), selectedId, lastLive)
            }
                .debounce(STATE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.link == Link.OK && snapshot.hasPlayer) {
                        lastLive = snapshot
                        persist(snapshot)
                    }
                    _state.value = snapshot
                }
        }
        scope.launch {
            combine(dataSource.displayedPlayers, dataSource.selectedPlayerId) { players, selectedId ->
                pickPlayer(players.map(PlayerData::toView), selectedId)?.let { it.id to it.imageUrl }
            }
                .distinctUntilChanged()
                // No player yet (a cold process that is still connecting): keep the restored picture.
                .mapLatest { picked -> picked?.let { (_, url) -> ArtUpdate(url?.let { loadArt(it) }) } }
                .collect { update -> update?.let { setArt(it.bitmap) } }
        }
        scope.launch {
            combine(state, art) { snapshot, bitmap -> snapshot to bitmap }
                .debounce(RENDER_DEBOUNCE_MS)
                .collect { (snapshot, bitmap) -> render(snapshot, bitmap) }
        }
    }

    private class ArtUpdate(val bitmap: Bitmap?)

    /** Shows the current snapshot again, for a widget that was just added or resized. */
    fun renderNow() = render(_state.value, _art.value)

    private fun render(snapshot: NowPlaying, bitmap: Bitmap?) {
        surfaces.forEach { surface ->
            runCatching { surface.show(snapshot, bitmap) }
                .onFailure { log.w(it) { "A surface failed to show the snapshot" } }
        }
    }

    /**
     * Brings the session back if it sleeps, without sending anything to a player. Called when a
     * widget is placed or its periodic update fires, and when the watch app opens.
     */
    fun wake() {
        if (!apiClient.isReadyForCommands.value) apiClient.requestCommandRecovery()
    }

    /**
     * Runs one command from a widget or the watch against the app's session. Returns false when
     * the server could not be reached in time or there is no player to act on.
     */
    suspend fun execute(command: Command): Boolean {
        if (command == Command.Refresh) {
            wake()
            if (apiClient.isReadyForCommands.value) dataSource.refreshPlayersAndQueues()
            return true
        }
        if (!awaitReady()) {
            log.w { "Not connected; dropped $command" }
            return false
        }
        if (command is Command.SelectPlayer) {
            val target = dataSource.displayedPlayers.value.firstOrNull { it.playerId == command.playerId }
                ?: return false
            dataSource.selectPlayer(target.player)
            return true
        }
        val player = currentPlayer() ?: return false
        when (command) {
            Command.Toggle -> dataSource.playerAction(player, toggleAction(player))
            Command.Next -> dataSource.playerAction(player, PlayerAction.Next)
            Command.Previous -> dataSource.playerAction(player, PlayerAction.Previous)
            is Command.VolumeStep -> dataSource.playerAction(player, volumeStepAction(player, command.up))
            is Command.VolumeSet -> dataSource.playerAction(player, volumeSetAction(player, command.level))
            is Command.Play -> playDispatcher.play(
                PlayRequest(
                    queueOrPlayerId = player.queueOrPlayerId,
                    media = listOf(command.uri),
                    option = QueueOption.REPLACE,
                ),
            )

            is Command.SelectPlayer, Command.Refresh -> Unit
        }
        if (command is Command.Toggle || command is Command.Next || command is Command.Previous ||
            command is Command.Play
        ) {
            scope.launch { keepLiveWhilePlaying() }
        }
        return true
    }

    private suspend fun awaitReady(): Boolean {
        if (!apiClient.isReadyForCommands.value) {
            apiClient.requestCommandRecovery()
            withTimeoutOrNull(READY_TIMEOUT_MS) { apiClient.isReadyForCommands.first { it } } ?: return false
        }
        // A session that just came back has no players until the first answer arrives.
        return withTimeoutOrNull(PLAYERS_TIMEOUT_MS) {
            dataSource.displayedPlayers.first { it.isNotEmpty() }
        } != null
    }

    private fun currentPlayer(): PlayerData? {
        val players = dataSource.displayedPlayers.value
        val id = pickPlayer(players.map(PlayerData::toView), dataSource.selectedPlayerId.value)?.id
        return players.firstOrNull { it.playerId == id }
    }

    /**
     * Once something plays, the app's own media service keeps the process and the session alive
     * (and shows the media notification), so the surfaces follow track changes. `MainActivity`
     * starts it while the app is open; this does the same after a tap on a widget or the watch.
     * Best effort: Android may refuse a foreground-service start from the background.
     */
    private suspend fun keepLiveWhilePlaying() {
        withTimeoutOrNull(PLAYING_TIMEOUT_MS) { dataSource.isAnyQueueItemPlaying.first { it } } ?: return
        try {
            context.startForegroundService(Intent(context, MainMediaPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                log.i { "Media service not started from the background; surfaces stay on the last snapshot" }
            } else {
                log.w(e) { "Media service start failed" }
            }
        } catch (e: SecurityException) {
            log.w(e) { "Media service start refused" }
        }
    }

    /** Things to start from the watch: the first items of the home-page recommendation rows. */
    suspend fun shortcuts(): List<Shortcut> {
        if (!apiClient.isReadyForCommands.value) return emptyList()
        val rows = mediaItemRepository.fetchRecommendationRows().getOrNull().orEmpty()
        return rows.take(SHORTCUT_ROWS).flatMap { row ->
            val items = row.items?.takeIf { it.isNotEmpty() }
                ?: mediaItemRepository.fetchRecommendationRowItems(row).orEmpty()
            items.take(SHORTCUTS_PER_ROW).mapNotNull { item ->
                item.mediaUri?.let { Shortcut(it, item.displayName, item.subtitle ?: row.displayName) }
            }
        }.distinctBy { it.uri }
    }

    private suspend fun loadArt(url: String): Bitmap? = runCatching {
        loadCoilBitmap(context, SingletonImageLoader.get(context), url)?.let(::scaleForSurfaces)
    }.getOrNull()

    private fun setArt(bitmap: Bitmap?) {
        _art.value = bitmap
        runCatching {
            bitmap?.let { artFile.outputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, ART_QUALITY, out) } }
                ?: artFile.delete()
        }
    }

    private fun restoreArt(): Bitmap? = runCatching {
        artFile.takeIf { persisted != null && it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }.getOrNull()

    private fun persist(snapshot: NowPlaying) {
        prefs.edit().putString(KEY_STATE, Base64.encodeToString(MusicWire.encode(snapshot), Base64.NO_WRAP)).apply()
    }

    private fun restore(): NowPlaying? = runCatching {
        prefs.getString(KEY_STATE, null)?.let { MusicWire.decodeState(Base64.decode(it, Base64.NO_WRAP)) }
    }.getOrNull()

    companion object {
        private const val PREFS = "music_surfaces"
        private const val KEY_STATE = "state"
        private const val STATE_DEBOUNCE_MS = 250L
        private const val RENDER_DEBOUNCE_MS = 100L
        private const val READY_TIMEOUT_MS = 12_000L
        private const val PLAYERS_TIMEOUT_MS = 8_000L
        private const val PLAYING_TIMEOUT_MS = 6_000L
        private const val SHORTCUT_ROWS = 4
        private const val SHORTCUTS_PER_ROW = 5
        private const val ART_QUALITY = 85

        /** Widgets copy the bitmap across processes once per size, the watch gets it over Bluetooth. */
        const val ART_MAX_PX = 320

        internal fun scaleForSurfaces(source: Bitmap): Bitmap {
            val longest = maxOf(source.width, source.height)
            if (longest <= ART_MAX_PX) return source
            val scale = ART_MAX_PX.toFloat() / longest
            return Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
    }
}

/** The app offers Stop where the content cannot pause (a radio stream); the surfaces do the same. */
internal fun toggleAction(player: PlayerData): PlayerAction =
    if (player.player.isPlaying && !player.canPauseContent) PlayerAction.Stop else PlayerAction.TogglePlayPause

/** The same command the taps beside the app's volume slider send. */
internal fun volumeStepAction(player: PlayerData, up: Boolean): PlayerAction = when {
    player.usesGroupVolume && up -> PlayerAction.GroupVolumeUp
    player.usesGroupVolume -> PlayerAction.GroupVolumeDown
    up -> PlayerAction.VolumeUp
    else -> PlayerAction.VolumeDown
}

internal fun volumeSetAction(player: PlayerData, level: Int): PlayerAction {
    val value = level.coerceIn(0, 100).toDouble()
    return if (player.usesGroupVolume) PlayerAction.GroupVolumeSet(value) else PlayerAction.VolumeSet(value)
}
