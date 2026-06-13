# Show-confirmation toggle — design

## Goal

Let the user turn off the confirmation card that appears after sharing an image
into Clipboard Hero. Some users share constantly and find the auto-dismissing
success card redundant; the copy itself is the point, not the feedback.

The toggle suppresses **only the success card**. Failures always surface, so a
silent setting never hides a broken copy.

## Setting

Add to `settings/CopySettings.kt`:

```kotlin
data class CopySettings(
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 90,
    val maxDimension: MaxDimension = MaxDimension.ORIGINAL,
    val showConfirmation: Boolean = true,   // ON = current behavior
)
```

Default is `true` — existing users see no change.

`SettingsRepository`:
- New key `private val KEY_SHOW_CONFIRMATION = booleanPreferencesKey("show_confirmation")`.
- Read in the `settings` flow: `showConfirmation = prefs[KEY_SHOW_CONFIRMATION] ?: defaults.showConfirmation`.
- Write in `update()`: `prefs[KEY_SHOW_CONFIRMATION] = settings.showConfirmation`.

Follows the existing invalid-value-falls-back-to-default pattern; a missing key
yields the default, never a crash.

## UI

`ui/MainScreen.kt`, inside `SettingsCard` (the Copy settings card — the setting
lives in `CopySettings`, so it belongs here, not the privacy rows). Add a
`Switch` row structurally identical to the existing "Keep history" row:

- Whole row is one `toggleable` target with `role = Role.Switch`.
- `Switch(checked = settings.showConfirmation, onCheckedChange = null)` — non-focusable so it isn't a second a11y stop.
- `onValueChange = { onSettingsChange(settings.copy(showConfirmation = it)) }`.

New string resource `show_confirmation` = "Show confirmation".

## Behavior

`ui/ConfirmationSheet.kt` — add a state to the `CopyState` sealed interface:

```kotlin
data object SilentSuccess : CopyState
```

`LaunchedEffect` in `ConfirmationSheet`:

```kotlin
LaunchedEffect(state) {
    when (state) {
        CopyState.Pending -> Unit                 // still waiting on the copy
        CopyState.SilentSuccess -> onDone()       // close immediately, draw nothing
        else -> { delay(DISMISS_DELAY_MS); onDone() }  // Success / Error: 1.5s card
    }
}
```

The `when (state)` render block maps `SilentSuccess -> Unit` (no card). `Success`
and `Error` render exactly as today.

`ShareViewModel.copy()` already loads `settingsRepository.settings.first()`.
On success, branch on the flag:

```kotlin
repository.copyToClipboard(sourceUri, mimeType, settings, retention)
    .onSuccess { ShareShortcuts.publish(context) }
    .map { result ->
        if (settings.showConfirmation) {
            CopyState.Success(Thumbnails.decode(result.file), result.originalBytes, result.finalBytes)
        } else {
            CopyState.SilentSuccess
        }
    }
    .onFailure { Log.w(TAG, "Copy failed for $sourceUri", it) }
    .getOrDefault(CopyState.Error)
```

`Error` is returned unchanged by `getOrDefault`, so failures always show the
error card regardless of the toggle.

### Why a new state vs. the activity reading the setting

Keeping `SilentSuccess` in `CopyState` means all dismiss/render logic stays in
`ConfirmationSheet`. The activity continues to host `ConfirmationSheet(state,
onDone = ::finish)` with no new branching, and the VM remains the single source
of truth for what the user should see.

## Invariants honored

- **#1 (clipboard write on window focus):** unchanged. The copy and shortcut
  publish still run from `onWindowFocusChanged` → `ShareViewModel.copy()`. Only
  the post-copy feedback UI changes.
- **#2–#5 (compression / pass-through):** untouched — no transformer changes.
- No new storage, services, or permissions.

## Tests

- `settings/SettingsRepositoryTest.kt`: `showConfirmation` round-trips through
  `update()`/`settings`, and defaults to `true` when the key is absent. Build the
  DataStore via `PreferenceDataStoreFactory` + `TemporaryFolder` (file ending
  `.preferences_pb`), per the existing test pattern.
- New `ShareViewModelTest.kt`: with the setting off, a successful copy emits
  `CopyState.SilentSuccess`; with it on, `CopyState.Success`. A failed copy emits
  `CopyState.Error` in both cases. Reset `FileProvider.sCache` via reflection in
  `@Before` and inject the repository's `clock` — both per CLAUDE.md gotchas.
  (If wiring `ShareViewModel` under Robolectric proves heavy, fall back to
  asserting the success/error mapping at the repository boundary and cover the
  flag branch with the settings round-trip test.)

## Out of scope

- No separate control over the error card or the auto-dismiss duration.
- No per-format or per-source variation — one global boolean.
