package pl.tajchert.imagetoclipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.MaxDimension
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageTransformerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var transformer: ImageTransformer

    @Before
    fun setUp() {
        transformer = ImageTransformer()
    }

    /** Writes a bitmap to a file in [format]; gradient/noise controls compressibility. */
    private fun imageFile(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        quality: Int = 100,
        noise: Boolean = false,
        transparentCorner: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(42)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val color = when {
                    transparentCorner && x < 16 && y < 16 -> Color.TRANSPARENT
                    noise -> Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    else -> Color.rgb(x % 256, y % 256, (x + y) % 256)
                }
                bitmap.setPixel(x, y, color)
            }
        }
        val file = tmp.newFile(name)
        file.outputStream().use { bitmap.compress(format, quality, it) }
        return file
    }

    private val noTransform = CopySettings(format = OutputFormat.ORIGINAL, maxDimension = MaxDimension.ORIGINAL)

    @Test
    fun `original format and dimension pass through untouched`() {
        val source = imageFile("s.png", 64, 64, Bitmap.CompressFormat.PNG)
        val bytes = source.readBytes()

        val result = transformer.transform(source, "image/png", noTransform)

        assertEquals(source, result.file)
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals("image/png", result.mimeType)
        assertEquals(result.originalBytes, result.finalBytes)
    }

    @Test
    fun `gif passes through even when compression is on`() {
        val source = tmp.newFile("anim.gif").apply { writeBytes(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) }

        val result = transformer.transform(source, "image/gif", CopySettings())

        assertEquals(source, result.file)
        assertEquals("image/gif", result.mimeType)
    }

    @Test
    fun `webp conversion shrinks a png screenshot`() {
        // noise PNG is huge losslessly; lossy WebP must beat it
        val source = imageFile("s.png", 512, 512, Bitmap.CompressFormat.PNG, noise = true)

        val result = transformer.transform(source, "image/png", CopySettings(format = OutputFormat.WEBP, quality = 80))

        assertEquals("image/webp", result.mimeType)
        assertTrue("expected ${result.finalBytes} < ${result.originalBytes}", result.finalBytes < result.originalBytes)
        val decoded = BitmapFactory.decodeFile(result.file.path)
        assertEquals(512, decoded.width)
    }

    @Test
    fun `jpeg conversion flattens transparency to white`() {
        val source = imageFile("s.png", 256, 256, Bitmap.CompressFormat.PNG, noise = true, transparentCorner = true)

        val result = transformer.transform(source, "image/png", CopySettings(format = OutputFormat.JPEG, quality = 90))

        assertEquals("image/jpeg", result.mimeType)
        val decoded = BitmapFactory.decodeFile(result.file.path)
        val corner = decoded.getPixel(0, 0)
        assertTrue(
            "corner should be near-white",
            Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240,
        )
    }

    @Test
    fun `downscale bounds the long edge`() {
        val source = imageFile("s.jpg", 800, 400, Bitmap.CompressFormat.JPEG, quality = 90)

        val result = transformer.transform(
            source, "image/jpeg",
            CopySettings(format = OutputFormat.JPEG, quality = 90, maxDimension = MaxDimension.P1080),
        )

        // 800 < 1080: must NOT upscale
        val decoded = BitmapFactory.decodeFile(result.file.path)
        assertEquals(800, decoded.width)

        val big = imageFile("big.jpg", 2400, 1200, Bitmap.CompressFormat.JPEG, quality = 90)
        val resultBig = transformer.transform(
            big, "image/jpeg",
            CopySettings(format = OutputFormat.JPEG, quality = 90, maxDimension = MaxDimension.P1080),
        )
        val decodedBig = BitmapFactory.decodeFile(resultBig.file.path)
        assertEquals(1080, maxOf(decodedBig.width, decodedBig.height))
    }

    @Test
    fun `size guard keeps original when re-encode grows the file`() {
        // noise compressed hard as JPEG q50; re-encoding at q100 is guaranteed bigger
        val source = imageFile("noisy.jpg", 256, 256, Bitmap.CompressFormat.JPEG, quality = 50, noise = true)
        val bytes = source.readBytes()

        val result = transformer.transform(source, "image/jpeg", CopySettings(format = OutputFormat.JPEG, quality = 100))

        assertEquals(source, result.file)
        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(result.originalBytes, result.finalBytes)
    }

    @Test
    fun `corrupt input falls back to pass-through`() {
        val source = tmp.newFile("broken.png").apply { writeText("this is not an image") }

        val result = transformer.transform(source, "image/png", CopySettings())

        assertEquals(source, result.file)
        assertEquals("image/png", result.mimeType)
    }
}
