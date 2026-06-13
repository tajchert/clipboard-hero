package pl.mtajchert.clipboardhero

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.MaxDimension
import pl.mtajchert.clipboardhero.settings.OutputFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageClipboardRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: ImageClipboardRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        // FileProvider caches its path strategy statically per authority, but Robolectric
        // gives every test a fresh temp filesDir — clear the cache so roots stay valid.
        FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        context = ApplicationProvider.getApplicationContext()
        repo = ImageClipboardRepository(context, clock = { now })
    }

    private var uriCounter = 0

    private fun registerSource(bytes: ByteArray): Uri {
        val sourceUri = Uri.parse("content://test.source/img${uriCounter++}")
        shadowOf(context.contentResolver).registerInputStream(sourceUri, ByteArrayInputStream(bytes))
        return sourceUri
    }

    private val noTransform = CopySettings(format = OutputFormat.ORIGINAL, maxDimension = MaxDimension.ORIGINAL)
    private val keepAll = RetentionPolicy(maxItems = 10, ttlMillis = null)

    private fun copy(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        mime: String = "image/png",
        settings: CopySettings = noTransform,
        retention: RetentionPolicy = keepAll,
    ): CopiedImage {
        now += 1 // unique timestamp per copy
        return repo.copyToClipboard(registerSource(bytes), mime, settings, retention).getOrThrow()
    }

    /** Noise PNG: large losslessly, so lossy conversion reliably shrinks it (avoids the size guard). */
    private fun pngBytes(size: Int = 128): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = kotlin.random.Random(42)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val clipsDir get() = File(context.filesDir, "clips")

    @Test
    fun `copies bytes exactly without re-encoding`() {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }

        val copied = copy(bytes)

        assertArrayEquals(bytes, copied.file.readBytes())
    }

    @Test
    fun `clip file is timestamped with mime-mapped extension`() {
        val copied = copy(mime = "image/jpeg")

        assertEquals("clip_$now.jpg", copied.file.name)
        assertEquals("image/jpeg", copied.mimeType)
        assertEquals(now, copied.timestamp)
    }

    @Test
    fun `unknown image subtype falls back to bin extension and keeps mime`() {
        val copied = copy(mime = "image/x-weird!type")

        assertTrue(copied.file.name.endsWith(".bin"))
        assertEquals("image/x-weird!type", copied.mimeType)
    }

    @Test
    fun `puts our FileProvider uri with correct mime on the clipboard`() {
        val copied = copy(mime = "image/png")

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals(copied.providerUri, clip.getItemAt(0).uri)
        assertEquals("pl.mtajchert.clipboardhero.fileprovider", copied.providerUri.authority)
        assertEquals("image/png", clip.description.getMimeType(0))
    }

    @Test
    fun `unreadable uri returns failure`() {
        val result = repo.copyToClipboard(Uri.parse("content://nope/missing"), "image/png", noTransform, keepAll)

        assertTrue(result.isFailure)
    }

    @Test
    fun `default settings convert png to jpeg clip`() {
        val copied = copy(pngBytes(256), settings = CopySettings())

        assertTrue(copied.file.name.endsWith(".jpg"))
        assertEquals("image/jpeg", copied.mimeType)
        assertEquals(
            "image/jpeg",
            context.getSystemService(ClipboardManager::class.java).primaryClip!!.description.getMimeType(0),
        )
        assertTrue(copied.originalBytes >= copied.finalBytes)
    }

    @Test
    fun `history returns newest first`() {
        copy(byteArrayOf(1))
        copy(byteArrayOf(2))
        val newest = copy(byteArrayOf(3))

        val history = repo.history(keepAll)

        assertEquals(3, history.size)
        assertEquals(newest.file.name, history[0].file.name)
        assertTrue(history[0].timestamp > history[1].timestamp)
        assertTrue(history[1].timestamp > history[2].timestamp)
    }

    @Test
    fun `history empty when nothing copied`() {
        assertEquals(emptyList<CopiedImage>(), repo.history(keepAll))
    }

    @Test
    fun `eleventh copy evicts the oldest`() {
        val first = copy(byteArrayOf(0))
        repeat(10) { copy(byteArrayOf((it + 1).toByte())) }

        val history = repo.history(keepAll)

        assertEquals(10, history.size)
        assertTrue(history.none { it.file.name == first.file.name })
    }

    @Test
    fun `history off keeps only the latest`() {
        val single = RetentionPolicy(maxItems = 1, ttlMillis = null)
        copy(byteArrayOf(1), retention = single)
        val latest = copy(byteArrayOf(2), retention = single)

        assertEquals(listOf(latest.file.name), clipsDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `ttl expiry prunes on read`() {
        val ttl = RetentionPolicy(maxItems = 10, ttlMillis = 3_600_000L)
        copy(byteArrayOf(1), retention = ttl)

        now += 3_600_001L

        assertEquals(emptyList<CopiedImage>(), repo.history(ttl))
        assertEquals(0, clipsDir.listFiles()!!.size)
    }

    @Test
    fun `legacy clip file appears in history`() {
        clipsDir.mkdirs()
        File(clipsDir, "clip.png").writeBytes(byteArrayOf(7))

        val history = repo.history(keepAll)

        assertEquals(1, history.size)
        assertEquals("image/png", history[0].mimeType)
    }

    @Test
    fun `delete removes a single item`() {
        val a = copy(byteArrayOf(1))
        copy(byteArrayOf(2))

        repo.delete(a)

        assertEquals(1, repo.history(keepAll).size)
    }

    @Test
    fun `clearAll removes files and clears the clipboard`() {
        copy(byteArrayOf(1))
        copy(byteArrayOf(2))

        repo.clearAll()

        assertEquals(emptyList<CopiedImage>(), repo.history(keepAll))
        assertNull(context.getSystemService(ClipboardManager::class.java).primaryClip)
    }
}
