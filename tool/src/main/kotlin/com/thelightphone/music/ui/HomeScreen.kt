package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/** The iPod-style hub: drill into Artists / Albums / Playlists, or jump to Search / Now Playing. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun willShow() {
        MusicApp.start(lightContext)
    }

    @Composable
    override fun Content() {
        // The ONE readiness gate: every screen below is only reachable once a session exists,
        // so they may capture MusicApp.requireSession() at construction without null-dancing.
        val session by MusicApp.session.collectAsState()
        val configured = session != null

        MusicScaffold(
            title = "LightDrome",
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            // The menu scrolls in the space left over — the now-playing bar never covers it.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!configured) {
                    LightText(
                        text = "Add your Navidrome server to begin.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    MenuRow("Settings") { navigateTo({ SettingsScreen(it) }) }
                } else {
                    MenuRow("Artists") { navigateTo({ ArtistsScreen(it) }) }
                    MenuRow("Albums") { navigateTo({ AlbumListScreen(it, "Albums", null) }) }
                    MenuRow("Songs") { navigateTo({ SongsScreen(it) }) }
                    MenuRow("Playlists") { navigateTo({ PlaylistsScreen(it) }) }
                    MenuRow("Search") { navigateTo({ SearchScreen(it) }) }
                    MenuRow("Now Playing") { navigateTo({ NowPlayingScreen(it) }) }
                    MenuRow("Settings") { navigateTo({ SettingsScreen(it) }) }
                }
            }
        }
    }
}
