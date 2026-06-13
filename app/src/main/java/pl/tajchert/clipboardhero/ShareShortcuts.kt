package pl.tajchert.clipboardhero

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Publishes the dynamic sharing shortcut that lets the system rank this app in the
 * share sheet's direct-share row. Re-pushed after each copy as a usage signal.
 */
object ShareShortcuts {

    const val CATEGORY = "pl.tajchert.clipboardhero.SHARE_TARGET"
    private const val SHORTCUT_ID = "copy-to-clipboard"

    fun publish(context: Context) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.share_target_label))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
                )
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }
}
