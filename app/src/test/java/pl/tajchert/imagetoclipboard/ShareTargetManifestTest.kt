package pl.tajchert.imagetoclipboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTargetManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resolvesShare(mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeType)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any {
                ComponentName(it.activityInfo.packageName, it.activityInfo.name) ==
                    ComponentName(context, ShareReceiverActivity::class.java)
            }
    }

    @Test
    fun `appears in share menu for images`() {
        assertTrue(resolvesShare("image/png"))
        assertTrue(resolvesShare("image/jpeg"))
        assertTrue(resolvesShare("image/gif"))
    }

    @Test
    fun `does not appear in share menu for text or video`() {
        assertFalse(resolvesShare("text/plain"))
        assertFalse(resolvesShare("video/mp4"))
    }
}
