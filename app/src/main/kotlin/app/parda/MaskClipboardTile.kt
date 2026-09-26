package app.parda

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "Mask clipboard" quick-settings tile: copy text anywhere, pull down the shade, tap. The
 * sheet opens over the current app, since Android only lets a visible app read the clipboard.
 */
class MaskClipboardTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, QuickMaskActivity::class.java)
            .setAction(QuickMaskActivity.ACTION_MASK_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
