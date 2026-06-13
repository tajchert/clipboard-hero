# Copy History, Direct Share & Privacy — Design

**Date:** 2026-06-13
**Status:** Approved
**Builds on:** MVP spec (2026-06-12), compression settings spec (2026-06-13)

## Purpose

Three additions that make the app sticky and trustworthy:

1. **Copy history** — the clipboard is volatile (any copy overwrites it; the OS
   auto-clears). Keeping the last 10 clips with tap-to-recopy turns a lost image
   into a two-tap recovery.
2. **Direct Share ranking** — a published sharing shortcut lets the system rank the
   app in the share sheet's top row, cutting a scroll-and-hunt on every use.
3. **Privacy controls** — clipboard images can be sensitive. Clear-now, optional
   auto-delete, and a history-off switch keep the storage story trustworthy.
   Everything stays local; no background services.

## Decisions

| Decision | Choice |
|---|---|
| History size | 10 newest (1 when history disabled) |
| History UI | Grid card on main screen (3 columns, chunked rows), replaces "Last copied"; tap = re-copy, long-press = delete, trash icon = clear all; hidden when empty |
| Auto-delete | Off by default; options 1 h / 24 h / 7 d; lazy enforcement (pruned on app/share access, no background work) |
| Storage | Filesystem only: `clips/clip_<epochMillis>.<ext>`; no database |
| Direct Share | One dynamic sharing shortcut + `<share-target>` XML, pushed on app open and after each successful copy |

## Architecture

### Storage & retention (`ImageClipboardRepository` rework)

- Clip files: `clips/clip_<epochMillis>.<ext>`. Timestamp parsed from the name;
  a legacy `clip.<ext>` (pre-history installs) is included using `lastModified()`.
- `RetentionPolicy(maxItems: Int, ttlMillis: Long?)` — passed into the repository
  by callers; the repository stays ignorant of settings storage.
  Derived as: `maxItems = if (historyEnabled) 10 else 1`, `ttlMillis` from `AutoDelete`.
- `prune(policy)` deletes files beyond `maxItems` (newest kept) and older than
  `ttlMillis`. Runs after every copy and on every read (`history()`,
  `latestImage()`) — lazy expiry, no scheduler.
- API:
  - `copyToClipboard(uri, mime, settings, retention): Result<CopiedImage>` —
    unchanged flow + timestamped target name + prune.
  - `history(retention): List<CopiedImage>` — newest first, pruned first.
  - `delete(image: CopiedImage)` — best-effort single-file delete.
  - `clearAll()` — deletes every clip file and clears the system clipboard
    (`clearPrimaryClip()`, wrapped in runCatching — some OEMs throw).
  - `recopy(image)` — unchanged.
- Injectable clock (`clock: () -> Long`, default `System.currentTimeMillis`) for
  TTL tests.
- `incoming.tmp` continues to be excluded from history (name filter `clip_`/`clip.`).

### Settings

- `PrivacySettings(historyEnabled: Boolean = true, autoDelete: AutoDelete = AutoDelete.OFF)`
- `enum AutoDelete(val hours: Int?) { OFF(null), H1(1), H24(24), D7(168) }`
- `SettingsRepository` gains `privacySettings: Flow<PrivacySettings>` and
  `suspend fun updatePrivacy(PrivacySettings)`; same DataStore file, same
  invalid-value-falls-back-to-default behavior.
- `ShareReceiverActivity` reads both settings flows (`first()`) in its IO coroutine
  and derives the `RetentionPolicy`.

### Direct Share

- `res/xml/shortcuts.xml` with a `<share-target>`: mime `image/*`, targeting
  `ShareReceiverActivity`, category `pl.tajchert.imagetoclipboard.SHARE_TARGET`.
- Manifest: `<meta-data android:name="android.app.shortcuts" ...>` on MainActivity.
- `ShareShortcuts.publish(context)` (small object): pushes one dynamic shortcut
  (id `copy-to-clipboard`, label "Copy to clipboard", app icon, that category,
  `setLongLived`) via `ShortcutManagerCompat.pushDynamicShortcut`.
- Called from `MainActivity.onCreate` and after each successful copy in
  `ShareReceiverActivity` — usage signal feeds the system's share ranking.

### UI

- **History card** (`HistoryCard` in MainScreen file): header (title + trash
  `IconButton`), rows of 3 thumbnails (`items.chunked(3)`, plain `Row`s — no lazy
  grid inside the scrollable column). Newest thumbnail drawn with a 2 dp primary
  border. Tap → `recopy` + toast; long-press → `delete` + refresh. Card omitted
  when history is empty.
- **Settings card additions**: "Keep history" `Switch` row; "Auto-delete"
  segmented row (Off / 1 h / 24 h / 7 d) — always visible (applies to the single
  kept clip even when history is off).
- `MainActivity` holds `history: List<HistoryItemUi>` state, refreshed in
  `onResume` and after delete/clear/re-copy actions. Thumbnails decoded via the
  existing `Thumbnails.decode` at small size (≤256 px).

## Error handling

- Delete/clear are best-effort: missing files ignored, clipboard-clear failures
  swallowed (log only) — UI refreshes from disk state regardless.
- Pruning failures never fail the copy.
- Unparseable clip filenames are treated as legacy items (mtime), never crash.

## Out of scope

Pinning items, history search, multi-select delete, `ACTION_SEND_MULTIPLE`,
QS tile (separate future feature).

## Testing

Robolectric (fake clock injected where time matters):

- Copy creates `clip_<ts>.<ext>`; 11th copy evicts the oldest; `history()` is
  newest-first; TTL expiry removes old items on read; `maxItems=1` (history off)
  keeps only the latest; legacy `clip.png` appears in history; `delete` removes
  one item; `clearAll` empties the dir and nulls the primary clip.
- `PrivacySettings` defaults + round-trip + invalid enum fallback.
- After `ShareShortcuts.publish`, `ShortcutManagerCompat.getDynamicShortcuts`
  contains id `copy-to-clipboard`.
- Existing transform/repository tests updated to the new signature.

Emulator: share twice → history shows 2 items; tap older → clipboard chip
appears; long-press → item gone; clear → card disappears; share sheet shows the
app as a direct-share row after several uses.
