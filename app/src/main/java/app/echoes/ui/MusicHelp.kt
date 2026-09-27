package app.echoes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.echoes.R

/** The one-page answer to "how do I get music in here?", shown on first launch and from the ⓘ in Descargar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHelp(onDismiss: () -> Unit, onDownload: (() -> Unit)? = null, onPortal: () -> Unit) {
    val name = stringResource(R.string.app_name)
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.surfaceHigh, contentColor = Palette.text) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Cómo meter música", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("Cada carpeta con música es una lista. Cualquiera de estas vías sirve.", color = Palette.muted)
            Spacer(Modifier.height(18.dp))

            Way(Icons.Rounded.Folder, Palette.mint, "La que ya tienes en el móvil", "Aparece sola al abrir la app. No hay que hacer nada.")
            Way(
                Icons.Rounded.Share, Palette.pink, "Compartir desde YouTube o Spotify",
                "En su app: Compartir → $name. Sirve con una canción, un álbum o una playlist pública. Eliges la lista y se descarga con su portada.",
            )
            Way(
                Icons.Rounded.Link, Palette.amber, "Pegar un enlace",
                "Botón ⬇ de Inicio → pega el enlace. Una playlist de Spotify se puede vincular: al sincronizar baja solo lo nuevo (hasta 100 canciones).",
            )
            Way(
                Icons.Rounded.QueueMusic, Palette.mint, "Playlists largas de Spotify",
                "Abre exportify.net en el navegador, entra con tu cuenta de Spotify y exporta la playlist. Luego, en Descargar → «Importar lista (CSV)» y elige el archivo.",
            ) {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://exportify.net"))) }) { Text("Abrir exportify.net") }
            }
            Way(
                Icons.Rounded.Lan, Palette.pink, "Desde el ordenador",
                "Ajustes → Portal. Abre la dirección en el navegador del PC (misma wifi), escribe el código y arrastra archivos o pega enlaces.",
            ) {
                TextButton(onClick = onPortal) { Text("Ir al Portal") }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                if (onDownload != null) TextButton(onClick = onDownload) { Text("Pegar un enlace") }
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Palette.mint, contentColor = Color.Black)) { Text("Entendido") }
            }
        }
    }
}

@Composable
private fun Way(icon: ImageVector, tint: Color, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(bottom = 16.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}
