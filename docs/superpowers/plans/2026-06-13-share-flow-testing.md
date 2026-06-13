# Share-flow Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover the `ShareViewModel` toggle branch and `ConfirmationSheet` rendering with fast JVM tests, and add one deterministic instrumented test for the real focus-triggered copy → clipboard path.

**Architecture:** Parts A/B run under Robolectric on the JVM (`:app:testDebugUnitTest`, no emulator) using the real repositories with the existing content-resolver-shadow + test-DataStore patterns. Part C adds a new `androidTest` source set with a test-only FileProvider (distinct authority) that supplies a `content://` source, launched via `ActivityScenario` so the real `onWindowFocusChanged` copy runs.

**Tech Stack:** Kotlin, Robolectric, `kotlinx-coroutines-test`, Compose UI test (`ui-test-junit4`), AndroidX Test (`ext:junit`, `runner`, `rules`), FileProvider.

---

### Task 1: Add JVM test dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add library entries to the version catalog**

In `gradle/libs.versions.toml`, under `[libraries]` (after the `androidx-test-core` line), add:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
```

(`coroutines` version `1.11.0` already exists; `ui-test-junit4` is managed by the Compose BOM, so no version.)

- [ ] **Step 2: Wire them into the test configuration**

In `app/build.gradle.kts`, in the `dependencies { }` block, after the existing `testImplementation(libs.androidx.test.core)` line, add:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
```

(The BOM `platform(...)` is repeated for the test classpath so `ui-test-junit4` resolves to the same Compose version as `implementation`.)

- [ ] **Step 3: Verify the project syncs and the test classpath compiles**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL (no new test files yet — this just proves the dependencies resolve).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "test: add coroutines-test and compose ui-test deps"
```

---

### Task 2: `ShareViewModelTest` (JVM/Robolectric)

**Files:**
- Create: `app/src/test/java/pl/mtajchert/clipboardhero/ShareViewModelTest.kt`

Reference facts: `ShareViewModel(repository, settingsRepository, context, ioDispatcher)` is constructor-pure. `ImageClipboardRepository(context, clock)` and `SettingsRepository(dataStore)` are both `new`-able. Sources are supplied via `shadowOf(contentResolver).registerInputStream(uri, stream)` (see `ImageClipboardRepositoryTest`). `ImageClipboardRepository.AUTHORITY == "pl.mtajchert.clipboardhero.fileprovider"`. `RetentionPolicy(maxItems, ttlMillis)` is the retention constructor used in that test.

- [ ] **Step 1: Write the test file**

Create `app/src/test/java/pl/mtajchert/clipboardhero/ShareViewModelTest.kt`:

```kotlin
package pl.mtajchert.clipboardhero

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.mtajchert.clipboardhero.settings.CopySettings
import pl.mtajchert.clipboardhero.settings.MaxDimension
import pl.mtajchert.clipboardhero.settings.OutputFormat
import pl.mtajchert.clipboardhero.settings.SettingsRepository
import pl.mtajchert.clipboardhero.ui.CopyState
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    // DataStore runs on the same test dispatcher, so advanceUntilIdle() drains its IO too.
    private val dataStoreScope = CoroutineScope(dispatcher + Job())
    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var repo: ImageClipboardRepository
    private var uriCounter = 0
    private val now = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // FileProvider caches path strategy per authority; Robolectric rotates filesDir per test.
        FileProvider::class.java.getDeclaredField("sCache").apply {
            isAccessible = true
            (get(null) as MutableMap<*, *>).clear()
        }
        context = ApplicationProvider.getApplicationContext()
        val ds: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                tmp.newFile("settings.preferences_pb")
            }
        settingsRepo = SettingsRepository(ds)
        repo = ImageClipboardRepository(context, clock = { now })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dataStoreScope.cancel()
    }

    private fun newViewModel() = ShareViewModel(repo, settingsRepo, context, dispatcher)

    private fun registerSource(): Uri {
        val uri = Uri.parse("content://test.source/img${uriCounter++}")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        return uri
    }

    // ORIGINAL format = byte-exact pass-through, so the copy succeeds without decoding.
    private val passthrough = CopySettings(
        format = OutputFormat.ORIGINAL,
        maxDimension = MaxDimension.ORIGINAL,
    )

    @Test
    fun `toggle on emits Success`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = true))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        advanceUntilIdle()

        assertTrue(vm.copyState.value is CopyState.Success)
    }

    @Test
    fun `toggle off emits SilentSuccess`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = false))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.SilentSuccess, vm.copyState.value)
    }

    @Test
    fun `unreadable uri emits Error even when toggle off`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = false))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(Uri.parse("content://nope/missing"), "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `null source emits Error`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.copy(null, "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `our own fileprovider authority is rejected`() = runTest(dispatcher) {
        val vm = newViewModel()
        val ownUri = Uri.parse("content://${ImageClipboardRepository.AUTHORITY}/clips/clip_1.png")

        vm.copy(ownUri, "image/png")
        advanceUntilIdle()

        assertEquals(CopyState.Error, vm.copyState.value)
    }

    @Test
    fun `copy is idempotent across repeat calls`() = runTest(dispatcher) {
        settingsRepo.update(passthrough.copy(showConfirmation = true))
        advanceUntilIdle()
        val vm = newViewModel()

        vm.copy(registerSource(), "image/png")
        vm.copy(registerSource(), "image/png") // guarded: must be a no-op
        advanceUntilIdle()

        assertTrue(vm.copyState.value is CopyState.Success)
        assertEquals(1, repo.history(RetentionPolicy(maxItems = 10, ttlMillis = null)).size)
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.ShareViewModelTest"`
Expected: PASS (6 tests). They exercise already-implemented production code, so they should pass on first green run. If `toggle off` fails by reporting `Success`, the DataStore write didn't land before `copy()` — confirm the `advanceUntilIdle()` after `settingsRepo.update(...)` is present.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/pl/mtajchert/clipboardhero/ShareViewModelTest.kt
git commit -m "test: cover ShareViewModel toggle/error branches"
```

---

### Task 3: `ConfirmationSheetTest` (Compose under Robolectric)

**Files:**
- Create: `app/src/test/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheetTest.kt`

Reference facts: `ConfirmationSheet(state: CopyState, onDone: () -> Unit)`; `DISMISS_DELAY_MS = 1500`; strings `R.string.copied_success` ("Copied to clipboard"), `R.string.copied_error`; `ClipboardHeroTheme(dynamicColor = …)` wraps content (used by existing previews). `assertDoesNotExist()` and `assertIsDisplayed()` come from `androidx.compose.ui.test`.

- [ ] **Step 1: Write the test file**

Create `app/src/test/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheetTest.kt`:

```kotlin
package pl.mtajchert.clipboardhero.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.mtajchert.clipboardhero.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmationSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun str(id: Int) =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun `success shows the copied card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Success(null, 2_400_000, 480_000), onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertIsDisplayed()
    }

    @Test
    fun `error shows the error card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Error, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_error)).assertIsDisplayed()
    }

    @Test
    fun `silent success draws no card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.SilentSuccess, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertDoesNotExist()
    }

    @Test
    fun `pending draws no card`() {
        rule.setContent {
            ClipboardHeroTheme(dynamicColor = false) {
                ConfirmationSheet(CopyState.Pending, onDone = {})
            }
        }
        rule.onNodeWithText(str(R.string.copied_success)).assertDoesNotExist()
    }

    @Test
    fun `silent success dismisses immediately`() {
        var done = false
        rule.setContent {
            ConfirmationSheet(CopyState.SilentSuccess, onDone = { done = true })
        }
        rule.waitForIdle()
        assertTrue(done)
    }

    @Test
    fun `success dismisses only after the delay`() {
        rule.mainClock.autoAdvance = false
        var done = false
        rule.setContent {
            ConfirmationSheet(CopyState.Success(null, 0, 0), onDone = { done = true })
        }
        rule.mainClock.advanceTimeBy(1499)
        assertFalse(done)
        rule.mainClock.advanceTimeBy(2)
        assertTrue(done)
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.ui.ConfirmationSheetTest"`
Expected: PASS (6 tests). If the delay test is flaky at the boundary, widen the margins (`advanceTimeBy(1400)` then `advanceTimeBy(200)`) — the assertion is "not before 1500ms, yes after", not exact-millisecond.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheetTest.kt
git commit -m "test: cover ConfirmationSheet rendering and dismiss timing"
```

---

### Task 4: `androidTest` infrastructure (build config + test FileProvider)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/AndroidManifest.xml`
- Create: `app/src/androidTest/res/xml/test_file_paths.xml`

- [ ] **Step 1: Add catalog versions and libraries**

In `gradle/libs.versions.toml`, under `[versions]` (after `androidxTestCore = "1.7.0"`), add:

```toml
androidxTestExtJunit = "1.3.0"
androidxTestRunner = "1.7.0"
```

Under `[libraries]` (after the entries added in Task 1), add:

```toml
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRunner" }
```

- [ ] **Step 2: Set the instrumentation runner**

In `app/build.gradle.kts`, inside `defaultConfig { }` (which currently sets only `applicationId`), add:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

- [ ] **Step 3: Add androidTest dependencies**

In `app/build.gradle.kts` `dependencies { }`, after the `testImplementation(libs.androidx.compose.ui.test.junit4)` line from Task 1, add:

```kotlin
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
```

(`ui-test-manifest` must be `debugImplementation` — it adds the activity host the Compose test rule needs, and the instrumented test runs against the debug build.)

- [ ] **Step 4: Create the test FileProvider manifest**

Create `app/src/androidTest/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <!-- Test-only source provider. Distinct authority from the app's own
             FileProvider so ShareReceiverActivity.isSafeSource accepts it. -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="pl.mtajchert.clipboardhero.test.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/test_file_paths" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 5: Create the test FileProvider paths**

Create `app/src/androidTest/res/xml/test_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="test_images" path="." />
</paths>
```

- [ ] **Step 6: Verify the androidTest APK assembles**

Run: `./gradlew :app:assembleDebugAndroidTest`
Expected: BUILD SUCCESSFUL (compiles the new manifest + deps; no test class yet).

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/androidTest/AndroidManifest.xml \
        app/src/androidTest/res/xml/test_file_paths.xml
git commit -m "test: add androidTest infra (runner, deps, test FileProvider)"
```

---

### Task 5: `ShareReceiverActivityTest` (instrumented happy path)

**Files:**
- Create: `app/src/androidTest/java/pl/mtajchert/clipboardhero/ShareReceiverActivityTest.kt`

Reference facts: `ShareReceiverActivity` is `@AndroidEntryPoint`; instrumented tests run in the real app process against the production `@HiltAndroidApp` `ClipboardHeroApplication`, so Hilt injects normally (no `HiltAndroidRule` needed). The activity copies from `onWindowFocusChanged` and auto-dismisses ~1500ms after the card shows, so do the time-sensitive clipboard read right after the card appears. `SettingsRepository.create(context)` uses the app's real DataStore; set `showConfirmation = true` so the card shows deterministically. Production FileProvider authority is `pl.mtajchert.clipboardhero.fileprovider`.

- [ ] **Step 1: Write the instrumented test**

Create `app/src/androidTest/java/pl/mtajchert/clipboardhero/ShareReceiverActivityTest.kt`:

```kotlin
package pl.mtajchert.clipboardhero

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
    private val testCtx: Context get() = instr.context        // test APK (owns the test FileProvider)
    private val targetCtx: Context get() = instr.targetContext // app under test

    @Before
    fun ensureConfirmationOn() {
        // Deterministic: force defaults (JPEG, showConfirmation = true) in the app's real DataStore.
        runBlocking { SettingsRepository.create(targetCtx).update(CopySettings(showConfirmation = true)) }
    }

    private fun sourceUri(): Uri {
        val file = File(testCtx.filesDir, "share_src.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) {
            bmp.setPixel(x, y, Color.rgb(x * 4, y * 4, 128))
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(testCtx, "pl.mtajchert.clipboardhero.test.fileprovider", file)
    }

    @Test
    fun share_copies_to_clipboard_and_shows_card() {
        val uri = sourceUri()
        // Grant the app under test read access to our test provider's URI.
        testCtx.grantUriPermission(targetCtx.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

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
```

- [ ] **Step 2: Confirm a device/emulator is connected**

Run: `adb devices`
Expected: at least one line ending in `device`. If none, start an emulator (`emulator -avd <name>`) before the next step.

- [ ] **Step 3: Run the instrumented test**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "pl.mtajchert.clipboardhero.ShareReceiverActivityTest"`
Expected: PASS (1 test). If the clipboard assertion returns a null authority, the read happened after the activity auto-dismissed — confirm the clipboard read is inside the `use { }` block immediately after `waitUntil`, before any other delay.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/pl/mtajchert/clipboardhero/ShareReceiverActivityTest.kt
git commit -m "test: add instrumented share -> clipboard happy path"
```

---

### Task 6: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all existing tests plus the new `ShareViewModelTest` (6) and `ConfirmationSheetTest` (6) pass.

- [ ] **Step 2: Run the instrumented suite on a device**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL — `ShareReceiverActivityTest` passes.

- [ ] **Step 3: Run lint to confirm no new errors**

Run: `./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL (warnings OK, errors not).

- [ ] **Step 4: Commit any incidental fixes (optional)**

No code changes expected here. If verification surfaced a fix, commit it with a descriptive message.

---

## Notes for the implementer

- **No production code changes.** Every task adds test files or build config. If a test fails, treat it as a real signal — verify the production behavior, don't loosen the assertion to make it pass.
- **Part A timing model:** the single `StandardTestDispatcher` backs `Dispatchers.Main` (via `setMain`), the VM's `ioDispatcher`, AND the DataStore scope, so one `advanceUntilIdle()` drains everything. Keep all three on `dispatcher`.
- **Why ORIGINAL format in Part A:** a byte-exact pass-through copy succeeds without decoding the 3-byte fake source. Don't switch to JPEG there or the (failed) decode path muddies the success assertion.
- **Part C runs only on `connectedDebugAndroidTest`** (needs a device/emulator). The JVM tests in Tasks 2–3 are the everyday signal and require no device.
```
