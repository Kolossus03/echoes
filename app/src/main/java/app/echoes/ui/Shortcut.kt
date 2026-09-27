package app.echoes.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.echoes.Graph
import app.echoes.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android fixes an installed app's name and icon, but lets it pin a home-screen shortcut with any
 * label and picture. That is how the app gets a name and icon of the user's choosing.
 */
object Shortcut {
    const val COVER_KEY = "app:shortcut"
    private const val ID = "custom"

    fun supported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Set when the launcher confirms a pin; some (HyperOS) silently drop requests the user once denied. */
    val confirmed = MutableStateFlow(0L)

    /**
     * Pins the shortcut, or refreshes it in place if it is already on the home screen. Returns
     * false when the request went out and only a later [confirmed] tick will say it landed.
     */
    fun pin(context: Context, name: String): Boolean {
        val picture = Graph.covers.file(COVER_KEY)?.let { BitmapFactory.decodeFile(it.path) }
        val info = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(name)
            .setIcon(picture?.let { IconCompat.createWithAdaptiveBitmap(adaptive(it)) } ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == ID }
        if (pinned) return ShortcutManagerCompat.updateShortcuts(context, listOf(info))
        val callback = PendingIntent.getBroadcast(
            context, 0, Intent(context, Pinned::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ShortcutManagerCompat.requestPinShortcut(context, info, callback.intentSender)
        return false
    }

    class Pinned : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            confirmed.value = System.currentTimeMillis()
        }
    }

    /**
     * Launchers mask adaptive icons to roughly the middle two thirds, so the picture goes there,
     * framed by its own corner colour, instead of being cropped by the mask.
     */
    private fun adaptive(src: Bitmap): Bitmap {
        val size = 432
        val inner = 300
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(src.getPixel(0, 0) or 0xFF000000.toInt())
        val o = (size - inner) / 2
        canvas.drawBitmap(src, null, Rect(o, o, o + inner, o + inner), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
