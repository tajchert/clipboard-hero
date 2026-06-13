# Show-confirmation Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-facing toggle that suppresses the success confirmation card after a copy, while always still surfacing errors.

**Architecture:** A new `showConfirmation` boolean on `CopySettings` (persisted in the existing settings DataStore) drives a `Switch` in the Copy settings card. When off, `ShareViewModel` emits a new `CopyState.SilentSuccess` that `ConfirmationSheet` renders as nothing and dismisses immediately; errors still emit `CopyState.Error` and show the error card.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Preferences DataStore, Hilt, Robolectric unit tests.

---

### Task 1: Add `showConfirmation` to `CopySettings`

**Files:**
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/settings/CopySettings.kt`

- [ ] **Step 1: Add the field**

Edit the `CopySettings` data class to add a fourth property (keep the existing JPEG comment):

```kotlin
data class CopySettings(
    // JPEG over WebP: some paste targets (e.g. Telegram) mishandle WebP clipboard images
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 90,
    val maxDimension: MaxDimension = MaxDimension.ORIGINAL,
    // ON = current behavior: show the success card after a copy
    val showConfirmation: Boolean = true,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/pl/mtajchert/clipboardhero/settings/CopySettings.kt
git commit -m "feat(settings): add showConfirmation field to CopySettings"
```

---

### Task 2: Persist `showConfirmation` in `SettingsRepository`

**Files:**
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/settings/SettingsRepository.kt`
- Test: `app/src/test/java/pl/mtajchert/clipboardhero/settings/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `SettingsRepositoryTest`. The tests themselves only use `CopySettings`, `runBlocking`, `first`, and `assertEquals` — all already imported in the test file, so no new imports are needed for this step.

```kotlin
    @Test
    fun `showConfirmation defaults to true when nothing stored`() = runBlocking {
        val repo = SettingsRepository(newDataStore())

        assertEquals(true, repo.settings.first().showConfirmation)
    }

    @Test
    fun `showConfirmation round-trips`() = runBlocking {
        val repo = SettingsRepository(newDataStore())
        val wanted = CopySettings(showConfirmation = false)

        repo.update(wanted)

        assertEquals(false, repo.settings.first().showConfirmation)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.settings.SettingsRepositoryTest"`
Expected: FAIL — `showConfirmation round-trips` fails because `update()` never writes the key, so it reads back the default `true` instead of `false`. (The defaults test passes trivially.)

- [ ] **Step 3: Wire the key into the repository**

In `SettingsRepository.kt`, add the key to the `companion object` alongside the others:

```kotlin
        private val KEY_SHOW_CONFIRMATION = booleanPreferencesKey("show_confirmation")
```

(`booleanPreferencesKey` is already imported in this file.)

In the `settings` flow's `map`, add the field to the `CopySettings(...)` construction:

```kotlin
            CopySettings(
                format = prefs[KEY_FORMAT].toEnumOrNull<OutputFormat>() ?: defaults.format,
                quality = prefs[KEY_QUALITY]?.coerceIn(50, 100) ?: defaults.quality,
                maxDimension = prefs[KEY_MAX_DIMENSION].toEnumOrNull<MaxDimension>() ?: defaults.maxDimension,
                showConfirmation = prefs[KEY_SHOW_CONFIRMATION] ?: defaults.showConfirmation,
            )
```

In `update()`, persist it:

```kotlin
    suspend fun update(settings: CopySettings) {
        dataStore.edit { prefs ->
            prefs[KEY_FORMAT] = settings.format.name
            prefs[KEY_QUALITY] = settings.quality
            prefs[KEY_MAX_DIMENSION] = settings.maxDimension.name
            prefs[KEY_SHOW_CONFIRMATION] = settings.showConfirmation
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "pl.mtajchert.clipboardhero.settings.SettingsRepositoryTest"`
Expected: PASS (all tests, including the existing round-trip which now also covers the new default).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/pl/mtajchert/clipboardhero/settings/SettingsRepository.kt \
        app/src/test/java/pl/mtajchert/clipboardhero/settings/SettingsRepositoryTest.kt
git commit -m "feat(settings): persist showConfirmation in SettingsRepository"
```

---

### Task 3: Add `SilentSuccess` state and immediate-dismiss behavior

**Files:**
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheet.kt`

This task has no unit test — `ConfirmationSheet` is a Composable with timing logic; it is exercised via the device verification in Task 6. Keep the change minimal and mechanical.

- [ ] **Step 1: Add the state to the sealed interface**

In `ConfirmationSheet.kt`, add a `SilentSuccess` object to `CopyState`:

```kotlin
sealed interface CopyState {
    data object Pending : CopyState
    data class Success(
        val thumbnail: Bitmap?,
        val originalBytes: Long,
        val finalBytes: Long,
    ) : CopyState
    data object SilentSuccess : CopyState
    data object Error : CopyState
}
```

- [ ] **Step 2: Dismiss immediately on `SilentSuccess`**

Replace the `LaunchedEffect` in `ConfirmationSheet` with a `when` that closes instantly for `SilentSuccess`:

```kotlin
    LaunchedEffect(state) {
        when (state) {
            CopyState.Pending -> Unit                // still waiting on the copy
            CopyState.SilentSuccess -> onDone()      // close immediately, draw no card
            else -> {                                // Success / Error: auto-dismiss card
                delay(DISMISS_DELAY_MS)
                onDone()
            }
        }
    }
```

- [ ] **Step 3: Render nothing for `SilentSuccess`**

In the `when (state)` block inside the `Box`, add a `SilentSuccess` branch that draws nothing:

```kotlin
        when (state) {
            CopyState.Pending -> Unit
            CopyState.SilentSuccess -> Unit
            is CopyState.Success -> ResultCard(
                thumbnail = state.thumbnail,
                message = stringResource(R.string.copied_success),
                subtitle = sizeSubtitle(state.originalBytes, state.finalBytes),
                isError = false,
            )
            CopyState.Error -> ResultCard(
                thumbnail = null,
                message = stringResource(R.string.copied_error),
                subtitle = null,
                isError = true,
            )
        }
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (exhaustive `when` now covers all four states).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/pl/mtajchert/clipboardhero/ui/ConfirmationSheet.kt
git commit -m "feat(ui): add SilentSuccess CopyState that dismisses without a card"
```

---

### Task 4: Emit `SilentSuccess` from `ShareViewModel` when the toggle is off

**Files:**
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ShareViewModel.kt`

`ShareViewModel` already reads `settingsRepository.settings.first()` inside `copy()`. Branch the success mapping on `settings.showConfirmation`. There is no existing `ShareViewModelTest`; wiring the Hilt VM under Robolectric for one branch is heavy, and the success/error mapping is the only new logic — it is covered by the settings round-trip test (Task 2) plus device verification (Task 6). Do not add a VM test.

- [ ] **Step 1: Branch the success mapping**

Replace the `.map { ... }` call in `copy()` with the toggle-aware version:

```kotlin
                repository.copyToClipboard(sourceUri, mimeType, settings, retention)
                    .onSuccess { ShareShortcuts.publish(context) }
                    .map { result ->
                        if (settings.showConfirmation) {
                            CopyState.Success(
                                Thumbnails.decode(result.file),
                                result.originalBytes,
                                result.finalBytes,
                            )
                        } else {
                            CopyState.SilentSuccess
                        }
                    }
                    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
                    .getOrDefault(CopyState.Error)
```

(`Error` is returned by `getOrDefault` on failure regardless of the toggle, so failures always show the error card.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/pl/mtajchert/clipboardhero/ShareViewModel.kt
git commit -m "feat(share): emit SilentSuccess when confirmation toggle is off"
```

---

### Task 5: Add the toggle to the Copy settings card

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/pl/mtajchert/clipboardhero/ui/MainScreen.kt`

- [ ] **Step 1: Add the string resource**

In `strings.xml`, add after the `settings_title` line (line 10):

```xml
    <string name="show_confirmation">Show confirmation</string>
```

- [ ] **Step 2: Add the Switch row to `SettingsCard`**

In `MainScreen.kt`, inside `SettingsCard`'s `Column`, add a toggle row. Place it right after the `max_size` `LabeledSegmentedRow` (the block ending at the `onSelect = { onSettingsChange(settings.copy(maxDimension = it)) },` closing `)`), before the existing "Keep history" row. It mirrors the existing history toggle but drives `onSettingsChange`:

```kotlin
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Whole row is one toggle target labelled by the text; the Switch
                // is non-focusable (onCheckedChange = null) so it isn't a second stop.
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.showConfirmation,
                        role = Role.Switch,
                        onValueChange = { onSettingsChange(settings.copy(showConfirmation = it)) },
                    ),
            ) {
                Text(
                    text = stringResource(R.string.show_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.showConfirmation,
                    onCheckedChange = null,
                )
            }
```

All referenced symbols (`Row`, `toggleable`, `Role`, `Switch`, `Text`, `stringResource`, `Modifier.weight`, `Alignment`) are already imported in `MainScreen.kt` — the existing "Keep history" row uses every one of them.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run lint to confirm no new errors**

Run: `./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL (warnings OK, errors not).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/pl/mtajchert/clipboardhero/ui/MainScreen.kt
git commit -m "feat(ui): add Show confirmation toggle to Copy settings card"
```

---

### Task 6: Full build, test suite, and device verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Build and install the debug APK**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installed. (Use `adb -s <serial>` if several devices are attached.)

- [ ] **Step 3: Verify toggle ON (default) shows the card**

Open the app, confirm "Show confirmation" is ON. Share an image (screenshot via `adb shell input keyevent 120`, then the HUD share button → "Copy to clipboard"). Expected: the success card appears and auto-dismisses; Gboard shows the image chip.

- [ ] **Step 4: Verify toggle OFF suppresses the card but still copies**

In the app, turn "Show confirmation" OFF. Share an image again. Expected: no success card appears, the share activity closes immediately, AND the copy still works — confirm with `adb exec-out run-as pl.mtajchert.clipboardhero ls files/clips/` (a new `clip_<epochMillis>` file) and a Gboard image chip.

- [ ] **Step 5: Commit any verification notes (optional)**

No code changes expected here. If verification surfaced a fix, commit it with a descriptive message.

---

## Notes for the implementer

- **Invariant #1 (clipboard write on window focus):** unchanged — the copy still triggers from `ShareReceiverActivity.onWindowFocusChanged` → `ShareViewModel.copy()`. Only the post-copy feedback UI changes. Do not move the trigger.
- **Invariants #2–#5 (compression / pass-through):** untouched — no `ImageTransformer` or repository changes in this plan.
- **Default is `true`** so existing users see no behavior change after upgrade.
- Errors always show the error card regardless of the toggle — this is intentional (only success is suppressed).
