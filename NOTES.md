# TeleDrive Development Notes

Last updated: 2026-11-07

## Project guardrails

- Active development branch: `codex/file-integrity-phase-1`.
- Keep `master` untouched until the complete release is reviewed and accepted.
- Target platforms: Windows, Android, and Linux. macOS is out of scope.
- Do not delete, migrate, or overwrite the user's original TeleDrive profile, TDLib sessions, or Telegram channel messages during development.
- Use the isolated Windows development profile for desktop testing.
- Preserve the low-RAM requirement: virtualised lists, bounded disk thumbnail cache, streaming/range-based playback and downloads, and no full-file in-memory buffers.
- Mark features as implemented, compiled, locally tested, and user-verified separately. A visible control alone is not proof that its feature works.

## Verified foundation

### Integrity phase 1

- Room database schema upgraded from version 17 to 18 with nullable per-part plaintext SHA-256.
- Per-part SHA-256 metadata is written to the local index and remote manifest.
- Whole-file SHA-256 is calculated and recorded for multipart uploads.
- Resume paths validate already-uploaded source ranges against recorded hashes.
- Multipart downloads verify individual plaintext parts and reject a mismatched part.
- Single-file downloads verify the whole-file hash when one is available.
- The user completed a real 2 GiB multipart upload/download round trip on Windows with a regular Telegram account. The downloaded file's SHA-256 matched the preserved source exactly:
  `ACBD5362FBF89E95900D01B736E9025540E084C23AF3E2D988609AAC06938F47`.
- Migration and integrity tests were added to the desktop test suite.

### Desktop UX already user-verified

- Storage submenu exposes Download and Free up space together when both local and remote copies exist.
- The View downloads snackbar action opens Transfer History.
- Desktop navigation rail exposes Home, Files, Favorites, Offline, Archived, Hidden, Transfers, Trash, Gallery, and Settings.
- Right-click on a file selects it and opens its actions. Individual multi-selection works.
- The user ran Gradle desktop/UI/shared tests successfully after the first right-click selection implementation (commit `3f8b327`).

## Current work — complete the selection experience

Latest selection work is on the active feature branch but has **not yet been built or runtime-tested** after these newest changes.

- Keep a visible checkbox on selectable file/folder cards and list rows; use a 48 dp click target and high-contrast surface.
- Keep normal item clicks for opening items; checkbox clicks toggle selection.
- Right-click selects an unselected item and opens its actions; right-clicking an already-selected item preserves the existing multi-selection.
- Desktop mouse drag begins range selection after normal click slop, using the existing range-selection and edge-scroll infrastructure.
- Touch/stylus retain the long-press gesture.
- Apply the interaction to Files, Gallery, Collections, Search, and selectable Trash rows.
- Existing selection toolbar and Escape/back clearing behaviour remain.
- Build and visually test before marking the always-visible checkboxes and mouse drag-sweep complete.

## Milestone 1 — Explorer-grade file management

Existing foundation:
- Breadcrumb navigation is present.
- Search and category/backup filters are present.
- Sort fields include name, size, modified date, added date, type, and backup status.
- Grid and list view modes exist.
- Folder and file selection, favorites, offline, archive, hidden, and trash actions are present.

Still to implement:
- A true folder-tree navigation pane.
- Details/table view with useful columns and sorting.
- A dedicated compact list density/layout.
- Tags and tag filters.
- Unified context menus and selection behaviour in every file-bearing screen.
- Persist view preference per drive/folder where appropriate.

The current `ViewMode` enum only has `GRID` and `LIST`; add new modes carefully and keep migrations/backward compatibility safe.

## Remaining roadmap

### Milestone 2 — Backup and safety
- Automatic server-side mirroring to configured backup channels.
- Per-channel message ID mapping and verification.
- Retry failed mirror operations and report backup health.
- Restore/repair from a surviving backup copy.
- App-side delete protection; normal Trash operations must remain local metadata-only.
- Typed confirmation for destructive actions.
- Document and verify Telegram admin permissions without granting unnecessary delete rights.

### Milestone 3 — Multi-account failover
- Independent TDLib sessions for the user's five authorised Telegram accounts.
- Startup health checks and clear account status.
- Automatic failover for new and in-progress queued transfers.
- Add-account flow for future accounts.
- Never discard a valid session or silently switch to an unauthorised account.

### Milestone 4 — Transfer hardening
- Flood-safe pacing and bounded concurrency.
- Bandwidth caps and night scheduling.
- Choose download destination and rename per download.
- Bulk folder download and queue priority controls.
- Verify pause/resume and recovery after app termination, internet loss, and reboot.
- Preserve the known TDLib limitation: pausing a single-message upload may restart that one file; split uploads resume from completed parts.

### Milestone 5 — Gallery
- Date timeline and media-type filters.
- Slideshow and better zoom/viewer controls.
- Album/favorites improvements.

### Milestone 6 — Home dashboard
- Recent files and recently played media.
- Resumable transfer summary and quick actions.
- Per-drive storage statistics.

### Milestone 7 — Integrity scrub and recovery
- Scheduled/background audit of file parts and hashes.
- Verify primary and backup message existence.
- Repair missing/corrupt parts from a surviving backup.
- Record checksums for forwarded/imported files on first verified download.
- Surface actionable health reports without silently changing user data.

### Milestone 8 — Performance and platform validation
- Profile memory and CPU on an 8 GB Windows machine.
- Keep lists virtualised and thumbnails disk-cached with a cap.
- Verify range-based playback/downloads and streaming buffers.
- Build and test Windows, Android, and Linux. macOS is not a release target.
- Perform regression, migration, and real-account smoke tests.

## Open verification items

- The user previously saw a DataStore multiple-instance exception during a desktop launch. It was not proven to recur after the later UX changes; capture and investigate if it appears again.
- The newest persistent-checkbox and mouse-drag selection changes need a fresh build and runtime test.
- Android and Linux builds/runtime behaviour have not yet been verified by the user in this development session.
- Desktop development runs have excluded the VLC native preparation/download tasks because the external VLC mirror stalled; inline VLC playback is therefore not part of the current dev smoke test.

## Final real-account smoke test

Before release, the user must verify with their Telegram accounts:
1. Upload a file and confirm it is mirrored to the main and backup channels.
2. Download, pause, resume, and verify the file hash.
3. Terminate the app mid-transfer, relaunch, and confirm recovery.
4. Temporarily sign out/unavailable one account and verify another authorised account takes over.
5. Confirm no ordinary app action deletes the remote Telegram messages.
