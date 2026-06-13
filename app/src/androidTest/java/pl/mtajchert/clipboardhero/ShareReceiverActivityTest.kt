package pl.mtajchert.clipboardhero

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareReceiverActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val targetCtx: Context get() = instr.targetContext

    private var sourceUri: Uri? = null

    @Before
    fun setUp() {
        // Deterministic: defaults (JPEG, showConfirmation = true) in the app's real DataStore.
        runBlocking { SettingsRepository.create(targetCtx).update(CopySettings(showConfirmation = true)) }
    }

    @After
    fun tearDown() {
        sourceUri?.let { targetCtx.contentResolver.delete(it, null, null) }
    }

    // A MediaStore image (authority "media", not our FileProvider) owned by the app's
    // own UID, so it is readable without a cross-package grant and passes isSafeSource.
    private fun mediaStoreSource(): Uri {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) {
            bmp.setPixel(x, y, Color.rgb(x * 4, y * 4, 128))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "cliphero_test_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val resolver = targetCtx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to insert test image into MediaStore")
        resolver.openOutputStream(uri).use { out ->
            checkNotNull(out) { "null output stream for $uri" }
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        sourceUri = uri
        return uri
    }

    @Test
    fun share_copies_to_clipboard_and_shows_card() {
        val uri = mediaStoreSource()
        val intent = Intent(targetCtx, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<ShareReceiverActivity>(intent).use {
            val success = targetCtx.getString(R.string.copied_success)
            // Wait for the async copy to surface the card (activity is foreground here).
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(success).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(success).assertIsDisplayed()

            // Time-sensitive (card visible == activity focused): read the clipboard now.
            var clipUri: Uri? = null
            instr.runOnMainSync {
                clipUri = targetCtx.getSystemService(ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.uri
            }
            assertEquals("pl.mtajchert.clipboardhero.fileprovider", clipUri?.authority)

            // Filesystem assertion persists regardless of timing.
            val clips = File(targetCtx.filesDir, "clips").listFiles().orEmpty()
            assertTrue("expected a clip_ file in clips/", clips.any { it.name.startsWith("clip_") })
        }
    }
}
