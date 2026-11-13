package com.xcantech.music.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.xcantech.music.wire.MusicWire.Command
import com.xcantech.music.wire.MusicWire.NowPlaying
import com.xcantech.music.wire.VolumeDial
import kotlinx.coroutines.delay

/**
 * Music on the Pixel Watch: now playing with the controls, the crown for volume, and one list
 * to pick the player or start something. Every tap goes to the Music app on the phone ([Store]).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val nav = rememberSwipeDismissableNavController()
            AppScaffold(Modifier.background(Palette.bg)) {
                SwipeDismissableNavHost(nav, startDestination = "now") {
                    composable("now") { NowScreen(onMore = { nav.navigate("more") }) }
                    composable("more") { MoreScreen(onDone = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wakes the phone app's session if it sleeps, and asks for fresh state and shortcuts.
        Store.readItems()
        Store.send(Command.Refresh)
    }
}

object Palette {
    val bg = Color(0xFF000000)
    val text = Color(0xFFF5F5F7)
    val dim = Color(0xFFB4AEC4)
    val accent = Color(0xFFB9A8FF)
    val onAccent = Color(0xFF20124F)
    val chip = Color(0xFF2A2440)
    val warn = Color(0xFFFFB4A8)
}

@Composable
private fun NowScreen(onMore: () -> Unit) {
    val state = Store.state
    val phone = Store.phone
    val focus = remember { FocusRequester() }
    val dial = remember { VolumeDial() }
    val level = state?.volume ?: -1

    LaunchedEffect(Unit) { focus.requestFocus() }
    // The crown stopped: send the level the last throttled turn left behind.
    LaunchedEffect(level) {
        delay(VOLUME_SETTLE_MS)
        if (level >= 0) dial.flush(level, System.currentTimeMillis())?.let { Store.send(Command.VolumeSet(it)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .onRotaryScrollEvent { event ->
                val current = Store.state?.takeIf { it.hasVolume } ?: return@onRotaryScrollEvent false
                val next = dial.turn(current.volume, event.verticalScrollPixels)
                if (next != current.volume) {
                    if (dial.shouldSend(next, System.currentTimeMillis())) {
                        Store.send(Command.VolumeSet(next)) { it.copy(volume = next) }
                    } else {
                        Store.showVolume(next)
                    }
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Store.art?.takeIf { state?.title?.isNotEmpty() == true }?.let { picture ->
            Image(
                picture,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = ART_ALPHA,
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Text(
                titleText(state),
                color = Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            val status = statusText(state, phone)
            if (status.isNotEmpty()) {
                Text(
                    status,
                    color = if (status == state?.artist) Palette.dim else Palette.warn,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(R.drawable.ic_previous, "Previous", 38.dp) { Store.send(Command.Previous) }
                RoundButton(
                    if (state?.playing == true) R.drawable.ic_pause else R.drawable.ic_play,
                    if (state?.playing == true) "Pause" else "Play",
                    50.dp,
                    filled = true,
                ) { Store.send(Command.Toggle) { it.copy(playing = !it.playing) } }
                RoundButton(R.drawable.ic_next, "Next", 38.dp) { Store.send(Command.Next) }
            }
            Spacer(Modifier.height(2.dp))
            if (state?.hasVolume == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(R.drawable.ic_volume_down, "Volume down", 30.dp) { step(state, up = false) }
                    Text(
                        if (state.muted) "muted" else "${state.volume}",
                        color = Palette.dim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(44.dp),
                    )
                    RoundButton(R.drawable.ic_volume_up, "Volume up", 30.dp) { step(state, up = true) }
                }
            }
            Spacer(Modifier.height(2.dp))
            PlayerChip(state?.playerName?.ifEmpty { null } ?: "Players", onMore)
        }
    }
}

private fun step(state: NowPlaying, up: Boolean) {
    val next = (state.volume + if (up) VOLUME_BUTTON_STEP else -VOLUME_BUTTON_STEP).coerceIn(0, 100)
    Store.send(Command.VolumeSet(next)) { it.copy(volume = next) }
}

@Composable
private fun RoundButton(icon: Int, label: String, size: Dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) Palette.accent else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(size * ICON_FRACTION),
            colorFilter = ColorFilter.tint(if (filled) Palette.onAccent else Palette.text),
        )
    }
}

@Composable
private fun PlayerChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 130.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.chip)
            .clickable(role = Role.Button, onClickLabel = "Choose player or something to play", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.ic_speaker),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            colorFilter = ColorFilter.tint(Palette.accent),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            color = Palette.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreScreen(onDone: () -> Unit) {
    val state = Store.state
    val shortcuts = Store.shortcuts
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(
            Modifier.background(Palette.bg),
            state = list,
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 18.dp,
            ),
        ) {
            item { Header("Play on") }
            val players = state?.players.orEmpty()
            if (players.isEmpty()) item { Note(statusText(state, Store.phone).ifEmpty { "No players" }) }
            items(players.size) { index ->
                val player = players[index]
                ListRow(
                    title = player.name,
                    subtitle = if (player.playing) "Playing" else "",
                    selected = player.id == state?.playerId,
                ) {
                    Store.send(Command.SelectPlayer(player.id))
                    onDone()
                }
            }
            item { Header("Start something") }
            if (shortcuts.isEmpty()) item { Note("Suggestions appear after the phone app has connected") }
            items(shortcuts.size) { index ->
                val shortcut = shortcuts[index]
                ListRow(shortcut.title, shortcut.subtitle, selected = false) {
                    Store.send(Command.Play(shortcut.uri))
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        color = Palette.dim,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Palette.dim,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ListRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.accent else Palette.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = if (selected) Palette.onAccent else Palette.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                color = if (selected) Palette.onAccent else Palette.dim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val ART_ALPHA = 0.28f
private const val ICON_FRACTION = 0.55f
private const val VOLUME_BUTTON_STEP = 4
private const val VOLUME_SETTLE_MS = 350L
