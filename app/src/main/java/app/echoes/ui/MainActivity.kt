package app.echoes.ui

import app.echoes.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.echoes.Graph
import app.echoes.library.Library
import app.echoes.playback.PlayerConnection
import app.echoes.portal.PortalService
import app.echoes.ui.screens.CollectionScreen
import app.echoes.ui.screens.DownloadScreen
import app.echoes.ui.screens.HomeScreen
import app.echoes.ui.screens.InsightsScreen
import app.echoes.ui.screens.LibraryScreen
import app.echoes.ui.screens.MiniPlayer
import app.echoes.ui.screens.NowPlaying
import app.echoes.ui.screens.QueueSheet
import app.echoes.ui.screens.SearchScreen
import app.echoes.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {
    private lateinit var player: PlayerConnection
    private lateinit var actions: Actions
    private val nav = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        player = PlayerConnection(this)
        actions = Actions(this)
        handleShare(intent)
        setContent {
            EchoesTheme {
                CompositionLocalProvider(LocalNav provides nav, LocalPlayer provides player, LocalActions provides actions) {
                    Gate()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Links shared from YouTube/Spotify, and CSV song lists shared or opened from a file manager. */
    private fun handleShare(intent: Intent?) {
        val file = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> return
        }
        when {
            file != null -> nav.sharedFile = file
            intent.action == Intent.ACTION_SEND -> nav.sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            else -> return
        }
        nav.go(Route.Download)
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Gate() {
        var granted by remember { mutableStateOf(hasAudioPermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasAudioPermission()
        }
        if (!granted) {
            Welcome { launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }
            return
        }
        LaunchedEffect(Unit) {
            Graph.library.refresh()
            if (Graph.prefs.portalEnabled.value) PortalService.setRunning(this@MainActivity, true)
        }
        val lib by Graph.library.library.collectAsState()
        val current = lib
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.mint) }
        } else {
            Root(current)
        }
    }

    @Composable
    private fun Root(lib: Library) {
        BackHandler(enabled = nav.stack.size > 1 || nav.playerOpen || nav.queueOpen || nav.tab != Route.Home) { nav.back() }
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { route ->
                when (route) {
                    Route.Home -> HomeScreen(lib)
                    Route.Library -> LibraryScreen(lib)
                    Route.Search -> SearchScreen(lib)
                    Route.Insights -> InsightsScreen(lib)
                    Route.Settings -> SettingsScreen(lib)
                    Route.Download -> DownloadScreen(lib)
                    else -> CollectionScreen(route, lib)
                }
            }
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Brush.verticalGradient(listOf(Palette.bg, Palette.bg.copy(alpha = 0.7f)))),
            )
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bg))))
                Column(Modifier.background(Palette.bg).navigationBarsPadding()) {
                    MiniPlayer(lib)
                    BottomBar()
                }
            }
            AnimatedVisibility(nav.playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NowPlaying(lib)
            }
            AnimatedVisibility(nav.queueOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                QueueSheet(lib)
            }
            ActionDialogs()
        }
    }

    @Composable
    private fun BottomBar() {
        val items = listOf(
            Triple(Route.Home, "Inicio", Icons.Rounded.Home),
            Triple(Route.Library, "Biblioteca", Icons.Rounded.LibraryMusic),
            Triple(Route.Search, "Buscar", Icons.Rounded.Search),
            Triple(Route.Insights, "Tú", Icons.Rounded.Insights),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { (route, label, icon) -> TabItem(label, icon, nav.tab == route) { nav.switchTab(route) } }
        }
    }

    @Composable
    private fun TabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
        Column(
            Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, label, tint = if (selected) Palette.text else Palette.muted)
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Palette.text else Palette.muted)
        }
    }

    @Composable
    private fun Welcome(onGrant: () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12352A), Palette.bg))).padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val name = stringResource(R.string.app_name)
            Text(name, style = MaterialTheme.typography.displaySmall, color = Palette.mint)
            Spacer(Modifier.height(12.dp))
            Text("Tu música, escuchada de verdad.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "$name lee la música que ya tienes en el móvil, mide cómo suena cada canción y aprende qué pones después. " +
                    "Nada sale del teléfono.",
                color = Palette.muted, textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.clip(CircleShape).background(Palette.mint).clickable(onClick = onGrant).padding(horizontal = 28.dp, vertical = 16.dp),
            ) { Text("Dar acceso a la música", color = Palette.bg, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.size(1.dp))
        }
    }
}
