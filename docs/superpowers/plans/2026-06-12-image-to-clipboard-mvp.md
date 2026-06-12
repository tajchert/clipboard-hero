# Image to Clipboard MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that registers as a share target for images and puts the shared image (full resolution, original bytes) on the system clipboard via its own FileProvider.

**Architecture:** Single-module Kotlin app. A translucent `ShareReceiverActivity` receives `ACTION_SEND image/*`, waits for window focus (Android 10+ clipboard rule), delegates to `ImageClipboardRepository` (streams bytes to `filesDir/clips/`, builds ClipData over our FileProvider URI), and shows a Compose confirmation card that auto-dismisses. `MainActivity` is a minimal Compose info screen with a "last copied" card.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2025.01), Material 3, AGP 8.8, minSdk 29 / target+compileSdk 35, JUnit4 + Robolectric 4.14 for unit tests.

**Spec:** `docs/superpowers/specs/2026-06-12-image-to-clipboard-design.md`

---

## File Structure

```
settings.gradle.kts                  — repo/plugin management, :app module
build.gradle.kts                     — root, plugin aliases apply false
gradle.properties                    — AndroidX flags, JVM args
gradle/libs.versions.toml            — version catalog
app/build.gradle.kts                 — android config, compose, robolectric test setup
app/src/main/AndroidManifest.xml     — both activities + FileProvider
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml   — normal + translucent theme
app/src/main/res/values/colors.xml   — icon background color
app/src/main/res/xml/file_paths.xml  — FileProvider paths
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/java/pl/tajchert/imagetoclipboard/
    ImageClipboardRepository.kt      — core logic: copy bytes, build clip, latestImage()
    ShareReceiverActivity.kt         — share entry point + focus-gated copy
    MainActivity.kt                  — launcher screen host
    ui/Thumbnails.kt                 — downsampled bitmap decode helper
    ui/ConfirmationSheet.kt          — translucent confirmation card UI
    ui/MainScreen.kt                 — how-to + last-copied card UI
app/src/test/java/pl/tajchert/imagetoclipboard/
    ImageClipboardRepositoryTest.kt
    ShareTargetManifestTest.kt
```

---

### Task 1: Project scaffold that compiles

**Files:**
- Create: all gradle files, manifest, themes/strings/colors/icon resources, stub `MainActivity.kt`

- [ ] **Step 1: Bootstrap Gradle wrapper**

```bash
cd /Users/mtajchert/coding/priv/image-to-clipboard
command -v gradle && gradle wrapper --gradle-version 8.11.1 \
  || (brew install gradle && gradle wrapper --gradle-version 8.11.1)
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created. (If `gradle init` asks questions, ctrl-C and run only `gradle wrapper`.)

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "image-to-clipboard"
include(":app")
```

- [ ] **Step 3: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.8.0"
kotlin = "2.1.0"
coreKtx = "1.15.0"
activityCompose = "1.10.0"
composeBom = "2025.01.00"
lifecycleRuntimeKtx = "2.8.7"
junit = "4.13.2"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 6: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "pl.tajchert.imagetoclipboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.tajchert.imagetoclipboard"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

- [ ] **Step 7: Write `app/src/main/AndroidManifest.xml`** (share activity's intent filter added in Task 3; FileProvider included now)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.ImageToClipboard">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="pl.tajchert.imagetoclipboard.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 8: Write resources**

`app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="clips" path="clips/" />
</paths>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Image to Clipboard</string>
    <string name="share_target_label">Copy to clipboard</string>
    <string name="copied_success">Copied to clipboard</string>
    <string name="copied_error">Couldn\'t read that image</string>
    <string name="howto_title">Copy any image to your clipboard</string>
    <string name="howto_step_1">Share an image from any app</string>
    <string name="howto_step_2">Pick “Copy to clipboard”</string>
    <string name="howto_step_3">Paste it anywhere from your keyboard</string>
    <string name="last_copied_title">Last copied</string>
    <string name="copy_again">Copy again</string>
</resources>
```

`app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="ic_launcher_background">#1A73E8</color>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.ImageToClipboard" parent="android:Theme.Material.Light.NoActionBar" />

    <style name="Theme.ImageToClipboard.Translucent">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowContentOverlay">@null</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:windowAnimationStyle">@null</item>
    </style>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml` (clipboard with image glyph):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M38,30h32a4,4 0,0 1,4 4v44a4,4 0,0 1,-4 4H38a4,4 0,0 1,-4 -4V34a4,4 0,0 1,4 -4z" />
    <path
        android:fillColor="#1A73E8"
        android:pathData="M48,26h12a3,3 0,0 1,3 3v3H45v-3a3,3 0,0 1,3 -3z" />
    <path
        android:fillColor="#1A73E8"
        android:pathData="M40,72l8,-12l6,8l5,-6l9,10z" />
    <path
        android:fillColor="#1A73E8"
        android:pathData="M47,46m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 9: Write stub `app/src/main/java/pl/tajchert/imagetoclipboard/MainActivity.kt`**

```kotlin
package pl.tajchert.imagetoclipboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text(text = "Image to Clipboard")
            }
        }
    }
}
```

- [ ] **Step 10: Add `.gitignore`**

```gitignore
.gradle/
build/
local.properties
.idea/
*.iml
.DS_Store
captures/
```

- [ ] **Step 11: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If `local.properties`/SDK missing, set `sdk.dir` to the Android SDK (usually `~/Library/Android/sdk`).

- [ ] **Step 12: Commit**

```bash
git add -A && git commit -m "feat: project scaffold — compiling Compose app with FileProvider"
```

---

### Task 2: ImageClipboardRepository (TDD)

**Files:**
- Create: `app/src/test/java/pl/tajchert/imagetoclipboard/ImageClipboardRepositoryTest.kt`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ImageClipboardRepository.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package pl.tajchert.imagetoclipboard

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageClipboardRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: ImageClipboardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = ImageClipboardRepository(context)
    }

    private fun registerSource(bytes: ByteArray, uri: String = "content://test.source/img"): Uri {
        val sourceUri = Uri.parse(uri)
        shadowOf(context.contentResolver).registerInputStream(sourceUri, ByteArrayInputStream(bytes))
        return sourceUri
    }

    @Test
    fun `copies bytes exactly without re-encoding`() {
        val bytes = ByteArray(50_000) { (it % 251).toByte() }
        val uri = registerSource(bytes)

        val result = repo.copyToClipboard(uri, "image/png")

        val copied = result.getOrThrow()
        assertArrayEquals(bytes, copied.file.readBytes())
    }

    @Test
    fun `maps mime type to file extension`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/jpeg").getOrThrow()

        assertEquals("clip.jpg", copied.file.name)
        assertEquals("image/jpeg", copied.mimeType)
    }

    @Test
    fun `unknown image subtype falls back to bin extension and keeps mime`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/x-weird!type").getOrThrow()

        assertEquals("clip.bin", copied.file.name)
        assertEquals("image/x-weird!type", copied.mimeType)
    }

    @Test
    fun `keeps only the latest copied file`() {
        repo.copyToClipboard(registerSource(byteArrayOf(1), "content://test.source/a"), "image/png").getOrThrow()
        repo.copyToClipboard(registerSource(byteArrayOf(2), "content://test.source/b"), "image/jpeg").getOrThrow()

        val clipsDir = File(context.filesDir, "clips")
        assertEquals(listOf("clip.jpg"), clipsDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `puts our FileProvider uri with correct mime on the clipboard`() {
        val uri = registerSource(byteArrayOf(1, 2, 3))

        val copied = repo.copyToClipboard(uri, "image/png").getOrThrow()

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip!!
        assertEquals(1, clip.itemCount)
        assertEquals(copied.providerUri, clip.getItemAt(0).uri)
        assertEquals("pl.tajchert.imagetoclipboard.fileprovider", copied.providerUri.authority)
        assertEquals("image/png", clip.description.getMimeType(0))
    }

    @Test
    fun `unreadable uri returns failure`() {
        val result = repo.copyToClipboard(Uri.parse("content://nope/missing"), "image/png")

        assertTrue(result.isFailure)
    }

    @Test
    fun `latestImage returns null when nothing copied`() {
        assertNull(repo.latestImage())
    }

    @Test
    fun `latestImage returns the stored image after a copy`() {
        val uri = registerSource(byteArrayOf(9, 9, 9))
        repo.copyToClipboard(uri, "image/webp").getOrThrow()

        val latest = repo.latestImage()

        assertNotNull(latest)
        assertEquals("clip.webp", latest!!.file.name)
        assertEquals("image/webp", latest.mimeType)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ImageClipboardRepositoryTest"`
Expected: compilation FAILURE — `ImageClipboardRepository` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package pl.tajchert.imagetoclipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

data class CopiedImage(
    val file: File,
    val providerUri: Uri,
    val mimeType: String,
)

class ImageClipboardRepository(private val context: Context) {

    private val clipsDir: File
        get() = File(context.filesDir, "clips")

    fun copyToClipboard(sourceUri: Uri, fallbackMimeType: String?): Result<CopiedImage> = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri)?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: fallbackMimeType?.takeIf { it != GENERIC_IMAGE_MIME }
            ?: GENERIC_IMAGE_MIME

        clipsDir.mkdirs()
        clipsDir.listFiles()?.forEach { it.delete() }
        val target = File(clipsDir, "clip.${extensionFor(mimeType)}")

        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open input stream for $sourceUri")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }

        val copied = target.toCopiedImage(mimeType)
        setClip(copied)
        copied
    }

    fun recopy(image: CopiedImage): Boolean {
        if (!image.file.exists()) return false
        setClip(image)
        return true
    }

    fun latestImage(): CopiedImage? {
        val file = clipsDir.listFiles()?.firstOrNull() ?: return null
        return file.toCopiedImage(mimeTypeFor(file.extension))
    }

    private fun setClip(image: CopiedImage) {
        val clip = ClipData(
            ClipDescription("Image", arrayOf(image.mimeType)),
            ClipData.Item(image.providerUri),
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun File.toCopiedImage(mimeType: String): CopiedImage {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, this)
        return CopiedImage(file = this, providerUri = uri, mimeType = mimeType)
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ImageClipboardRepositoryTest"`
Expected: 8 tests PASS. (If `unknown image subtype` test fails on the mime kept: the ClipDescription must carry the original mime string, not the extension-derived one.)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: ImageClipboardRepository — local copy + FileProvider clipboard"
```

---

### Task 3: ShareReceiverActivity + confirmation sheet

**Files:**
- Create: `app/src/test/java/pl/tajchert/imagetoclipboard/ShareTargetManifestTest.kt`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ShareReceiverActivity.kt`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ui/Thumbnails.kt`
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ui/ConfirmationSheet.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add share activity)

- [ ] **Step 1: Write the failing manifest test**

```kotlin
package pl.tajchert.imagetoclipboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ShareTargetManifestTest"`
Expected: compilation FAILURE — `ShareReceiverActivity` unresolved.

- [ ] **Step 3: Add the activity to `app/src/main/AndroidManifest.xml`** (inside `<application>`, before the provider)

```xml
        <activity
            android:name=".ShareReceiverActivity"
            android:exported="true"
            android:label="@string/share_target_label"
            android:theme="@style/Theme.ImageToClipboard.Translucent"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:noHistory="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
        </activity>
```

- [ ] **Step 4: Write `app/src/main/java/pl/tajchert/imagetoclipboard/ui/Thumbnails.kt`**

```kotlin
package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Thumbnails {
    /** Decodes [file] downsampled so the longest edge is roughly [maxDimension] px. */
    fun decode(file: File, maxDimension: Int = 512): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}
```

- [ ] **Step 5: Write `app/src/main/java/pl/tajchert/imagetoclipboard/ui/ConfirmationSheet.kt`**

```kotlin
package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.tajchert.imagetoclipboard.R

sealed interface CopyState {
    data object Pending : CopyState
    data class Success(val thumbnail: Bitmap?) : CopyState
    data object Error : CopyState
}

private const val DISMISS_DELAY_MS = 1500L

@Composable
fun ConfirmationSheet(state: CopyState, onDone: () -> Unit) {
    LaunchedEffect(state) {
        if (state !is CopyState.Pending) {
            delay(DISMISS_DELAY_MS)
            onDone()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            )
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        when (state) {
            CopyState.Pending -> Unit
            is CopyState.Success -> ResultCard(
                thumbnail = state.thumbnail,
                message = stringResource(R.string.copied_success),
                isError = false,
            )
            CopyState.Error -> ResultCard(
                thumbnail = null,
                message = stringResource(R.string.copied_error),
                isError = true,
            )
        }
    }
}

@Composable
private fun ResultCard(thumbnail: Bitmap?, message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
```

Note: `Icons.Filled.Check`/`Warning` live in `androidx.compose.material:material-icons-core`, pulled in transitively by material3. If unresolved, add `implementation("androidx.compose.material:material-icons-core")` (BOM-managed) to `app/build.gradle.kts`.

- [ ] **Step 6: Write `app/src/main/java/pl/tajchert/imagetoclipboard/ShareReceiverActivity.kt`**

```kotlin
package pl.tajchert.imagetoclipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.tajchert.imagetoclipboard.ui.ConfirmationSheet
import pl.tajchert.imagetoclipboard.ui.CopyState
import pl.tajchert.imagetoclipboard.ui.Thumbnails

class ShareReceiverActivity : ComponentActivity() {

    private var copyState by mutableStateOf<CopyState>(CopyState.Pending)
    private var copyStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfirmationSheet(state = copyState, onDone = ::finish)
            }
        }
    }

    // Android 10+ only lets the focused app write the clipboard, so the copy
    // must wait until this window actually holds focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !copyStarted) {
            copyStarted = true
            copySharedImage()
        }
    }

    private fun copySharedImage() {
        val sourceUri = if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (sourceUri == null) {
            copyState = CopyState.Error
            return
        }
        lifecycleScope.launch {
            val repository = ImageClipboardRepository(applicationContext)
            copyState = withContext(Dispatchers.IO) {
                repository.copyToClipboard(sourceUri, intent.type)
                    .map { CopyState.Success(Thumbnails.decode(it.file)) }
                    .getOrDefault(CopyState.Error)
            }
        }
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.tajchert.imagetoclipboard.ShareTargetManifestTest"`
Expected: 2 tests PASS.

Caveat: `setPrimaryClip` inside `copyToClipboard` runs on `Dispatchers.IO` here. `ClipboardManager` is thread-safe; if a `CalledFromWrongThreadException` ever appears on a device, split the repository call so `setPrimaryClip` happens on the main thread.

- [ ] **Step 8: Full build + all tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: share target activity with translucent confirmation sheet"
```

---

### Task 4: Main info screen

**Files:**
- Create: `app/src/main/java/pl/tajchert/imagetoclipboard/ui/MainScreen.kt`
- Modify: `app/src/main/java/pl/tajchert/imagetoclipboard/MainActivity.kt`

- [ ] **Step 1: Write `app/src/main/java/pl/tajchert/imagetoclipboard/ui/MainScreen.kt`**

```kotlin
package pl.tajchert.imagetoclipboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.tajchert.imagetoclipboard.R

data class LastCopiedUi(val thumbnail: Bitmap?, val onCopyAgain: () -> Unit)

@Composable
fun MainScreen(lastCopied: LastCopiedUi?) {
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
            if (lastCopied != null) {
                LastCopiedCard(lastCopied)
            }
        }
    }
}

@Composable
private fun HowToSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Step(number = 1, text = stringResource(R.string.howto_step_1))
        Step(number = 2, text = stringResource(R.string.howto_step_2))
        Step(number = 3, text = stringResource(R.string.howto_step_3))
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun LastCopiedCard(lastCopied: LastCopiedUi) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.last_copied_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (lastCopied.thumbnail != null) {
                Image(
                    bitmap = lastCopied.thumbnail.asImageBitmap(),
                    contentDescription = stringResource(R.string.last_copied_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Button(onClick = lastCopied.onCopyAgain, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.copy_again))
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite `MainActivity.kt`**

```kotlin
package pl.tajchert.imagetoclipboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.tajchert.imagetoclipboard.ui.LastCopiedUi
import pl.tajchert.imagetoclipboard.ui.MainScreen
import pl.tajchert.imagetoclipboard.ui.Thumbnails

class MainActivity : ComponentActivity() {

    private val repository by lazy { ImageClipboardRepository(applicationContext) }
    private var lastCopied by mutableStateOf<LastCopiedUi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                MainScreen(lastCopied = lastCopied)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLastCopied()
    }

    private fun refreshLastCopied() {
        val latest = repository.latestImage()
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
}
```

- [ ] **Step 3: Build + all tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 10 tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: main info screen with how-to steps and last-copied card"
```

---

### Task 5: Final verification

- [ ] **Step 1: Full clean build + tests + lint**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL; lint may warn but must not error.

- [ ] **Step 2: Manual emulator verification (if a device/emulator is available)**

```bash
adb devices                       # need at least one device
./gradlew :app:installDebug
# Push a test image and share it via the system share sheet:
adb shell screencap -p /sdcard/test_share.png
adb shell am start -a android.intent.action.SEND -t image/png \
  --eu android.intent.extra.STREAM "content://media/external/images/media/$(adb shell content query --uri content://media/external/images/media --projection _id | tail -1 | sed 's/.*_id=//')" \
  -n pl.tajchert.imagetoclipboard/.ShareReceiverActivity \
  --grant-read-uri-permission
```

Simpler manual path: open Photos/Files on the emulator → share an image → pick "Copy to clipboard" → confirm the confirmation card appears → open any text field → Gboard clipboard → paste the image.

Verify: confirmation card with thumbnail appears and auto-dismisses; image pastes in a Gboard-supporting app; main screen shows last-copied card.
If no device is available, state that explicitly in the final report — do not claim manual verification happened.

- [ ] **Step 3: Commit any fixes, final commit**

```bash
git add -A && git commit -m "chore: final verification fixes" || echo "nothing to commit"
```
