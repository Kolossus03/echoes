package app.echoes.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.res.stringResource
import app.echoes.R
import app.echoes.ui.Shortcut
import app.echoes.ui.components.CollectionArt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoes.Graph
import app.echoes.data.BoolPref
import app.echoes.library.Library
import app.echoes.portal.PortalService
import app.echoes.ui.LocalActions
import app.echoes.ui.LocalNav
import app.echoes.ui.Palette
import app.echoes.ui.components.SectionTitle
import app.echoes.ui.components.screenPadding

@Composable
fun SettingsScreen(lib: Library) {
    val nav = LocalNav.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val portalOn by Graph.prefs.portalEnabled.flow.collectAsState()
    val address by PortalService.address.collectAsState()
    var token by remember { mutableStateOf(Graph.prefs.portalToken) }
    val analysis by Graph.analysis.byPath.collectAsState()
    val analysed = lib.tracks.count { Graph.analysis.current(it) != null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { SectionTitle("Portal") }
        item {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Abrir el Portal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Con el móvil y el PC en la misma Wi-Fi, abre la dirección en el navegador: sube canciones arrastrándolas, " +
                                "controla la música y arregla etiquetas.",
                            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        portalOn,
                        onCheckedChange = { Graph.prefs.portalEnabled.set(it); PortalService.setRunning(context, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.bg),
                    )
                }
                if (portalOn) {
                    Spacer(Modifier.height(14.dp))
                    Text(address ?: "Arrancando…", style = MaterialTheme.typography.headlineSmall, color = Palette.mint)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Código: ", color = Palette.muted)
                        Text(token, fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 4.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { token = Graph.prefs.rotatePortalToken(); actions.toast("Código nuevo") }) { Text("Cambiar") }
                    }
                }
            }
        }
        if (Shortcut.supported(context)) {
            item { SectionTitle("Icono y nombre") }
            item { ShortcutCard() }
        }
        item { SectionTitle("Sonido") }
        item {
            Toggle(
                Graph.prefs.normalize, "Volumen uniforme",
                "Cada canción se mide (LUFS) y se ajusta a -14 LUFS: ni sustos con las que vienen altas ni subir el volumen con las flojas.",
            )
        }
        item {
            Toggle(
                Graph.prefs.trimSilence, "Recortar silencios",
                "Se salta el silencio del principio y del final de cada archivo, así las canciones se encadenan sin huecos.",
            )
        }
        item {
            Text(
                "Análisis: $analysed de ${lib.tracks.size} canciones medidas (${analysis.size} en total).",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item { SectionTitle("Listas") }
        item {
            Text(
                "Cada carpeta con música es una lista. Apaga las que no quieras ver (se ocultan también de Flow y de las mezclas).",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(lib.allFolders, key = { it.relPath }) { f ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall)
                    Text("${f.relPath} · ${f.tracks.size}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    !f.hidden, onCheckedChange = { actions.setFolder(f, hidden = !it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.bg),
                )
            }
        }
    }
}

@Composable
private fun ShortcutCard() {
    val context = LocalContext.current
    val actions = LocalActions.current
    val default = stringResource(R.string.app_name)
    var name by remember { mutableStateOf(Graph.prefs.shortcutName ?: default) }
    var waiting by remember { mutableStateOf(0L) }
    var blocked by remember { mutableStateOf(false) }
    val confirmed by Shortcut.confirmed.collectAsState()
    LaunchedEffect(waiting, confirmed) {
        if (waiting == 0L) return@LaunchedEffect
        if (confirmed >= waiting) { waiting = 0L; actions.toast("Acceso directo añadido"); return@LaunchedEffect }
        delay(6_000)
        blocked = true
        waiting = 0L
    }
    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            containerColor = Palette.surfaceHigh,
            title = { Text("No ha aparecido el acceso directo") },
            text = {
                Text(
                    "Si no has visto ningún aviso, el móvil lo está bloqueando. En Xiaomi: Información de la app → Otros permisos → " +
                        "«Accesos directos de la pantalla de inicio» → Permitir. Luego vuelve a pulsar «Añadir a inicio».",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blocked = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Abrir ajustes de la app") }
            },
            dismissButton = { TextButton(onClick = { blocked = false }) { Text("Cerrar") } },
        )
    }
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface).padding(16.dp)) {
        Text(
            "Android no deja cambiar el icono de una app instalada, pero sí crear un acceso directo con el nombre y la imagen que " +
                "quieras. Luego puedes quitar el icono original de la pantalla de inicio; seguirá en el cajón de apps.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CollectionArt(
                Shortcut.COVER_KEY, emptyList(),
                Modifier.size(64.dp).clickable { actions.pickCover(Shortcut.COVER_KEY) }, corner = 16.dp,
            )
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.pickCover(Shortcut.COVER_KEY) }) { Text("Elegir imagen") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    Graph.prefs.shortcutName = name.trim()
                    if (Shortcut.pin(context, name.trim())) actions.toast("Acceso directo actualizado") else waiting = System.currentTimeMillis()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.mint, contentColor = Palette.bg),
            ) { Text("Añadir a inicio") }
        }
    }
}

@Composable
private fun Toggle(pref: BoolPref, title: String, body: String) {
    val on by pref.flow.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, onCheckedChange = pref::set, colors = SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.bg))
    }
}
