# Faster, more robust testing for the share/confirmation flow — design

## Problem

The "Show confirmation" feature shipped with no automated coverage of its two new
behaviors: the `ShareViewModel` success/silent/error branch and the
`ConfirmationSheet` rendering. Verifying them meant manually driving the share
sheet on an emulator — slow and flaky (compounded by duplicate-named apps and the
fact that `adb` cannot grant MediaStore URIs).

This work closes that gap with **fast JVM tests for the logic** and **one
deterministic instrumented test for the genuinely device-only path** (focus-
triggered copy + real clipboard write). All branch/error variants live in the JVM
tests; the instrumented test is happy-path only.

## Scope

- A. `ShareViewModelTest` — JVM/Robolectric.
- B. `ConfirmationSheetTest` — Compose under Robolectric.
- C. One instrumented happy-path test in a new `androidTest` source set.

Out of scope: testing OFF/error paths on-device (covered by A), paste-target
compatibility, refactoring repositories into interfaces (not needed — they are
constructor-pure and Robolectric's content-resolver shadow supplies sources).

## Part A — `ShareViewModelTest` (JVM/Robolectric)

**File:** `app/src/test/java/pl/mtajchert/clipboardhero/ShareViewModelTest.kt`

`ShareViewModel`'s constructor is pure (`repository`, `settingsRepository`,
`context`, `ioDispatcher`), so the test constructs the **real**
`ImageClipboardRepository` and **real** `SettingsRepository` (backed by a test
DataStore, exactly as `SettingsRepositoryTest` does) — no fakes, no refactor.

Sources are supplied with the same shadow pattern `ImageClipboardRepositoryTest`
already uses:

```kotlin
shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
```

Coroutines: inject a `StandardTestDispatcher` as `ioDispatcher`; set the main
dispatcher for `viewModelScope` via `Dispatchers.setMain(testDispatcher)` in
`@Before` and `Dispatchers.resetMain()` in `@After`. Drive with `runTest { … }`
and `advanceUntilIdle()`, then read `viewModel.copyState.value`.

FileProvider `sCache` is cleared by reflection in `@Before` (same as the repo
test — `ImageClipboardRepository.copyToClipboard` puts a FileProvider URI on the
clipboard).

**Test cases:**

| # | Setup | Assert |
|---|-------|--------|
| 1 | `showConfirmation = true`, readable source | `copyState.value is CopyState.Success` |
| 2 | `showConfirmation = false`, readable source | `copyState.value === CopyState.SilentSuccess` |
| 3 | `showConfirmation = true`, unreadable `content://nope` | `copyState.value === CopyState.Error` |
| 4 | `showConfirmation = false`, unreadable `content://nope` | `copyState.value === CopyState.Error` (toggle never suppresses errors) |
| 5 | null source | `copyState.value === CopyState.Error` (fails `isSafeSource`) |
| 6 | our own FileProvider authority URI | `copyState.value === CopyState.Error` (rejected by `isSafeSource`) |
| 7 | call `copy()` twice with different sources | second call is a no-op; state reflects the first (the `started` guard) |

To control `showConfirmation`, the test calls `settingsRepository.update(CopySettings(showConfirmation = …))`
before `viewModel.copy(...)`.

**New dependency:** `kotlinx-coroutines-test` (version ref `coroutines`, already
`1.11.0`), added as `testImplementation`.

## Part B — `ConfirmationSheetTest` (Compose under Robolectric)

**File:** `app/src/test/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheetTest.kt`

`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`. GraphicsMode
NATIVE is already set in `robolectric.properties`. Uses `createComposeRule()`.

Because `ConfirmationSheet`'s `LaunchedEffect` uses `delay`, the test controls the
clock with `composeTestRule.mainClock.autoAdvance = false` and `advanceTimeBy(...)`.

**Test cases:**

| # | State | Assert |
|---|-------|--------|
| 1 | `Success(null, 2_400_000, 480_000)` | node with `R.string.copied_success` text is displayed |
| 2 | `Error` | node with `R.string.copied_error` text is displayed |
| 3 | `SilentSuccess` | no success/error text displayed |
| 4 | `Pending` | no success/error text displayed |
| 5 | `SilentSuccess` | `onDone` invoked without advancing the clock (immediate) |
| 6 | `Success(...)` | `onDone` NOT invoked before 1500ms; invoked after `advanceTimeBy(1500)` |

`onDone` is a test lambda flipping an `AtomicBoolean`/counter. Strings are read via
`composeTestRule.activity` context or `ApplicationProvider` + `getString`.

**New dependency:** `androidx.compose.ui:ui-test-junit4` (BOM-managed, no explicit
version), added as `testImplementation`.

## Part C — instrumented happy-path (`androidTest`)

**Files:**
- `app/src/androidTest/java/pl/mtajchert/clipboardhero/ShareReceiverActivityTest.kt`
- `app/src/androidTest/AndroidManifest.xml` (declares the test FileProvider)
- `app/src/androidTest/res/xml/test_file_paths.xml` (FileProvider paths)
- `app/src/androidTest/assets/test_image.png` (bundled source image)

**Why a test FileProvider:** `ShareReceiverActivity.isSafeSource` accepts only
`content://` URIs whose authority is **not** our app's FileProvider. A test-only
FileProvider with a distinct authority (`pl.mtajchert.clipboardhero.test.fileprovider`)
serves a real image, satisfies `isSafeSource`, and avoids the MediaStore grant
limitation that breaks `adb`-driven shares.

**Flow:**
1. Copy `test_image.png` from test assets into the test app's files dir; expose it
   through the test FileProvider to get a `content://…test.fileprovider/…` URI.
2. Build `Intent(ACTION_SEND)` with `type = "image/png"`, `EXTRA_STREAM = uri`,
   `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`, component set to
   `ShareReceiverActivity`.
3. `ActivityScenario.launch<ShareReceiverActivity>(intent)` — the activity gains
   focus, so the real `onWindowFocusChanged` → `ShareViewModel.copy()` path runs.
   Use a `createEmptyComposeRule()` so Compose assertions attach to the launched
   activity (custom-intent launch is incompatible with `createAndroidComposeRule`).
4. Ensure `showConfirmation = true` (default) before launch.

**Assertions:**
- `composeRule.onNodeWithText(getString(R.string.copied_success)).assertIsDisplayed()`
- `ClipboardManager.primaryClip.getItemAt(0).uri.authority == "pl.mtajchert.clipboardhero.fileprovider"`
- a `clips/clip_*` file exists in the app's files dir
  (`InstrumentationRegistry.getInstrumentation().targetContext.filesDir`)

**Build changes:**
- `defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }`
  (not currently set).
- `androidTestImplementation`: `androidx.test.ext:junit`, `androidx.test:runner`,
  `androidx.test:rules`, `androidx.compose.ui:ui-test-junit4`.
- `debugImplementation androidx.compose.ui:ui-test-manifest` (required so the test
  activity host is registered for Compose android tests).
- New version-catalog entries in `gradle/libs.versions.toml` for the above.

**Runs on:** `./gradlew :app:connectedDebugAndroidTest` (needs an emulator/device).
This is deterministic and unattended — unlike manual share-sheet driving.

## Verification

- `./gradlew :app:testDebugUnitTest` — Parts A + B run on the JVM in seconds, no
  emulator. This is the everyday signal.
- `./gradlew :app:connectedDebugAndroidTest` — Part C, run before releases or when
  touching the share/clipboard path.

## Invariants respected

- Repositories stay constructor-pure; tests `new` them directly (CLAUDE.md).
- FileProvider `sCache` reset by reflection in any new test using FileProvider.
- Robolectric `graphicsMode = NATIVE` is required and already configured.
- No change to production code is required for A/B; C adds only test-source-set
  files plus build config (a test runner + test deps). The app's
  `isSafeSource`, focus trigger, and copy pipeline are exercised, not modified.
