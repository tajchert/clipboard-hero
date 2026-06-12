# Compression & Format Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** User-configurable compression for clipboard images — output format (Original/WebP/JPEG), quality, max dimension — persisted in DataStore, applied in the copy pipeline, surfaced in the UI with size feedback.

**Architecture:** A `SettingsRepository` (Preferences DataStore) holds `CopySettings`. An `ImageTransformer` sits between "stream source bytes" and "store + clip" inside `ImageClipboardRepository`: pass-through for Original/GIF, otherwise decode (sampled, EXIF-rotated) → downscale → encode, with a size guard and pass-through fallback on any failure. UI: settings card on the main screen, size subtitle on the confirmation card.

**Tech Stack:** Existing stack + `androidx.datastore:datastore-preferences:1.1.1`, `androidx.exifinterface:1.3.7`.

**Spec:** `docs/superpowers/specs/2026-06-13-compression-settings-design.md`

---

## File Structure

```
gradle/libs.versions.toml                          — add datastore, exifinterface
app/build.gradle.kts                               — add the two deps
app/src/main/java/pl/tajchert/imagetoclipboard/
    settings/CopySettings.kt                       — model + enums (new)
    settings/SettingsRepository.kt                 — DataStore wrapper (new)
    ImageTransformer.kt                            — transform pipeline (new)
    ImageClipboardRepository.kt                    — tmp-file step + transformer + sizes (modify)
    ShareReceiverActivity.kt                       — read settings, pass sizes (modify)
    MainActivity.kt                                — settings state wiring (modify)
    ui/ConfirmationSheet.kt                        — size subtitle (modify)
    ui/MainScreen.kt                               — Copy settings card (modify)
app/src/main/res/values/strings.xml               — new strings (modify)
app/src/test/java/pl/tajchert/imagetoclipboard/
    settings/SettingsRepositoryTest.kt             — new
    ImageTransformerTest.kt                        — new
    ImageClipboardRepositoryTest.kt                — update signatures + 2 new tests
```

---

### Task 1: Settings model + SettingsRepository (TDD)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/settings/CopySettings.kt`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/settings/SettingsRepository.kt`
- Test: `app/src/test/java/pl/tajchert/imagetoclipboard/settings/SettingsRepositoryTest.kt`

- [ ] **Step 1: Add dependencies**

In `gradle/libs.versions.toml` `[versions]` add:
```toml
datastore = "1.1.1"
exifinterface = "1.3.7"
```
In `[libraries]` add:
```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }
```
In `app/build.gradle.kts` `dependencies` add:
```kotlin
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
```

- [ ] **Step 2: Write `settings/CopySettings.kt`**

```kotlin
package pl.tajchert.imagetoclipboard.settings

enum class OutputFormat { ORIGINAL, WEBP, JPEG }

enum class MaxDimension(val px: Int?) {
    ORIGINAL(null),
    P2048(2048),
    P1080(1080),
}

data class CopySettings(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 90,
    val maxDimension: MaxDimension = MaxDimension.ORIGINAL,
)
```

- [ ] **Step 3: Write the failing tests**

`app/src/test/java/pl/tajchert/imagetoclipboard/settings/SettingsRepositoryTest.kt`:

```kotlin
package pl.tajchert.imagetoclipboard.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("test.preferences_pb")
        }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `emits defaults when nothing stored`() = runBlocking {
        val repo = SettingsRepository(newDataStore())

        assertEquals(CopySettings(), repo.settings.first())
    }

    @Test
    fun `update and read round-trip`() = runBlocking {
        val repo = SettingsRepository(newDataStore())
        val wanted = CopySettings(
            format = OutputFormat.JPEG,
            quality = 65,
            maxDimension = MaxDimension.P1080,
        )

        repo.update(wanted)

        assertEquals(wanted, repo.settings.first())
    }

    @Test
    fun `invalid stored enum falls back to defaults`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("format")] = "BOGUS"
            prefs[stringPreferencesKey("max_dimension")] = "ALSO_BOGUS"
        }
        val repo = SettingsRepository(dataStore)

        assertEquals(CopySettings(), repo.settings.first())
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.settings.SettingsRepositoryTest"`
Expected: compilation FAILURE — `SettingsRepository` unresolved.

- [ ] **Step 5: Write `settings/SettingsRepository.kt`**

```kotlin
package pl.tajchert.imagetoclipboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<CopySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = CopySettings()
            CopySettings(
                format = prefs[KEY_FORMAT].toEnumOrNull<OutputFormat>() ?: defaults.format,
                quality = prefs[KEY_QUALITY]?.coerceIn(50, 100) ?: defaults.quality,
                maxDimension = prefs[KEY_MAX_DIMENSION].toEnumOrNull<MaxDimension>() ?: defaults.maxDimension,
            )
        }

    suspend fun update(settings: CopySettings) {
        dataStore.edit { prefs ->
            prefs[KEY_FORMAT] = settings.format.name
            prefs[KEY_QUALITY] = settings.quality
            prefs[KEY_MAX_DIMENSION] = settings.maxDimension.name
        }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.settingsDataStore)

        private val KEY_FORMAT = stringPreferencesKey("format")
        private val KEY_QUALITY = intPreferencesKey("quality")
        private val KEY_MAX_DIMENSION = stringPreferencesKey("max_dimension")

        private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
            this?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.settings.SettingsRepositoryTest"`
Expected: 3 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: CopySettings model + DataStore-backed SettingsRepository"
```

---

### Task 2: ImageTransformer (TDD)

**Files:**
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ImageTransformer.kt`
- Test: `app/src/test/java/pl/tajchert/imagetoclipboard/ImageTransformerTest.kt`

- [ ] **Step 1: Write the failing tests**

Note: tests generate real image bytes with `Bitmap.compress` (Robolectric 4.14 runs real native graphics, so codecs work). If WebP encoding turns out unsupported in Robolectric, mark that single test `@Ignore` with a comment and verify WebP on the emulator in the final task — do NOT fake the assertion.

```kotlin
package pl.tajchert.imagetoclipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
        transparent: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(42)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val color = when {
                    transparent -> Color.TRANSPARENT
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
        val source = imageFile("s.png", 512, 512, Bitmap.CompressFormat.PNG)

        val result = transformer.transform(source, "image/png", CopySettings(format = OutputFormat.WEBP, quality = 80))

        assertEquals("image/webp", result.mimeType)
        assertTrue("expected ${result.finalBytes} < ${result.originalBytes}", result.finalBytes < result.originalBytes)
        val decoded = BitmapFactory.decodeFile(result.file.path)
        assertEquals(512, decoded.width)
    }

    @Test
    fun `jpeg conversion flattens transparency to white`() {
        val source = imageFile("s.png", 32, 32, Bitmap.CompressFormat.PNG, transparent = true)

        val result = transformer.transform(source, "image/png", CopySettings(format = OutputFormat.JPEG, quality = 90))

        assertEquals("image/jpeg", result.mimeType)
        val decoded = BitmapFactory.decodeFile(result.file.path)
        val corner = decoded.getPixel(0, 0)
        assertTrue("corner should be near-white", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ImageTransformerTest"`
Expected: compilation FAILURE — `ImageTransformer` unresolved.

- [ ] **Step 3: Write `ImageTransformer.kt`**

```kotlin
package pl.tajchert.imagetoclipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class TransformResult(
    val file: File,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
)

class ImageTransformer {

    /**
     * Applies the configured compression to [source]. On any failure, or when the
     * settings ask for nothing, returns [source] untouched — compression must never
     * break the copy.
     */
    fun transform(source: File, sourceMime: String, settings: CopySettings): TransformResult {
        val originalBytes = source.length()
        val passThrough = TransformResult(source, sourceMime, originalBytes, originalBytes)

        val wantsFormatChange = settings.format != OutputFormat.ORIGINAL
        val targetPx = settings.maxDimension.px
        if (!wantsFormatChange && targetPx == null) return passThrough
        if (sourceMime == "image/gif") return passThrough // re-encoding destroys animation

        return runCatching {
            val decoded = decode(source, targetPx) ?: return passThrough
            val scaled = downscale(decoded.bitmap, targetPx)
            val wasResized = decoded.sampled || scaled !== decoded.bitmap

            val encoding = encodingFor(settings.format, sourceMime)
            val prepared = if (encoding.mimeType == "image/jpeg") flattenAlpha(scaled) else scaled
            val output = File(source.parentFile, "transformed.${encoding.extension}")
            output.outputStream().use { sink ->
                if (!prepared.compress(encoding.format, settings.quality, sink)) {
                    error("Bitmap.compress returned false for ${encoding.mimeType}")
                }
            }

            if (!wasResized && output.length() >= originalBytes) {
                output.delete() // compression must never make things worse
                passThrough
            } else {
                TransformResult(output, encoding.mimeType, originalBytes, output.length())
            }
        }.getOrElse { failure ->
            Log.w(TAG, "Transform failed, copying original", failure)
            passThrough
        }
    }

    private class Decoded(val bitmap: Bitmap, val sampled: Boolean)
    private class Encoding(val format: Bitmap.CompressFormat, val mimeType: String, val extension: String)

    private fun decode(source: File, targetPx: Int?): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        if (targetPx != null) {
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= targetPx) sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        return Decoded(applyExifRotation(source, bitmap), sampled = sampleSize > 1)
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap = runCatching {
        val orientation = ExifInterface(source)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)

    private fun downscale(bitmap: Bitmap, targetPx: Int?): Bitmap {
        if (targetPx == null) return bitmap
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= targetPx) return bitmap
        val scale = targetPx.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun encodingFor(format: OutputFormat, sourceMime: String): Encoding = when (format) {
        OutputFormat.WEBP -> Encoding(webpCompressFormat(), "image/webp", "webp")
        OutputFormat.JPEG -> Encoding(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
        // ORIGINAL + downscale: keep the format family — PNG stays lossless, the rest becomes JPEG
        OutputFormat.ORIGINAL ->
            if (sourceMime == "image/png") Encoding(Bitmap.CompressFormat.PNG, "image/png", "png")
            else Encoding(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
    }

    private fun webpCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun flattenAlpha(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private companion object {
        const val TAG = "ImageTransformer"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ImageTransformerTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: ImageTransformer — format conversion, downscale, size guard, fallbacks"
```

---

### Task 3: Wire transformer into ImageClipboardRepository (TDD)

**Files:**
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/ImageClipboardRepository.kt`
- Test: `app/src/test/java/pl/tajchert/imagetoclipboard/ImageClipboardRepositoryTest.kt`

- [ ] **Step 1: Update tests — existing ones pass `noTransform`, two new tests**

In `ImageClipboardRepositoryTest.kt`, add imports:
```kotlin
import android.graphics.Bitmap
import android.graphics.Color
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.MaxDimension
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import java.io.ByteArrayOutputStream
```
Add to the class:
```kotlin
    private val noTransform = CopySettings(format = OutputFormat.ORIGINAL, maxDimension = MaxDimension.ORIGINAL)

    private fun pngBytes(size: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, 128))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
```
Change EVERY existing `repo.copyToClipboard(<uri>, <mime>)` call to `repo.copyToClipboard(<uri>, <mime>, noTransform)` (8 call sites across the existing tests).

Add two new tests:
```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ImageClipboardRepositoryTest"`
Expected: compilation FAILURE — `copyToClipboard` has no 3-arg overload.

- [ ] **Step 3: Update `ImageClipboardRepository.kt`**

Full new content:

```kotlin
package pl.tajchert.imagetoclipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import pl.tajchert.imagetoclipboard.settings.CopySettings
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
)

class ImageClipboardRepository(
    private val context: Context,
    private val transformer: ImageTransformer = ImageTransformer(),
) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(
        sourceUri: Uri,
        fallbackMimeType: String?,
        settings: CopySettings,
    ): Result<CopiedImage> = runCatching {
        val resolver = context.contentResolver
        val sourceMime = resolver.getType(sourceUri)?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: fallbackMimeType?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: GENERIC_IMAGE_MIME

        clipsDir.mkdirs()
        val incoming = File(clipsDir, "incoming.tmp")
        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open input stream for $sourceUri")
        input.use { source ->
            incoming.outputStream().use { sink -> source.copyTo(sink) }
        }

        val transformed = transformer.transform(incoming, sourceMime, settings)

        val target = File(clipsDir, "clip.${extensionFor(transformed.mimeType)}")
        clipsDir.listFiles()?.filter { it != transformed.file }?.forEach { it.delete() }
        if (!transformed.file.renameTo(target)) throw IOException("Cannot move clip into place")

        val copied = CopiedImage(
            file = target,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, target),
            mimeType = transformed.mimeType,
            originalBytes = transformed.originalBytes,
            finalBytes = transformed.finalBytes,
        )
        setClip(copied)
        copied
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun latestImage(): CopiedImage? {
        val file = clipsDir.listFiles()?.firstOrNull { it.name.startsWith("clip.") } ?: return null
        return CopiedImage(
            file = file,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, file),
            mimeType = mimeTypeFor(file.extension),
            originalBytes = file.length(),
            finalBytes = file.length(),
        )
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun extensionFor(mimeType: String): String = MIME_TO_EXTENSION[mimeType] ?: "bin"

    private fun mimeTypeFor(extension: String): String =
        MIME_TO_EXTENSION.entries.firstOrNull { it.value == extension }?.key ?: GENERIC_IMAGE_MIME

    companion object {
        const val AUTHORITY = "pl.tajchert.imagetoclipboard.fileprovider"
        private const val GENERIC_IMAGE_MIME = "image/*"
        private val MIME_TO_EXTENSION = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/bmp" to "bmp",
            "image/*" to "img",
        )
    }
}
```

Note the `latestImage` filter (`startsWith("clip.")`) — a leftover `incoming.tmp` from a crashed copy must never be treated as the latest image.

This breaks `ShareReceiverActivity` compilation (3-arg call) — fix in the next task's step, but to keep THIS task compiling, apply the minimal `ShareReceiverActivity` change now:

In `ShareReceiverActivity.kt`, add imports:
```kotlin
import kotlinx.coroutines.flow.first
import pl.tajchert.imagetoclipboard.settings.SettingsRepository
```
Replace the `lifecycleScope.launch` block with:
```kotlin
        lifecycleScope.launch {
            val repository = ImageClipboardRepository(applicationContext)
            copyState = withContext(Dispatchers.IO) {
                val settings = SettingsRepository.create(applicationContext).settings.first()
                repository.copyToClipboard(sourceUri, intent.type, settings)
                    .map { CopyState.Success(Thumbnails.decode(it.file)) }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
            }
        }
```
And in `MainActivity.kt` no change needed yet (`latestImage`/`recopy` signatures unchanged).

- [ ] **Step 4: Run all tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests PASS (8 repo + 2 new repo + 2 manifest + 3 settings + 7 transformer = 22).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: apply compression settings in the copy pipeline"
```

---

### Task 4: Confirmation card size subtitle

**Files:**
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/ui/ConfirmationSheet.kt`
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/ShareReceiverActivity.kt`

- [ ] **Step 1: Extend `CopyState.Success` and render the subtitle**

In `ConfirmationSheet.kt`:

Replace the `CopyState` declaration:
```kotlin
sealed interface CopyState {
    data object Pending : CopyState
    data class Success(
        val thumbnail: Bitmap?,
        val originalBytes: Long,
        val finalBytes: Long,
    ) : CopyState
    data object Error : CopyState
}
```

Add imports:
```kotlin
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
```

Replace the `Success` branch in `ConfirmationSheet`:
```kotlin
            is CopyState.Success -> ResultCard(
                thumbnail = state.thumbnail,
                message = stringResource(R.string.copied_success),
                subtitle = sizeSubtitle(state.originalBytes, state.finalBytes),
                isError = false,
            )
```
and the `Error` branch gains `subtitle = null`.

Add the helper:
```kotlin
@Composable
private fun sizeSubtitle(originalBytes: Long, finalBytes: Long): String? {
    if (finalBytes <= 0) return null
    val context = LocalContext.current
    val final = Formatter.formatShortFileSize(context, finalBytes)
    // show the arrow only when compression changed the size meaningfully (>1%)
    return if (originalBytes > 0 && kotlin.math.abs(originalBytes - finalBytes) > originalBytes / 100) {
        "${Formatter.formatShortFileSize(context, originalBytes)} → $final"
    } else {
        final
    }
}
```

Update `ResultCard` signature and text block:
```kotlin
@Composable
private fun ResultCard(thumbnail: Bitmap?, message: String, subtitle: String?, isError: Boolean) {
```
and replace the trailing `Text(...)` with:
```kotlin
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = message, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
```

- [ ] **Step 2: Pass the sizes from `ShareReceiverActivity`**

Replace the `.map { ... }` line:
```kotlin
                    .map { CopyState.Success(Thumbnails.decode(it.file), it.originalBytes, it.finalBytes) }
```

- [ ] **Step 3: Build + tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 22 tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: show copied size (and savings) on the confirmation card"
```

---

### Task 5: Copy settings card on the main screen

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/ui/MainScreen.kt`
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/MainActivity.kt`

- [ ] **Step 1: Add strings**

Inside `<resources>` in `strings.xml`:
```xml
    <string name="settings_title">Copy settings</string>
    <string name="format_label">Format</string>
    <string name="format_original">Original</string>
    <string name="format_webp">WebP</string>
    <string name="format_jpeg">JPEG</string>
    <string name="quality_label">Quality</string>
    <string name="max_size_label">Max size</string>
    <string name="max_size_original">Original</string>
    <string name="max_size_2048">2048 px</string>
    <string name="max_size_1080">1080 px</string>
```

- [ ] **Step 2: Add the settings card to `MainScreen.kt`**

Add imports:
```kotlin
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.MaxDimension
import pl.tajchert.imagetoclipboard.settings.OutputFormat
import kotlin.math.roundToInt
```

Change `MainScreen` signature and body:
```kotlin
@Composable
fun MainScreen(
    settings: CopySettings,
    onSettingsChange: (CopySettings) -> Unit,
    lastCopied: LastCopiedUi?,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.howto_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            HowToSteps()
            SettingsCard(settings = settings, onSettingsChange = onSettingsChange)
            if (lastCopied != null) {
                LastCopiedCard(lastCopied)
            }
        }
    }
}
```

Add the card and its pieces:
```kotlin
@Composable
private fun SettingsCard(settings: CopySettings, onSettingsChange: (CopySettings) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            LabeledSegmentedRow(
                label = stringResource(R.string.format_label),
                entries = listOf(
                    OutputFormat.ORIGINAL to stringResource(R.string.format_original),
                    OutputFormat.WEBP to stringResource(R.string.format_webp),
                    OutputFormat.JPEG to stringResource(R.string.format_jpeg),
                ),
                selected = settings.format,
                onSelect = { onSettingsChange(settings.copy(format = it)) },
            )

            if (settings.format != OutputFormat.ORIGINAL) {
                Column {
                    Text(
                        text = "${stringResource(R.string.quality_label)}: ${settings.quality}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.quality.toFloat(),
                        onValueChange = {
                            onSettingsChange(settings.copy(quality = (it / 5).roundToInt() * 5))
                        },
                        valueRange = 50f..100f,
                        steps = 9,
                    )
                }
            }

            LabeledSegmentedRow(
                label = stringResource(R.string.max_size_label),
                entries = listOf(
                    MaxDimension.ORIGINAL to stringResource(R.string.max_size_original),
                    MaxDimension.P2048 to stringResource(R.string.max_size_2048),
                    MaxDimension.P1080 to stringResource(R.string.max_size_1080),
                ),
                selected = settings.maxDimension,
                onSelect = { onSettingsChange(settings.copy(maxDimension = it)) },
            )
        }
    }
}

@Composable
private fun <T> LabeledSegmentedRow(
    label: String,
    entries: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, (value, title) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                ) {
                    Text(text = title)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire settings state in `MainActivity.kt`**

Add imports:
```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.tajchert.imagetoclipboard.settings.CopySettings
import pl.tajchert.imagetoclipboard.settings.SettingsRepository
```
Add field:
```kotlin
    private val settingsRepository by lazy { SettingsRepository.create(applicationContext) }
```
Replace the `MaterialTheme(...) { MainScreen(...) }` call:
```kotlin
            MaterialTheme(colorScheme = colorScheme) {
                val settings by settingsRepository.settings.collectAsState(initial = CopySettings())
                MainScreen(
                    settings = settings,
                    onSettingsChange = { updated ->
                        lifecycleScope.launch { settingsRepository.update(updated) }
                    },
                    lastCopied = lastCopied,
                )
            }
```

- [ ] **Step 4: Build + tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 22 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: copy settings card — format, quality, max size"
```

---

### Task 6: Final verification

- [ ] **Step 1: Clean build + tests + lint**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL; lint warnings OK, errors not.

- [ ] **Step 2: Emulator verification (if a device is available)**

```bash
adb devices   # need a device
./gradlew :app:installDebug
```
Manual flow: system screenshot (`adb shell input keyevent 120`) → tap share on the HUD → pick "Copy to clipboard" → confirmation card should show the size line (e.g. "2.1 MB → 350 KB" with default WebP settings) → open the app → verify the settings card renders, change format to Original, share again → card shows a single size, no arrow.
Reminder from MVP testing: do NOT use `adb shell am start --grant-read-uri-permission` with MediaStore URIs — shell can't grant those; drive the real share sheet instead.

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "chore: verification fixes for compression settings" || echo "nothing to commit"
```
