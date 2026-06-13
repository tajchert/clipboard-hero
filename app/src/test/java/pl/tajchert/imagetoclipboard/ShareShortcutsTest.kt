package pl.tajchert.imagetoclipboard

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareShortcutsTest {

    @Test
    fun `publish pushes a long-lived sharing shortcut`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        ShareShortcuts.publish(context)

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        val ours = shortcuts.firstOrNull { it.id == "copy-to-clipboard" }
        assertTrue("shortcut missing", ours != null)
        assertTrue(ours!!.categories!!.contains(ShareShortcuts.CATEGORY))
    }
}
