# Copy History, Direct Share & Privacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the last 10 copied images with tap-to-recopy on the main screen, rank the app in the share sheet via a sharing shortcut, and add privacy controls (clear-now, lazy auto-delete, history toggle).

**Architecture:** Clips become timestamped files (`clips/clip_<epochMillis>.<ext>`) — the filesystem is the database. A `RetentionPolicy(maxItems, ttlMillis)` derived from new `PrivacySettings` is passed into `ImageClipboardRepository`, which prunes on every write and read (lazy expiry, no background work). A `ShareShortcuts` helper pushes one dynamic sharing shortcut declared in `res/xml/shortcuts.xml`. The main screen replaces "Last copied" with a 3-column history grid card.

**Tech Stack:** Existing stack; `ShortcutManagerCompat` from androidx.core (already a dependency). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-13-history-directshare-privacy-design.md`

---

## File Structure

```
app/src/main/java/pl/mtajchert/clipboardhero/
    settings/PrivacySettings.kt        — PrivacySettings + AutoDelete enum (new)
    settings/SettingsRepository.kt     — privacySettings flow + updatePrivacy (modify)
    ImageClipboardRepository.kt        — timestamped clips, RetentionPolicy, prune,
                                         history/delete/clearAll; latestImage removed (modify)
    ShareShortcuts.kt                  — dynamic sharing shortcut publisher (new)
    ShareReceiverActivity.kt           — retention from settings, publish shortcut (modify)
    MainActivity.kt                    — history state + privacy wiring (modify)
    ui/MainScreen.kt                   — HistoryCard replaces LastCopiedCard,
                                         settings card privacy rows (modify)
app/src/main/res/xml/shortcuts.xml     — share-target declaration (new)
app/src/main/AndroidManifest.xml       — shortcuts meta-data on MainActivity (modify)
app/src/main/res/values/strings.xml    — new strings (modify)
app/src/test/java/pl/mtajchert/clipboardhero/
    settings/SettingsRepositoryTest.kt — privacy tests (modify)
    ImageClipboardRepositoryTest.kt    — retention/history tests, signature updates (modify)
    ShareShortcutsTest.kt              — dynamic shortcut published (new)
```

---

### Task 1: PrivacySettings + SettingsRepository (TDD)

**Files:**
- Create: `app/src/main/java/pl/mtajchert/clipboardhero/settings/PrivacySettings.kt`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/settings/SettingsRepository.kt`
- Test: `app/src/test/java/pl/mtajchert/clipboardhero/settings/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write `settings/PrivacySettings.kt`**

```kotlin
package pl.mtajchert.clipboardhero.settings

enum class AutoDelete(val hours: Int?) {
    OFF(null),
    H1(1),
    H24(24),
    D7(168),
}

data class PrivacySettings(
    val historyEnabled: Boolean = true,
    val autoDelete: AutoDelete = AutoDelete.OFF,
)
```

- [ ] **Step 2: Add failing tests to `SettingsRepositoryTest.kt`**

Add inside the class:

```kotlin
    @Test
    fun `privacy defaults when nothing stored`() = runBlocking {
        val repo = SettingsRepository(newDataStore())

        assertEquals(PrivacySettings(), repo.privacySettings.first())
    }

    @Test
    fun `privacy update and read round-trip`() = runBlocking {
        val repo = SettingsRepository(newDataStore())
        val wanted = PrivacySettings(historyEnabled = false, autoDelete = AutoDelete.H24)

        repo.updatePrivacy(wanted)

        assertEquals(wanted, repo.privacySettings.first())
    }

    @Test
    fun `invalid stored auto-delete falls back to default`() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("auto_delete")] = "NEVER_EVER"
        }
        val repo = SettingsRepository(dataStore)

        assertEquals(PrivacySettings(), repo.privacySettings.first())
    }
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.settings.SettingsRepositoryTest"`
Expected: compilation FAILURE — `privacySettings` unresolved.

- [ ] **Step 4: Extend `SettingsRepository.kt`**

Add below the `settings` flow / `update` function:

```kotlin
    val privacySettings: Flow<PrivacySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = PrivacySettings()
            PrivacySettings(
                historyEnabled = prefs[KEY_HISTORY_ENABLED] ?: defaults.historyEnabled,
                autoDelete = prefs[KEY_AUTO_DELETE].toEnumOrNull<AutoDelete>() ?: defaults.autoDelete,
            )
        }

    suspend fun updatePrivacy(settings: PrivacySettings) {
        dataStore.edit { prefs ->
            prefs[KEY_HISTORY_ENABLED] = settings.historyEnabled
            prefs[KEY_AUTO_DELETE] = settings.autoDelete.name
        }
    }
```

Add to the companion object:

```kotlin
        private val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val KEY_AUTO_DELETE = stringPreferencesKey("auto_delete")
```

Add import: `androidx.datastore.preferences.core.booleanPreferencesKey`.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.settings.SettingsRepositoryTest"`
Expected: 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: PrivacySettings (history toggle, auto-delete) in SettingsRepository"
```

---

### Task 2: Repository — timestamped clips, retention, history (TDD)

**Files:**
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ImageClipboardRepository.kt`
- Test: `app/src/test/java/pl/mtajchert/clipboardhero/ImageClipboardRepositoryTest.kt`

- [ ] **Step 1: Rewrite the test file**

Replace `ImageClipboardRepositoryTest.kt` entirely (existing tests adapted to the
4-arg signature + fake clock + timestamped names; latestImage tests replaced by
history tests):

```kotlin
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
        now += 1                                            // unique timestamp per copy
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
        assertEquals("image/jpeg", context.getSystemService(ClipboardManager::class.java).primaryClip!!.description.getMimeType(0))
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
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.ImageClipboardRepositoryTest"`
Expected: compilation FAILURE — `RetentionPolicy` unresolved, 4-arg `copyToClipboard` missing.

- [ ] **Step 3: Rewrite `ImageClipboardRepository.kt`**

```kotlin
package pl.mtajchert.clipboardhero

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.PrivacySettings
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
    val originalBytes: Long,
    val finalBytes: Long,
    val timestamp: Long,
)

data class RetentionPolicy(val maxItems: Int, val ttlMillis: Long?) {
    companion object {
        const val HISTORY_SIZE = 10

        fun from(privacy: PrivacySettings) = RetentionPolicy(
            maxItems = if (privacy.historyEnabled) HISTORY_SIZE else 1,
            ttlMillis = privacy.autoDelete.hours?.let { it * 3_600_000L },
        )
    }
}

class ImageClipboardRepository(
    private val context: Context,
    private val transformer: ImageTransformer = ImageTransformer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(
        sourceUri: Uri,
        fallbackMimeType: String?,
        settings: CopySettings,
        retention: RetentionPolicy,
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

        val extension = extensionFor(transformed.mimeType)
        var timestamp = clock()
        while (File(clipsDir, "clip_$timestamp.$extension").exists()) timestamp++
        val target = File(clipsDir, "clip_$timestamp.$extension")
        if (!transformed.file.renameTo(target)) throw IOException("Cannot move clip into place")
        incoming.delete() // no-op when pass-through already renamed it

        prune(retention)

        val copied = target.toCopiedImage(
            mimeType = transformed.mimeType,
            originalBytes = transformed.originalBytes,
            finalBytes = transformed.finalBytes,
        )
        setClip(copied)
        copied
    }

    fun history(retention: RetentionPolicy): List<CopiedImage> {
        prune(retention)
        return clipFiles()
            .sortedByDescending { timestampOf(it) }
            .map { it.toCopiedImage(mimeTypeFor(it.extension), it.length(), it.length()) }
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun delete(image: CopiedImage) {
        runCatching { image.file.delete() }
    }

    fun clearAll() {
        clipFiles().forEach { runCatching { it.delete() } }
        runCatching {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }

    private fun clipFiles(): List<File> =
        clipsDir.listFiles()?.filter { it.isFile && it.name.startsWith("clip") } ?: emptyList()

    /** clip_<epochMillis>.<ext>; legacy clip.<ext> falls back to file mtime. */
    private fun timestampOf(file: File): Long =
        file.name.removePrefix("clip_").substringBefore('.').toLongOrNull() ?: file.lastModified()

    private fun prune(retention: RetentionPolicy) {
        val cutoff = retention.ttlMillis?.let { clock() - it }
        clipFiles()
            .sortedByDescending { timestampOf(it) }
            .forEachIndexed { index, file ->
                val overLimit = index >= retention.maxItems
                val expired = cutoff != null && timestampOf(file) < cutoff
                if (overLimit || expired) runCatching { file.delete() }
            }
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun File.toCopiedImage(mimeType: String, originalBytes: Long, finalBytes: Long): CopiedImage =
        CopiedImage(
            file = this,
            providerUri = FileProvider.getUriForFile(context, AUTHORITY, this),
            mimeType = mimeType,
            originalBytes = originalBytes,
            finalBytes = finalBytes,
            timestamp = timestampOf(this),
        )

    private fun extensionFor(mimeType: String): String = MIME_TO_EXTENSION[mimeType] ?: "bin"

    private fun mimeTypeFor(extension: String): String =
        MIME_TO_EXTENSION.entries.firstOrNull { it.value == extension }?.key ?: GENERIC_IMAGE_MIME

    companion object {
        const val AUTHORITY = "pl.mtajchert.clipboardhero.fileprovider"
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

Note: `incoming.tmp` does not start with `clip`, so `clipFiles()` ignores it naturally.

This breaks `ShareReceiverActivity` and `MainActivity` (old signatures). Apply the
minimal `ShareReceiverActivity` fix now (full UI wiring is Task 4):

Replace the `lifecycleScope.launch` block in `ShareReceiverActivity.kt`:

```kotlin
        lifecycleScope.launch {
            val settingsRepository = SettingsRepository.create(applicationContext)
            val repository = ImageClipboardRepository(applicationContext)
            copyState = withContext(Dispatchers.IO) {
                val settings = settingsRepository.settings.first()
                val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
                repository.copyToClipboard(sourceUri, intent.type, settings, retention)
                    .map { CopyState.Success(Thumbnails.decode(it.file), it.originalBytes, it.finalBytes) }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
            }
        }
```

And in `MainActivity.kt`, replace `refreshLastCopied` (temporary bridge; full history UI lands in Task 4):

```kotlin
    private fun refreshLastCopied() {
        val latest = repository.history(RetentionPolicy.from(PrivacySettings())).firstOrNull()
        lastCopied = latest?.let { image ->
            LastCopiedUi(
                thumbnail = Thumbnails.decode(image.file),
                onCopyAgain = {
                    if (repository.recopy(image)) {
                        Toast.makeText(this, R.string.copied_success, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
```

with imports `pl.mtajchert.clipboardhero.settings.PrivacySettings` added.

- [ ] **Step 4: Run all tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all PASS (14 repo + 6 settings + 7 transformer + 2 manifest = 29).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: timestamped clip history with retention policy (prune on read/write)"
```

---

### Task 3: Direct Share shortcut (TDD)

**Files:**
- Create: `app/src/main/res/xml/shortcuts.xml`
- Create: `app/src/main/java/pl/mtajchert/clipboardhero/ShareShortcuts.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ShareReceiverActivity.kt`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/MainActivity.kt`
- Test: `app/src/test/java/pl/mtajchert/clipboardhero/ShareShortcutsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package pl.mtajchert.clipboardhero

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
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.ShareShortcutsTest"`
Expected: compilation FAILURE — `ShareShortcuts` unresolved.

- [ ] **Step 3: Write `res/xml/shortcuts.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <share-target android:targetClass="pl.mtajchert.clipboardhero.ShareReceiverActivity">
        <data android:mimeType="image/*" />
        <category android:name="pl.mtajchert.clipboardhero.SHARE_TARGET" />
    </share-target>
</shortcuts>
```

- [ ] **Step 4: Add meta-data to the MainActivity entry in `AndroidManifest.xml`**

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
        </activity>
```

- [ ] **Step 5: Write `ShareShortcuts.kt`**

```kotlin
package pl.mtajchert.clipboardhero

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

    const val CATEGORY = "pl.mtajchert.clipboardhero.SHARE_TARGET"
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
```

- [ ] **Step 6: Publish from both activities**

In `ShareReceiverActivity.copySharedImage`, replace the coroutine body so success
also publishes (keeps state logic unchanged):

```kotlin
        lifecycleScope.launch {
            val settingsRepository = SettingsRepository.create(applicationContext)
            val repository = ImageClipboardRepository(applicationContext)
            copyState = withContext(Dispatchers.IO) {
                val settings = settingsRepository.settings.first()
                val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
                repository.copyToClipboard(sourceUri, intent.type, settings, retention)
                    .onSuccess { ShareShortcuts.publish(applicationContext) }
                    .map { CopyState.Success(Thumbnails.decode(it.file), it.originalBytes, it.finalBytes) }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
            }
        }
```

In `MainActivity.onCreate`, after `super.onCreate(savedInstanceState)` add:

```kotlin
        ShareShortcuts.publish(applicationContext)
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all PASS (30 total).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: direct-share sharing shortcut for share-sheet ranking"
```

---

### Task 4: History card + privacy UI

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ui/MainScreen.kt`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/MainActivity.kt`

- [ ] **Step 1: Add strings**

```xml
    <string name="history_title">History</string>
    <string name="clear_history">Clear history &amp; clipboard</string>
    <string name="history_cleared">History cleared</string>
    <string name="keep_history">Keep history</string>
    <string name="auto_delete_label">Auto-delete</string>
    <string name="auto_delete_off">Off</string>
    <string name="auto_delete_1h">1 h</string>
    <string name="auto_delete_24h">24 h</string>
    <string name="auto_delete_7d">7 d</string>
```

- [ ] **Step 2: Rework `MainScreen.kt`**

Remove `LastCopiedUi` and `LastCopiedCard`. Add at the top (replacing `LastCopiedUi`):

```kotlin
data class HistoryItemUi(val id: String, val thumbnail: Bitmap?)

data class HistoryUi(
    val items: List<HistoryItemUi>,
    val onRecopy: (HistoryItemUi) -> Unit,
    val onDelete: (HistoryItemUi) -> Unit,
    val onClearAll: () -> Unit,
)
```

New signature + body:

```kotlin
@Composable
fun MainScreen(
    settings: CopySettings,
    onSettingsChange: (CopySettings) -> Unit,
    privacy: PrivacySettings,
    onPrivacyChange: (PrivacySettings) -> Unit,
    history: HistoryUi,
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
            SettingsCard(
                settings = settings,
                onSettingsChange = onSettingsChange,
                privacy = privacy,
                onPrivacyChange = onPrivacyChange,
            )
            if (history.items.isNotEmpty()) {
                HistoryCard(history)
            }
        }
    }
}
```

`SettingsCard` gains the two privacy rows — new signature
`SettingsCard(settings, onSettingsChange, privacy, onPrivacyChange)`, and after the
max-size `LabeledSegmentedRow` add:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.keep_history),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = privacy.historyEnabled,
                    onCheckedChange = { onPrivacyChange(privacy.copy(historyEnabled = it)) },
                )
            }

            LabeledSegmentedRow(
                label = stringResource(R.string.auto_delete_label),
                entries = listOf(
                    AutoDelete.OFF to stringResource(R.string.auto_delete_off),
                    AutoDelete.H1 to stringResource(R.string.auto_delete_1h),
                    AutoDelete.H24 to stringResource(R.string.auto_delete_24h),
                    AutoDelete.D7 to stringResource(R.string.auto_delete_7d),
                ),
                selected = privacy.autoDelete,
                onSelect = { onPrivacyChange(privacy.copy(autoDelete = it)) },
            )
```

Add `HistoryCard`:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(history: HistoryUi) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = history.onClearAll) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.clear_history),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            history.items.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { item ->
                        HistoryThumbnail(
                            item = item,
                            isLatest = item.id == history.items.first().id,
                            onTap = { history.onRecopy(item) },
                            onLongPress = { history.onDelete(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryThumbnail(
    item: HistoryItemUi,
    isLatest: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isLatest) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumbnail != null) {
            Image(
                bitmap = item.thumbnail.asImageBitmap(),
                contentDescription = stringResource(R.string.history_title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
```

New imports needed in `MainScreen.kt`:

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import pl.mtajchert.clipboardhero.settings.AutoDelete
import pl.mtajchert.clipboardhero.settings.PrivacySettings
```

(Remove now-unused `fillMaxWidth`/`FillWidth` imports only if the compiler flags them.)

- [ ] **Step 3: Rewrite `MainActivity.kt`**

```kotlin
package pl.mtajchert.clipboardhero

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.PrivacySettings
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import pl.mtajchert.clipboardhero.ui.HistoryItemUi
import pl.mtajchert.clipboardhero.ui.HistoryUi
import pl.mtajchert.clipboardhero.ui.MainScreen
import pl.mtajchert.clipboardhero.ui.Thumbnails

class MainActivity : ComponentActivity() {

    private val repository by lazy { ImageClipboardRepository(applicationContext) }
    private val settingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private var historyImages by mutableStateOf<List<CopiedImage>>(emptyList())
    private var thumbnails by mutableStateOf<Map<String, android.graphics.Bitmap?>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareShortcuts.publish(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                val settings by settingsRepository.settings.collectAsState(initial = CopySettings())
                val privacy by settingsRepository.privacySettings.collectAsState(initial = PrivacySettings())
                MainScreen(
                    settings = settings,
                    onSettingsChange = { updated ->
                        lifecycleScope.launch { settingsRepository.update(updated) }
                    },
                    privacy = privacy,
                    onPrivacyChange = { updated ->
                        lifecycleScope.launch {
                            settingsRepository.updatePrivacy(updated)
                            refreshHistory()
                        }
                    },
                    history = HistoryUi(
                        items = historyImages.map {
                            HistoryItemUi(id = it.file.name, thumbnail = thumbnails[it.file.name])
                        },
                        onRecopy = ::recopy,
                        onDelete = ::deleteItem,
                        onClearAll = ::clearAll,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshHistory() }
    }

    private suspend fun refreshHistory() {
        val retention = RetentionPolicy.from(settingsRepository.privacySettings.first())
        val images = withContext(Dispatchers.IO) { repository.history(retention) }
        val thumbs = withContext(Dispatchers.IO) {
            images.associate { it.file.name to Thumbnails.decode(it.file, maxDimension = 256) }
        }
        historyImages = images
        thumbnails = thumbs
    }

    private fun recopy(item: HistoryItemUi) {
        val image = historyImages.firstOrNull { it.file.name == item.id } ?: return
        if (repository.recopy(image)) {
            Toast.makeText(this, R.string.copied_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteItem(item: HistoryItemUi) {
        val image = historyImages.firstOrNull { it.file.name == item.id } ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.delete(image) }
            refreshHistory()
        }
    }

    private fun clearAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.clearAll() }
            refreshHistory()
            Toast.makeText(this@MainActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 4: Build + all tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 30 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: history grid card + privacy controls (clear, auto-delete, toggle)"
```

---

### Task 5: Final verification

- [ ] **Step 1: Clean build + tests + lint**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL; no lint errors.

- [ ] **Step 2: Emulator verification**

Use `adb -s emulator-5554` (a physical device may also be attached). Flow:
screenshot (`input keyevent 120`) → share → "Copy to clipboard", twice with
different screens. Then open the app:
- History card shows 2 thumbnails, newest highlighted.
- Tap the older one → system clipboard chip appears (re-copy works).
- Long-press it → item disappears.
- Trash icon → card disappears, toast "History cleared".
- Toggle "Keep history" off, share once, share again → history card shows only 1 item.
- Reminder: do NOT use `adb shell am start --grant-read-uri-permission` with
  MediaStore URIs — drive the real share sheet.

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "chore: verification fixes for history/direct-share/privacy" || echo "nothing to commit"
```
