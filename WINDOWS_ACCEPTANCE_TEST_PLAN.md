# TeleDrive — Windows Consolidated Acceptance Test Plan

Last updated: 2026-11-13
Test target: branch `work/desktop-feature-fixes-20261112`
Expected starting point: latest head of `work/desktop-feature-fixes-20261112`; fetch the branch and record `git rev-parse HEAD` before testing.
Companion status register: [DEVELOPMENT_STATUS_REGISTER.md](DEVELOPMENT_STATUS_REGISTER.md)

This is the final Windows-first acceptance plan for the consolidated development workspace. It is intentionally evidence-driven: check an item only after performing it on the exact workspace/commit being recorded. A compile or automated test is not a substitute for the user-facing test.

## 0. Test environment and safety gate

For the isolated Windows launch, use PowerShell from the clone root:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\run-isolated-desktop.ps1
```

The execution-policy bypass is process-scoped; it applies only to this PowerShell session and does not permanently change Windows policy.


- [ ] New clone exists at `C:\Users\ahsan\TeleDrive-Desktop-Dev`.
- [ ] `git status --short` reviewed; no unknown user files are being overwritten.
- [ ] `git rev-parse HEAD` recorded in the test log.
- [ ] Android SDK setting is local to this clone through `local.properties`; it is not committed.
- [ ] App is started using `scripts\run-isolated-desktop.ps1`.
- [ ] Confirm isolated test data is written under `.teledrive-instance`.
- [ ] Confirm the installed app under `%LOCALAPPDATA%\TeleDrive` and the user's production TeleDrive profiles are untouched.
- [ ] Use test files and test folders. Never use the user's only copy of valuable data.
- [ ] Keep test downloads, logs, database snapshots and Room schema artifacts until each test is recorded.

Record the exact test commit and isolated data directory before testing.

## 1. Build and automated test gate

Run one focused stage at a time. Do not repeatedly rerun unrelated tasks after a clearly unrelated failure.

- [ ] `.\gradlew.bat --no-daemon :shared:desktopTest`
- [ ] `.\gradlew.bat --no-daemon :ui:desktopTest`
- [ ] `.\gradlew.bat --no-daemon :desktop:test` (includes `ThumbnailRefreshQueueRuleTest`)
- [ ] `.\gradlew.bat --no-daemon :desktop:compileKotlin`
- [ ] Build output reviewed; record failures by exact task and first meaningful compiler/test error.
- [ ] Launch isolated desktop app and confirm startup, navigation and clean shutdown.

If a task does not exist in this branch, record that rather than inventing an alternative. If a task fails, diagnose the first relevant error before rerunning.

## 2. Player — highest priority

### 2.1 Physical monitor brightness (DDC/CI)

- [ ] Open a video and confirm the physical monitor brightness slider appears promptly.
- [ ] Move brightness to a low, middle and high level; observe the physical display backlight/brightness changing.
- [ ] Confirm the image/video itself is not being dimmed by an in-app ColorFilter or ColorMatrix overlay.
- [ ] Drag the slider continuously and confirm there is no fighting/jumping from stale polling.
- [ ] Release the slider and confirm the final level persists.
- [ ] Switch between multiple videos and confirm the slider remains available and physical brightness state does not reset unexpectedly.
- [ ] Test transient DDC read failure / monitor response delay if reproducible; slider should not disappear immediately.
- [ ] Test app restart and persisted visibility/auto-hide preference.
- [ ] Record whether the monitor and connection support DDC/CI; do not label unsupported hardware behavior as a code regression without evidence.

### 2.2 Volume and audio attach

- [ ] Open a video with audio; sound is audible immediately without first moving the volume slider.
- [ ] Change volume before or immediately after playback begins; the latest value is applied when VLC audio output attaches.
- [ ] Mute/unmute repeatedly; audio state and icon stay in sync.
- [ ] Seek, pause/resume, switch video and return; audio remains audible.
- [ ] Test Gallery video launch as well as direct player launch; specifically watch for the prior "silent until volume/mute is touched" bug.
- [ ] Confirm volume keyboard controls work and do not accidentally trigger unrelated UI actions.

### 2.3 Playback, seeking and navigation

- [ ] Click/tap the video surface to pause and resume.
- [ ] Test the previous/next buttons and confirm they act on the intended queue item.
- [ ] Test left/right arrow seeking in both directions; confirm the requested fast-seek interval is applied consistently.
- [ ] Test P/N seek shortcuts: P moves backward 5 seconds and N moves forward 5 seconds; left/right arrows remain 10 seconds. Confirm they do not trigger file navigation.
- [ ] Test W/E previous/next-file shortcuts.
- [ ] Test playback speed options, including 0.5x, 1x and the intended faster values; slower and faster controls both work.
- [ ] Test volume up/down shortcuts and mute.
- [ ] Test fullscreen enter/exit, including switching between files while fullscreen.
- [ ] Test queue order, shuffle, repeat and sleep timer.
- [ ] Test audio-track and subtitle-track selection, audio/subtitle delay and external subtitle loading if available.
- [ ] Test player teardown when switching between gallery/player screens; no two stale VLC instances/audio outputs should remain active.

### 2.4 Player UI behavior
### 2.5 PR #10 regressions to verify

- [ ] Open the playback queue/playlist menu; it must not throw an infinite-height Compose layout exception or close TeleDrive.
- [ ] With controls hidden and video fullscreen, switch repeatedly between local videos and streamed videos. Confirm no full-screen black flash, no stale frame/source, and no second VLC instance/audio stream.
- [ ] Press H once; controls hide. Press H a second time within 450 ms; controls show. After the configured timeout they should auto-hide again.
- [ ] Use the dedicated hide-controls button and confirm the overlay show button remains available.
- [ ] Press F11 from Files, Gallery and other app pages; confirm the whole TeleDrive window enters fullscreen. Press F11 again; restore the exact previous floating/maximized placement.
- [ ] Open a video fullscreen and press the app Back button; confirm the app remains fullscreen on the previous page.
- [ ] Exit fullscreen with the video fullscreen control/Escape; confirm the previous window placement is restored rather than forcing a different half-size/floating layout.

- [ ] Volume and brightness sliders appear/auto-hide according to the saved preference.
- [ ] Auto-hide delay is correct and controls remain usable while pointer/drag interaction is active.
- [ ] No stuck controls, frozen playback, black frame, or lost audio after repeated file switching.
- [ ] Record any difference between direct player launch and Gallery launch.

## 3. Transfers and download queue — highest priority

Use at least three test files with visibly different sizes (for example, small, medium and large), and record each file's actual size.

### 3.1 Worker cap and true progress

- [ ] Set worker cap to 1; confirm only one eligible transfer actively downloads.
- [ ] Set worker cap to 2; confirm at most two active transfers.
- [ ] Set worker cap to 3; confirm at most three active transfers where Telegram/network constraints allow it.
- [ ] Confirm no item stays marked Running forever without byte growth or a meaningful waiting reason.
- [ ] Compare displayed transferred bytes and speed with the actual growing destination file.
- [ ] If Telegram emits an account-level speed-limit notification, confirm Transfers identifies it as a Telegram-side restriction; do not interpret this as a TeleDrive bandwidth cap or claim it can be overridden locally.
- [ ] Confirm progress does not make large fake jumps or reach 100% before the file is complete and finalized.
- [ ] When Telegram cannot start another transfer (including same data-centre/provider limits), confirm the UI shows a truthful waiting state rather than pretending the item is actively transferring.
- [ ] Confirm speed returns to zero/idle when paused, completed or waiting.

### 3.2 Pause and resume

- [ ] Pause one active transfer; confirm actual network/file progress stops.
- [ ] Resume it; confirm the same transfer continues and the file validates.
- [ ] Pause All; confirm active native requests are actually paused/cancelled and queue rows settle into a paused state.
- [ ] Resume All; confirm eligible rows re-enter the queue without duplicates.
- [ ] Pause/resume repeatedly; no phantom Running rows, duplicate destination files or stale progress.
- [ ] Test resume after closing and reopening the isolated app.

### 3.3 Cancel and Cancel All

- [ ] Cancel one transfer; confirm it stops and the row reaches the expected terminal state.
- [ ] Cancel All with multiple active and queued downloads; confirm no old item resumes unexpectedly.
- [ ] Immediately enqueue a fresh single-file download; it must work normally.
- [ ] Confirm cancellation does not delete a user's original local source file or unrelated completed files.

### 3.4 Real download and recovery

- [ ] Download a small file, a 100–200 MB file and a larger file where practical.
- [ ] Confirm destination, size and content/hash are correct.
- [ ] Interrupt network briefly and confirm truthful waiting/retry behavior.
- [ ] Close/reopen the isolated app during a transfer; confirm attach/re-attach and recovery behavior.
- [ ] Confirm successful finalization removes stale managed native download state without touching unrelated/external files.
- [ ] Verify completed-file open action and Transfer History details.

## 4. Uploads — especially folder import cancellation

- [ ] Upload one file; verify the Telegram/cloud copy and local source remain correct.
- [ ] Upload a folder containing many files.
- [ ] While the folder is still being imported/enqueued, press Cancel All once.
- [ ] Confirm no late item from the cancelled batch appears in the active queue after cancellation completes.
- [ ] Confirm already-imported local files and their library metadata remain intact.
- [ ] Immediately upload a new single file; confirm it works without pressing Cancel All a second time.
- [ ] Confirm completed uploads and existing remote copies are not corrupted or cleared.
- [ ] Repeat once with slower network conditions if the race does not reproduce on the first run.

## 5. Storage, local copies and Free up space

Use disposable test files and distinguish three locations: app-managed Room/download copy, TDLib cache path, and Telegram cloud copy.

- [ ] File with app-managed copy + external TDLib cache path: Free up space removes only the app-managed copy, clears the Room local path, and leaves the external TDLib file untouched.
- [ ] File with app-managed copy + no TDLib local path + cloud backup: Free up space removes the app-managed copy, clears its Room path, and preserves the Telegram cloud backup.
- [ ] File with both app-managed and app-managed TDLib paths: both eligible managed copies are removed.
- [ ] File outside managed directories: original is preserved.
- [ ] Available Offline copy: is preserved according to the protection rules.
- [ ] File without confirmed remote verification: cannot be destructively cleaned up.
- [ ] Storage locations show an accurate count/size after removal.
- [ ] Verify the file can still be opened/downloaded from Telegram after cleanup.
- [ ] In Free up space, open the Local copies tab. Visible rows should automatically inspect TDLib-reported cache paths (without a manual per-file click), show app/database and TDLib paths/sizes or an explicit no-cache state, and keep external/original, TDLib-only, missing, and Available Offline cases distinguishable.
- [ ] Scroll a large backed-up library quickly; TDLib inspection must stay bounded and not flood the client with concurrent requests. Manual refresh must remain available.
- [ ] Use Reveal for existing paths; confirm external/original paths are labelled protected and cannot be treated as TeleDrive-owned.
- [ ] Remove a disposable managed copy only; verify the cloud copy remains and the external/original/Available Offline file is untouched.
- [ ] Confirm confirmation text accurately explains what will and will not be deleted.

## 6. Files browser and file management

- [ ] Navigate nested folders with breadcrumbs and back navigation.
- [ ] Search by file/folder name and clear the search.
- [ ] Test sorting by name, size, modified date, added date, type and backup state where available.
- [ ] Test Grid and List layouts; verify Details/table view only if it actually exists in the current build.
- [ ] Test compact density without broken click targets or clipped actions.
- [ ] Test right-clicking an item; only that item's context menu should open.
- [ ] Test right-clicking already-selected and unselected items; existing multi-selection should be preserved as intended.
- [ ] Test blank-space right-click in populated Grid and List layouts; it should open the same Add menu as the + button.
- [ ] Test blank-space right-click in an empty folder; it should open Add.
- [ ] Confirm right-clicking an item never opens the blank-space Add menu as well.
- [ ] Test Add files, Add folder and New note from the + menu and blank-space menu.
- [ ] Test favorites, Offline, Archive, Hide, Trash and Restore.
- [ ] Verify normal Trash operations do not delete Telegram cloud messages or original files.
- [ ] Test copy/move/rename and folder destination creation; verify bytes, names and metadata.
- [ ] Test ZIP/7z operations only to the extent the current build exposes them; record supported formats and limits.
- [ ] Verify keyboard Escape/back clears selection and does not close an unrelated screen.
- [ ] Hover toolbar and player icons across Home, Files, Gallery, Preview, Transfers, Trash, Channels, Settings, Cache Manager and Thumbnail Manager/Queue; each actionable icon should show a label matching its action.

### 6.1 PR #10 multi-tab and keyboard work

- [ ] Open two or more Files tabs; open different folders in each and switch back/forth. Each tab must retain its folder and scroll position.
- [ ] Close a background tab and then the selected tab; an adjacent tab should activate without corrupting folder state.
- [ ] Use Close all Files tabs; confirm exactly one fresh root tab remains. Reopen folders and verify the selected tab/folder workspace restores after navigating to a preview and returning.
- [ ] Drag a selected file/folder from Grid, List and Details onto another tab. Confirm the drag action (Move or Copy) is honored and the destination is that tab's current folder.
- [ ] Confirm dropping onto the active tab is rejected and dropping onto an invalid/closed tab does not mutate anything.
- [ ] Ctrl+T opens a new Files workspace tab from the file list, tab strip and toolbar; existing tabs and their folder/scroll state remain intact.
- [ ] Ctrl+N opens the New Folder dialog in Files, including when focus is on a selected row or the Files toolbar. In the Gallery Albums root it opens New Album.
- [ ] Ctrl+A selects all files and folders in the active Files folder, including file results on unloaded pages; the toolbar count equals the actual selected rows.
- [ ] Ctrl+C copies selected files/folders; repeated Ctrl+V pastes additional copies. Ctrl+X cuts and Ctrl+V moves once. Confirm files and folders preserve metadata and local copies as appropriate.
- [ ] Copy/cut in Files, Gallery media/albums, Search results and Favorites/Offline/Archived/Hidden collections; paste into an actual Files folder or Gallery album. Confirm the app clipboard is shared across sections and a successful cut is consumed only after every requested move succeeds.
- [ ] In Favorites/Offline/Archived/Hidden, Ctrl+A selects matching files/folders and Ctrl+C/X copies/cuts selected items. These are filtered collections, not destination folders; verify Ctrl+V targets the Drive root.
- [ ] In Search, Ctrl+A selects matching file results and Ctrl+C/X copies/cuts them. Ctrl+V is not assigned because Search has no destination folder.
- [ ] In Thumbnail Manager and Thumbnail Queue, Ctrl+A selects all filtered inventory/queue items across pages. In Cache Manager and Backed-up local-copy inventory, Ctrl+A selects all matching rows.
- [ ] In Trash, Ctrl+A selects all selectable trash rows. In Transfers, Ctrl+A selects all jobs visible under the current search/filter.
- [ ] Test Ctrl+A/C/X/V inside Search, Cache Manager and Transfers text fields; text-field selection/clipboard must remain native and must not trigger collection actions.
- [ ] Reproduce the screen-recorded Files bug: select many folders, click Clear selection, and confirm the toolbar returns to Files and every row's selected background/checkmark clears immediately. Then select exactly five folders and confirm exactly five rows remain highlighted and the toolbar says 5 selected. Navigate away/back and repeat.
- [ ] In Files Grid/List/Details, selecting a file or folder shows a clearly visible filled primary badge with a contrasting checkmark; deselecting restores the neutral outline. Confirm this stronger selection treatment is Files-only and Gallery's visuals remain unchanged.
- [ ] Trigger Ctrl+C/X/V after selecting with the mouse or checkbox, including when the destination folder is empty. Copy should leave the source and allow repeated paste; cut should move once and clear only after success.
- [ ] Force a cut/move destination failure and confirm the cut clipboard remains available for retry. Start a newer cut while an earlier move is finishing and confirm the earlier completion does not clear the newer clipboard.
- [ ] Ctrl+N opens New Folder; Delete sends selected items to Trash; F2 renames; Ctrl+A selects all; Backspace navigates to the parent folder; Escape clears selection.
- [ ] Test keyboard shortcuts when the list has focus and when an editable text field has focus; shortcuts must not steal typing.
## 7. Gallery, thumbnails and cache

### 7.1 Gallery

- [ ] Media search returns expected images/videos and is debounced.
- [ ] Favorites, Offline, Backed up and Not backed up filters return the correct items.
- [ ] Next/previous in Preview stays within the current filtered result set.
- [ ] Grid/List toggle works for media and albums.
- [ ] Use visible zoom in/out controls and verify the expanded 2–12 column range, including the denser 9–12 column options. On wide windows, Gallery adds two columns but must still stop at 12.
- [ ] Confirm list and compact grid rows show the containing folder path, including nested albums.
- [ ] Open a large remote-only image and a downloaded local image. Confirm viewer dimensions/zoom use original file bytes, not the grid thumbnail. For remote-only images above the auto-preview limit, explicitly download from the viewer and confirm it transitions to the full local original after transfer completion.
- [ ] Confirm grid thumbnail cache stays separate and bounded: larger previews improve sharpness without rewriting or compressing source files.
- [ ] Begin with a remote-only item that has a Telegram thumbnail, then download its original image/video. Confirm an existing thumbnail is replaced from the downloaded original only when the effective thumbnail policy is Automatic; manual-only files/folders remain untouched.
- [ ] Confirm thumbnail refresh reuses the existing cache entry (no duplicate thumbnail copies) and the original file size/hash remains unchanged.
- [ ] Album sort by name, item count and latest activity works.
- [ ] Empty folder albums appear as intended; document-only folders are not incorrectly treated as photo albums.
- [ ] Create an album and confirm it is a regular folder visible from Files.
- [ ] Test bulk Copy, Share (for local files), Details, Move, Download, Upload, Favorite, Offline, Hide, Archive and Trash.
- [ ] Test Free up space from Gallery separately from Trash.
- [ ] Download snackbar opens Transfer History.
- [ ] Test images, videos, mixed folders, empty folders and a large media folder.

### 7.2 Thumbnail manager and generation queue

- [ ] Inventory distinguishes All, Generated and Not generated.
- [ ] Search and sort by name, source size, thumbnail/cache size and generation/last-viewed fields where shown.
- [ ] Folder aggregate size is consistent with its children.
- [ ] Select one, select all filtered results, and drag-select if supported.
- [ ] Generate selected thumbnails and verify generated state changes only after successful generation.
- [ ] Pause/resume individual and all jobs.
- [ ] Cancel selected and Cancel All; confirm no orphan jobs remain.
- [ ] Retry a failed item.
- [ ] Navigate away/back while jobs run; queue state and selection should remain coherent.
- [ ] Confirm concurrency remains bounded and UI stays responsive on a large folder.

### 7.3 Cache manager

- [ ] Cache tabs load and report sizes by cache category and folder.
- [ ] Search/filter/sort identify the intended cache entries.
- [ ] Selective cleanup removes only selected eligible cache data.
- [ ] Clearing thumbnails does not delete originals or cloud files.
- [ ] Cache usage refreshes after deletion.
- [ ] Confirm no hidden "free local originals" or unsafe cache action appears.

## 8. Viewer — images and videos

### 8.1 Images

- [ ] Large image opens quickly without loading the full original into memory unnecessarily.
- [ ] Double-click/double-tap zoom behavior is predictable and toggles back to the prior fit state.
- [ ] Zoom buttons work independently of gesture zoom.
- [ ] Pan works while zoomed; image quality remains original.
- [ ] Zoom resets appropriately when navigating to another image.
- [ ] Rotate left/right through 360 degrees, toggle horizontal and vertical mirrors independently, then combine them. Confirm the original source file bytes and metadata remain unchanged.
- [ ] Android-only pinch behavior is deferred to the later Android pass; do not mark Android parity verified from Windows tests.

### 8.2 Copy/move/download from viewer

- [ ] Download is available when remote-only and not already locally available.
- [ ] Copy to… creates a second library entry in the chosen folder; original remains.
- [ ] Move to… relocates the library entry; it does not leave a duplicate in the old folder.
- [ ] Viewer updates the current sequence correctly after move; it must not keep displaying a stale deleted/moved item.
- [ ] Remote Telegram backup and original file bytes remain intact.
- [ ] Destination folder navigation and creation work.
- [ ] Success/failure feedback is clear.

### 8.3 Video viewer integration

- [ ] Open video from Gallery; sound begins immediately.
- [ ] Player controls and keyboard shortcuts match direct player behavior.
- [ ] Move/Trash current video updates the open sequence as expected.
- [ ] Switching items does not leave old audio or a second VLC instance running.

## 9. Navigation, dashboard and general regression

- [ ] Home, Files, Favorites, Offline, Archived, Hidden, Transfers, Trash, Gallery and Settings open correctly.
- [ ] Navigation/back behavior remains stable after opening nested screens and previews.
- [ ] Header title/back button remain available while scrolling where required.
- [ ] Empty states, error states and loading indicators are accurate.
- [ ] UI remains responsive while transfers and thumbnail generation run.
- [ ] Observe memory/CPU on the user's Windows machine; note approximate idle, browsing and heavy-work figures rather than assuming performance.
- [ ] Restart the isolated app and confirm preferences, queue state and thumbnail cache recover correctly.

## 10. Sign-off rule

A feature is not signed off because its checklist is ticked from memory. Record the exact commit SHA, command, steps, expected/actual result and evidence for each tested section.

At the end, publish a short test report with:
1. Passed items.
2. Failed items and reproducible steps.
3. Items still untested or hardware-dependent.
4. Any new suspected feature/status discrepancies discovered.
5. The exact commit recommended for the next implementation pass.

Do not merge any draft PR into the upstream/default branch as part of local acceptance testing. Keep the working feature branch isolated until the user reviews and approves the result.
