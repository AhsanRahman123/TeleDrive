# TeleDrive Development Status Register

Last audited: 2026-11-12
Repository: `AhsanRahman123/TeleDrive`
Development branch: `work/desktop-feature-fixes-20261112`
Stack base: PR #9 head `36f3da98f960c879c548099b30150c74ee9b33e5`
Latest code head audited: `5d655c13a09fc3320eae93b5a93698ded84b0f05`. Documentation-only commits follow this code head; fetch the branch tip before building and record its exact SHA.

This register is the current status source for the consolidated development workspace. Older planning documents remain useful for feature intent, but their status labels may be stale. When this register conflicts with an older note, re-audit the source and tests before changing the status.

## 1. Evidence labels — do not collapse these into one "done" status

- **USER-VERIFIED (specific build):** The user personally performed the acceptance scenario and reported the expected result. Record the tested commit/build where known.
- **AUTOMATED-TESTED:** A relevant automated test passed. This does not prove the live UI or real Telegram behavior works.
- **COMPILED:** The relevant target compiled. This does not prove runtime behavior.
- **SOURCE-PRESENT:** The feature implementation exists in the current branch. It still needs the appropriate build/test/runtime proof.
- **SUSPECTED / CONFLICTING NOTE:** Older documents or a prior implementation claim say it was done, but current source and evidence have not been reconciled.
- **NOT IMPLEMENTED (last source audit):** A direct source audit found the capability absent. Re-check only if later commits could have changed it.
- **PLANNED:** Requested but not yet evidenced as implemented.
- **DEFERRED:** Intentionally not part of the current Windows-first work.

Every feature should move through these labels independently. Never promote a feature to user-verified just because a button exists or a build succeeds.

## 2. Consolidated source of truth

The current working branch is `work/desktop-feature-fixes-20261112`. It is stacked on PR #9 head `36f3da98` and contains the sequential feature history represented by draft PRs #6–#9 plus the new Windows feature batch in draft PR #10. The latest audited PR #10 head is `380f40a6` (2026-11-12); it must be freshly built and runtime-tested before promotion.

- PR #6: https://github.com/AhsanRahman123/TeleDrive/pull/6
- PR #7: https://github.com/AhsanRahman123/TeleDrive/pull/7
- PR #8: https://github.com/AhsanRahman123/TeleDrive/pull/8
- PR #9: https://github.com/AhsanRahman123/TeleDrive/pull/9
- PR #10: https://github.com/AhsanRahman123/TeleDrive/pull/10 (draft; current Windows feature batch, not merged)
- Current branch: https://github.com/AhsanRahman123/TeleDrive/tree/work/desktop-feature-fixes-20261112

The old `codex/windows-ddcci-player-controls` line (PR #5) is closed and superseded by PR #6. It has 23 commits that diverged from the accepted line. Do not merge or copy that whole branch into this baseline. The restored physical-monitor brightness and volume work is already represented in the newer PR #6+ history, with subsequent player fixes on top.

Do not use the abandoned macOS/glass appearance experiment as a source of code. macOS and appearance redesign are out of scope. Keep the old experimental folder untouched.

## 3. Evidence already recorded

### User-verified on earlier test checkouts

- **2 GiB multipart integrity round trip — USER-VERIFIED.** The user uploaded and downloaded a real 2 GiB file on Windows. The resulting SHA-256 matched the source exactly: `ACBD5362FBF89E95900D01B736E9025540E084C23AF3E2D988609AAC06938F47`. This proves that specific integrity path on that tested build; repeat a smaller regression test on the consolidated baseline before release.
- **Free up space with an external TDLib cache path — USER-VERIFIED on the PR #9 storage test checkout.** For a roughly 150 MB test file, the app-managed Room/download copy was removed and the Room local path cleared, while the separate external TDLib file remained untouched.
- **Free up space when TDLib has no local path — USER-VERIFIED on the PR #9 storage test checkout.** For a roughly 200 MB test file with no TDLib local path, the managed Room copy was removed and the Telegram cloud backup remained available.
- Earlier desktop UX checks reported working: navigation rail, Storage Download/Free up space submenu, View downloads navigation to Transfer History, right-click on a file opening its item actions, and individual multi-selection. Recheck these once on the consolidated baseline because later changes touched adjacent screens.

### Automated tests / build evidence

- The focused `LocalCopyCacheCleanupTest` passed 6/6 on the storage-fix patch branch.
- The same storage fix was applied to the PR #9 head. A combined ` :shared:desktopTest --tests ... :desktop:compileKotlin ` run on the PR #9 head succeeded; the focused report was separately 6/6 on the patch branch.
- The storage test checkout at PR #9 head launched the desktop app and passed the two manual Free up space scenarios listed above.
- Integrity migration/verification tests were added to the desktop test suite during the earlier integrity work.
- PR #8 recorded successful 100 MB and 200 MB real downloads on its test build. PR #9 subsequently changed managed TDLib download handling, so those download scenarios must be repeated on the consolidated baseline.
- PR #7 contains transfer queue stabilization changes and tests, but the live queue behavior must still be retested on the final baseline.

These results are evidence for the exact scenarios and checkouts listed, not a blanket release sign-off.

## 4. Current-source features that need final Windows acceptance testing

These features are present in the consolidated branch's implementation history or current source, but do not yet have a complete, fresh end-to-end acceptance pass on the exact consolidated checkout.

### Player and physical-monitor brightness

Latest known player source change: `e73a04108fdbc4c409c5741fb0b88639a9d21fef` (2026-11-10), which qualifies serialized VLC control calls.

The newer PR #6+ player line includes:
- Physical Windows DDC/CI monitor brightness, using the restored Monitorian-like control path rather than dimming video pixels with a ColorFilter/ColorMatrix overlay.
- Brightness polling/write guards to avoid stale polling while dragging or just after writes, and latest-value-wins writes.
- Brightness slider visibility retained through transient DDC read failures and media changes.
- VLC volume/mute controls, with latest volume state reapplied after playback/audio output attaches.
- Serialized VLC control calls away from native callback/UI execution.
- Player queue, previous/next, seeking, playback speed, fullscreen, shuffle/repeat, sleep timer, and audio/subtitle control work from the player feature line.
- Persisted slider/control auto-hide preferences.

**Status: SOURCE-PRESENT; latest source requires a fresh Windows build and focused live retest.** Prior DDC/CI testing occurred on earlier checkouts. Do not claim that every latest player change is verified until the exact current branch passes the player section in `WINDOWS_ACCEPTANCE_TEST_PLAN.md`.

### Transfers and managed TDLib downloads

The current feature line includes:
- A selectable global download-worker cap, with worker-count handling aligned across UI, ViewModel and queue drainer.
- Pause All / Resume All and cancellation coordination with native TDLib requests.
- Progress based on TDLib cumulative byte counts and periodic sampling, rather than an invented UI-only progress ramp.
- Waiting-for-Telegram status for inactive native transfers.
- Restored TDLib UpdateFile progress handling, timeout handling, priority/progress event payloads, and progress accounting.
- Managed native TDLib download attach/re-attach, pause/resume/cancel, legacy-path fallback and managed-entry cleanup after successful finalization.
- Storage/free-space rules that keep managed Room copies, TDLib paths and Telegram cloud copies distinct.

**Status: SOURCE-PRESENT; AUTOMATED-TESTED/COMPILED in portions of the PR chain; final live queue/download acceptance is still required.** This is high priority because the user previously saw selecting three workers run only two, a third item stuck without progress, confusing resume behavior and progress that did not feel like FDM/IDM. Specifically retest worker cap 1/2/3, pause/resume, cancel/cancel-all, reattach after restart, true byte progress, same-DC Telegram limits, and a fresh download immediately after cancellation.

### Gallery, thumbnails, viewer and cache manager

The branch includes work on:
- Gallery media search and Favorites/Offline/Backed up/Not backed up filters.
- Filter-preserving next/previous preview navigation.
- Grid/List layouts, album rows, album sorting and regular-folder-backed albums, including empty folder albums.
- Bulk Gallery actions: Copy, Share (when local paths exist), Details, Move, Download, Upload, Favorite, Offline, Hide, Archive and Trash.
- Thumbnail manager inventory, generation state, queue controls, folder-aware cache accounting and cache manager tabs.
- Viewer/player integration and folder/file operations across the feature line.

**Status: SOURCE-PRESENT; earlier user testing exists for parts of Gallery and viewer; latest consolidated runtime pass is still required.** Test every action that affects actual files, especially moving the currently open item, deleting/restoring, download/stream playback, cache-size accounting, and queue cancellation. Do not assume a previous Gallery test covers later queue/storage changes.

## 5. New Windows feature batch — PR #10

**Latest audited source:** 766461191551948b4cdabeef9340a18d1375fed7 (2026-11-12). PR #10 is draft and has not been merged.

Source changes present on the branch:
- Playlist menu no longer nests an independently vertically scrolling Column inside the Compose DropdownMenu; the queue menu relies on its own bounded scrolling.
- Preview navigation retains one VLC player for same-media-type queue transitions, keeps the prior local/streamed media and matching file identity during the next preview's Loading state, and no longer keys the native player composable by every file ID.
- Player control visibility is preserved across file changes; H hides controls on one press and shows on a second press within 450 ms; a dedicated hide button is present.
- Preview Back no longer forces fullscreen off. Desktop fullscreen remembers/restores the prior window placement; F11 is handled at the app root.
- Desktop image preview uses a full-resolution painter with high-quality filtering. Locally generated grid thumbnails are bounded at 768 px with explicit JPEG quality 0.86; Telegram photo-preview selection prefers the largest available size up to 1024 px. After a successful original image/video download, an existing cached thumbnail is queued for replacement from the local original only when the effective policy is Automatic; the worker's force flag now bypasses the cached-entry skip and replaces the same cache entry rather than adding another copy.
- Gallery list and compact grid rows expose parent-folder paths; Gallery grid has explicit zoom controls and an expanded 2–12 column range on wide windows.
- Files screen has a multi-tab workspace with per-tab folder navigation/scroll-position restoration; Ctrl+C/Ctrl+X/Ctrl+V/Ctrl+N/Delete/F2/Backspace shortcuts are connected; copy clipboard content remains reusable across paste while cut content is consumed.
- File and folder rows provide in-app drag payloads in Grid/List/Details; tab drop targets dispatch Move or Copy to the destination tab's current folder.
- Folder selections expand into storage audit; Free up space has a Backed-up local copies inventory listing app and TDLib cache paths, allowing reveal/inspection while relying on repository-level managed-root, remote-verification and Available Offline protections. Visible inventory rows automatically inspect TDLib cache state with a four-request concurrency cap; they show path/size or a no-cache state and retain manual refresh.
- Hover tooltips were added to primary Files, Gallery, Preview/player, Home, Search, Collections, Channels, Trash, Transfers, Settings, Cache Manager and Thumbnail Manager/Queue icon actions.
- TDLib account-level speed-limit notifications are exposed to Transfers as a diagnostic. This does not bypass a Telegram-side account limit or itself increase download speed.

**Status: SOURCE-PRESENT.** The previous 1dbb6ba4 Windows smoke build/launch recorded in section 10 predates PR #10's changes. No Windows build/test or live user acceptance has yet been confirmed on PR #10 code head `76646119`. GitHub Actions workflow-run query for `766461191551948b4cdabeef9340a18d1375fed7` returned zero runs at the last check. Do not promote these items to COMPILED or USER-VERIFIED.

Immediate Windows acceptance needed on the exact latest head:
1. Run :shared:desktopTest, :ui:desktopTest, :desktop:test and :desktop:compileKotlin, then package/run through the isolated desktop profile.
2. Re-test playlist opening, same-codec video switching without a flash, local and streamed media, fullscreen/back/exit, H one/two press, hide button, and physical DDC/CI brightness/volume.
3. Test Files tab switching, tab close, per-tab navigation/scroll restore, Grid/List/Details drag payloads, same-tab drop rejection, cross-tab Move/Copy, Ctrl+C/X/V/N/Delete/F2/Backspace and blank-space right-click.
4. Inspect the Local copies screen with app-managed, external/original, TDLib-only, Available Offline and remote-unverified test files before deleting anything.
5. Test Gallery path visibility and added thumbnail-size controls; verify original source dimensions and zoom.
6. Measure real download bytes/second against a fresh disposable file. A TDLib speed-limit notification is evidence of Telegram-side throttling, not an app speed fix. If no notification appears, preserve transfer diagnostic logs and investigate the network/DC path separately.

---
## 6. Suspected, conflicting or last-audit gaps — keep these visible

These are deliberately not marked complete. Resolve each by inspecting the current source, running a focused test, and recording the result.

| Item | Current evidence / uncertainty | Next proof required |
|---|---|---|
| Blank-space right-click Add menu in Files | Current PR #10 source attaches `onDesktopEmptySpaceContextClick` to the Files content surface, opens the Add menu on blank-space right-click and closes it on blank-space left click. | SOURCE-PRESENT; verify Grid, List, empty folder, and item right-click does not open Add too. |
| Copy to… / Move to… from the open image/video viewer | Prior source audit said the preview overflow menu did not expose these actions, though shared repository and folder-picker infrastructure exists. Later commits may have changed it. | Inspect current preview screens; test destination creation, bytes/metadata, viewer sequence behavior and remote backup preservation. |
| Desktop image zoom | PR #10 source uses the original-size image painter, high-quality filtering, the existing desktop zoomable state and explicit zoom buttons. | SOURCE-PRESENT; test desktop double-click/buttons/pan and Android pinch/double-tap/buttons as applicable. |
| Always-visible selection checkbox and drag-sweep selection | An older NOTES entry says the latest checkbox/drag-sweep changes had not yet been built or runtime-tested. That note predates the current branch. | Inspect exact current source and test Files, Gallery, Collections, Search and Trash; verify normal click, checkbox, right-click preserve-selection, drag edge-scroll and Escape/back clearing. |
| Details/table view status | Older NOTES and backlog contradict each other: one says Details is missing, while later branch history includes a Details layout. | Inspect current ViewMode and UI; test actual columns, sorting, selection and persistence. Do not copy the stale "missing" claim forward. |
| ZIP/7z support | Earlier implementation notes mention basic ZIP/7z support, but the exact supported operations and latest state are not established here. | Test listing, extraction, creation if supported, progress, cancellation, large archives and path safety; record exact limits. |
| Folder upload Cancel All race | Source history contains a generation-token/locking fix that should reject late enqueues from an invalidated batch. A live large-folder regression on the consolidated branch is still needed. | Upload a folder with many files, press Cancel All mid-import, verify queue empties and local files remain, then immediately upload one new file successfully. |
| Download progress described as "real-time" | PR #7/#8 shifted toward native byte progress, but prior user observations were poor and PR #9 changed native download management. | Compare visible byte counts/speed/ETA against actual file-size growth and completion; no fake progress, no stale speed after waiting/pausing. |
| Player brightness control across monitor/driver variants | Code contains DDC/CI paths, but the live result depends on the physical monitor and DDC/CI support. | Test the user's monitor at multiple levels, reconnects and media changes; record if the monitor itself or video image changes. |
| External subtitle file loading | Older notes identify this as a gap. | Inspect current implementation and test local external subtitle selection, encoding, sync and persistence if the feature is expected. |

## 7. Planned, not yet established as implemented

Keep these in the future backlog unless a code audit finds existing work:
- True folder-tree navigation.
- Tags and tag filters.
- Per-folder/per-drive view preferences.
- Gallery EXIF capture date/camera/orientation metadata, optional GPS with privacy controls, and timeline/calendar.
- Advanced Gallery saved filters/smart albums, ratings/labels and exact/perceptual duplicate groups.
- Automatic backup-channel mirroring, message mapping, backup health, retry and restore/repair.
- Multi-account TDLib failover across the user's authorised accounts.
- Bandwidth caps, night scheduling, advanced flood-safe pacing, app-termination/network/reboot recovery hardening.
- Home dashboard with recent files/recently played media and transfer/storage summaries.
- Deeper integrity scrub/recovery and backup tooling.

## 8. Platform and scope decisions

- **Current priority: Windows desktop.** Finish and acceptance-test the desktop app first.
- **Android:** SDK is installed, but the Android compile currently has known errors (`AndroidThumbnailStore.kt` missing `retryAt`; `TdLibTelegramClient.kt` visibility access to `evictManagedDownloadCache`). Defer Android compile/APK work until the user asks to resume Android fixes after desktop completion.
- **Linux:** keep portability in mind, but do not divert the current Windows acceptance work. Validate Linux when the user is ready to switch.
- **macOS:** out of scope. Do not spend time on macOS support or appearance redesign.

## 9. Consolidated local workspace instructions

Create one clean local clone from the baseline branch; do not reuse or clean out any existing test checkout:

```powershell
$destination = "$HOME\TeleDrive-Desktop-Dev"
if (Test-Path -LiteralPath $destination) {
    throw "Destination already exists. Inspect it before doing anything."
}
git clone --branch work/desktop-feature-fixes-20261112 --single-branch https://github.com/AhsanRahman123/TeleDrive.git $destination
if ($LASTEXITCODE -ne 0) { throw "Git clone failed" }
"sdk.dir=C:/Android" | Set-Content -Encoding ascii "$destination\local.properties"
```

Run the app with the repo's isolated data profile:

```powershell
Set-Location "$HOME\TeleDrive-Desktop-Dev"
.\scripts\run-isolated-desktop.ps1
```

The script sets `TELEDRIVE_DATA_DIR` to `.teledrive-instance` inside the new workspace and runs the desktop target. Do not launch ordinary production TeleDrive against the user's real profile for acceptance testing.

Do not delete the old checkouts, test downloads, app data, TDLib sessions, Room schema artifacts, or backups until the new workspace is built, run, and all unique evidence/data has been reviewed. In particular, do not run broad `git clean`, `git reset --hard`, or `git stash -u` commands.

## 10. Test-record format

For each acceptance item, record:
- Date/time and exact Git commit SHA.
- Build/task command and result.
- Test data (file size/type, local path type, Telegram state; do not include private credentials).
- Exact steps performed.
- Expected result versus actual result.
- Screenshot/log location and whether the outcome was user-verified.
- Any regression or limitation.

Only after the exact consolidated commit passes its relevant acceptance tests should the corresponding row be promoted to USER-VERIFIED.


## 11. Consolidated workspace smoke test — 2026-11-12

- **Clone:** User created `C:\Users\ahsan\TeleDrive-Desktop-Dev`; HEAD was `1dbb6ba4d2766e941c708f5c182bd63e81eba86b`, the documentation commit on the feature branch. Git working tree was clean in the user's PowerShell output.
- **Build/test compilation:** User ran `.\gradlew.bat --no-daemon :shared:desktopTest :ui:desktopTest :desktop:test :desktop:compileKotlin`; Gradle reported `BUILD SUCCESSFUL`, 41 actionable tasks executed, in 3m46s. Visible deprecation and safe-call warnings were non-fatal.
- **Launch:** The provided `scripts/run-isolated-desktop.ps1` initially hit the user's PowerShell execution policy. User used a process-scoped execution-policy bypass and launched successfully.
- **Login/onboarding:** User completed API credentials and Telegram onboarding in the isolated development instance and reached the Home dashboard.
- **Visible dashboard:** Screenshot showed Favorites, Recent, and Storage summary (304 files, 14.8 GB). This confirms successful startup/onboarding and that a library overview is displayed. It does not by itself prove that individual file contents are accessible or that every feature works.
- **Profile separation:** The app process uses `TELEDRIVE_DATA_DIR` pointing at `.teledrive-instance` inside the new clone. The local settings, Room DB, TDLib DB/files and keys are stored under that isolated root. The logged-in Telegram account is nevertheless the user's real account; remote cloud operations affect real Telegram data.
- **Next:** Perform read-only navigation and viewer smoke tests first. Do not delete, move, rename, upload, clear cache, trash, or otherwise mutate real cloud/library data until the user explicitly chooses a disposable test item or test folder. Record each test against the exact commit.
