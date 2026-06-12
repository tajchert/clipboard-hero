package pl.tajchert.imagetoclipboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.MaxDimension
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageClipboardRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: ImageClipboardRepository

    @Before
    fun setUp() {
        // FileProvider caches its path strategy statically per authority, but Robolectric
        // gives every test a fresh temp filesDir — clear the cache so roots stay valid.
        FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        context = ApplicationProvider.getApplicationContext()
        repo = ImageClipboardRepository(context)
    }

    private fun registerSource(bytes: ByteArray, uri: String = "content://test.source/img"): Uri {
        val sourceUri = Uri.parse(uri)
        shadowOf(context.contentResolver).registerInputStream(sourceUri, ByteArrayInputStream(bytes))
        return sourceUri
    }

    private val noTransform = CopySettings(format = OutputFormat.ORIGINAL, maxDimension = MaxDimension.ORIGINAL)

    /** Noise PNG: large losslessly, so lossy conversion reliably shrinks it (avoids the size guard). */
    private fun pngBytes(size: Int = 128): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = kotlin.random.Random(42)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `copies bytes exactly without re-encoding`() {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }
        val uri = registerSource(bytes)

        val result = repo.copyToClipboard(uri, "image/png", noTransform)

        val copied = result.getOrThrow()
        assertArrayEquals(bytes, copied.file.readBytes())
    }

    @Test
    fun `maps mime type to file extension`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/jpeg", noTransform).getOrThrow()

        assertEquals("clip.jpg", copied.file.name)
        assertEquals("image/jpeg", copied.mimeType)
    }

    @Test
    fun `unknown image subtype falls back to bin extension and keeps mime`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/x-weird!type", noTransform).getOrThrow()

        assertEquals("clip.bin", copied.file.name)
        assertEquals("image/x-weird!type", copied.mimeType)
    }

    @Test
    fun `keeps only the latest copied file`() {
        repo.copyToClipboard(registerSource(byteArrayOf(1), "content://test.source/a"), "image/png", noTransform).getOrThrow()
        repo.copyToClipboard(registerSource(byteArrayOf(2), "content://test.source/b"), "image/jpeg", noTransform).getOrThrow()

        val clipsDir = File(context.filesDir, "clips")
        assertEquals(listOf("clip.jpg"), clipsDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `puts our FileProvider uri with correct mime on the clipboard`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/png", noTransform).getOrThrow()

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals(copied.providerUri, clip.getItemAt(0).uri)
        assertEquals("pl.tajchert.imagetoclipboard.fileprovider", copied.providerUri.authority)
        assertEquals("image/png", clip.description.getMimeType(0))
    }

    @Test
    fun `unreadable uri returns failure`() {
        val result = repo.copyToClipboard(Uri.parse("content://nope/missing"), "image/png", noTransform)

        assertTrue(result.isFailure)
    }

    @Test
    fun `latestImage returns null when nothing copied`() {
        assertNull(repo.latestImage())
    }

    @Test
    fun `latestImage returns the stored image after a copy`() {
        val uri = registerSource(byteArrayOf(9, 9, 9))
        repo.copyToClipboard(uri, "image/webp", noTransform).getOrThrow()

        val latest = repo.latestImage()

        assertNotNull(latest)
        assertEquals("clip.webp", latest!!.file.name)
        assertEquals("image/webp", latest.mimeType)
    }

    @Test
    fun `default settings convert png to webp clip`() {
        val uri = registerSource(pngBytes(256))

        val copied = repo.copyToClipboard(uri, "image/png", CopySettings()).getOrThrow()

        assertEquals("clip.webp", copied.file.name)
        assertEquals("image/webp", copied.mimeType)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("image/webp", clipboard.primaryClip!!.description.getMimeType(0))
        assertTrue(copied.finalBytes > 0)
        assertTrue(copied.originalBytes >= copied.finalBytes)
    }

    @Test
    fun `clips dir holds exactly one file after transform`() {
        repo.copyToClipboard(registerSource(pngBytes(), "content://test.source/a"), "image/png", CopySettings()).getOrThrow()
        repo.copyToClipboard(registerSource(pngBytes(), "content://test.source/b"), "image/png", noTransform).getOrThrow()

        val clipsDir = File(context.filesDir, "clips")
        assertEquals(listOf("clip.png"), clipsDir.listFiles()!!.map { it.name })
    }
}
