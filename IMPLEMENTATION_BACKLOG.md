# TeleDrive — Current user change list

Last updated: 2026-11-08
Branch: `codex/file-integrity-phase-1`
Target platforms: Windows, Android and Linux. macOS is out of scope.

This document replaces the previous pending-feature list. It reflects the user's refreshed requirements from 8 November 2026. The user reports that the other features they have tested are working correctly; do not reopen or redesign those features without a new report.

## Working rules

- Preserve existing working behavior. Make focused changes and avoid unrelated refactors.
- Preserve original file bytes and quality. Zooming, copying, moving and downloading must not recompress or modify the original.
- Reuse existing preview, repository, folder-picker and transfer infrastructure wherever possible.
- Keep the UI compact. Use native platform interaction patterns where they differ, but keep the requested capability available on each target platform.
- Validate each slice with desktop tests/build, then runtime testing. Keep Android and Linux parity in the plan and verify platform-specific behavior separately.
- Do not make changes to `master`, original app profiles, TDLib sessions or Telegram channel messages.

## 1. Gallery image zoom

Required:
- In the image viewer, double-click/double-tap toggles between fit/original view and a zoomed view. Repeating the gesture returns to the previous fit state.
- Add dedicated on-screen Zoom in and Zoom out buttons. They must work independently of the gesture.
- Zoom must support panning when the image is enlarged and must not reduce the available source resolution.
- On Android, preserve the existing native pinch-to-zoom behavior and the existing Telephoto image pipeline. Pinch, double-tap and buttons must operate on the same zoom state.
- On Windows and Linux, support double-click zoom, the dedicated buttons, and suitable mouse/keyboard zoom interactions. Do not add a heavy image-processing dependency or decode the whole original into memory just to zoom.
- Zoom state must reset appropriately when navigating to another image; it must not unexpectedly carry over to the next photo.

Source audit:
- Android already uses Telephoto `ZoomableAsyncImage`, whose documented gestures include pinch-to-zoom and double-click zoom. The current wrapper remembers its state internally but does not expose dedicated zoom buttons.
- The Android preview currently has a separate pinch handler for text previews; image pinch is already provided by Telephoto.
- Desktop image preview currently renders Coil `AsyncImage` with `ContentScale.Fit`; no image zoom state or controls are attached.

Acceptance:
- Android pinch, double-tap and zoom buttons all work together; zooming remains sharp and panning works.
- Windows and Linux support double-click and buttons, and mouse/keyboard interaction remains usable.
- Zooming one photo does not cause the next photo to open at the same zoom accidentally.
- Large images remain memory-safe and original-quality viewing is preserved.

## 2. File actions while viewing a picture

Required:
- While an image is open in the viewer, provide a Download action where a remote copy exists and a local copy is not already available.
- Add Copy to… and Move to… actions inside the image viewer on Android, Windows and Linux.
- Copy to… creates another library entry in the chosen destination folder; the original stays in its current location.
- Move to… relocates the existing library entry to the chosen destination; it must not create a second copy.
- Both actions must use a folder picker with folder navigation and destination creation, matching the existing Files workflow.
- Preserve original bytes, file identity semantics and metadata. Keep existing remote Telegram-side copy behavior where supported.
- Show a clear success/failure message. Do not silently close the image viewer just because the file is moved.

Source audit:
- Android and desktop preview overflow menus already expose Download for remote-only files.
- Neither viewer currently exposes Copy to… or Move to… for the viewed file.
- `PreviewViewModel` already owns the FileRepository and can perform these actions without duplicating storage logic.
- A reusable `FolderPickerHost` already exists in the shared UI module; make it available to the platform preview screens rather than creating unrelated picker implementations.

Acceptance:
- Copying a viewed image to another folder leaves the original in place and both entries open at full quality.
- Moving a viewed image changes its folder location without changing the underlying file bytes or remote backup.
- Download from the viewer creates the expected local copy and appears in Transfers.
- The same actions are usable on Android, Windows and Linux.

## 3. Right-click empty space opens the Add menu

Required:
- In the Files browser, right-clicking empty space in the content area should open the same Add menu used by the existing + button.
- The menu must offer the same current actions: Add files, Add folder and New note.
- This applies in both Grid and List/Details layouts, including an otherwise empty folder view.
- Right-clicking an actual file or folder must continue to open that item's context actions; it must not also open Add.
- Keep right-click actions desktop-only. Preserve Android touch/long-press behavior.
- Keep the existing + button; this is an additional way to reach the same menu, not a replacement.

Source audit:
- The + menu is implemented in `FilesScreen` as a `FloatingActionButtonMenu`.
- File/folder rows already consume desktop secondary-click through `onDesktopContextClick`.
- There is no parent-level handler for an unconsumed secondary click on the blank Files content area.

Acceptance:
- Right-clicking unused space in a populated Grid or List opens the Add menu.
- Right-clicking a file or folder opens only that item's context menu.
- Right-clicking an empty folder opens Add.
- The menu actions behave exactly like the existing + menu.

## 4. Transfer cancellation during large folder uploads

Observed issue:
- A folder containing hundreds of photos was uploaded.
- After a few uploads, the user pressed Cancel all.
- The transfer queue appeared to retain/show the last cancelled item and a subsequent file upload behaved incorrectly.
- Pressing Cancel all again cleared the stale state; subsequent uploads worked normally.
- The issue has not recurred since. The user reports that other tested features work perfectly.

Source audit / likely race:
- `FilesViewModel.runImport` imports a folder incrementally and calls `transferRepository.enqueueUpload` once per newly imported file.
- `TransferRepositoryImpl.cancelAll` changes rows that already exist to CANCELLED, but it does not currently notify or invalidate the still-running folder-import loop.
- Therefore, the folder-import loop can enqueue later files after the Cancel all database update has completed. This is a plausible explanation for the one-off stale/reappearing transfer behavior.
- The existing worker checks transfer row state cooperatively, but that alone does not prevent the importer from adding new queue rows after the cancellation pass.

Required:
- Make Cancel all invalidate pending upload enqueues that belong to an in-progress folder/file import batch.
- An enqueue that races with Cancel all must be ordered safely: either it is inserted before Cancel all and cancelled by it, or it is rejected as part of the cancelled batch. No old batch item may appear in the active queue after Cancel all completes.
- Keep newly initiated uploads after cancellation working normally.
- Keep already imported local files intact; cancelling transfers must not delete local source files or their library metadata.
- Do not clear or corrupt unrelated completed transfers or existing remote copies.
- Add a regression test for cancelling during a large import/enqueue batch, then starting a new single-file upload.

Acceptance:
- Cancel all during a large folder upload leaves no remaining items from that cancelled batch in the active queue after the action completes.
- A new file uploaded immediately after Cancel all appears once, progresses normally and completes.
- The same cancellation sequence does not require pressing Cancel all twice.
- The issue remains under observation during real-world testing because it has so far occurred only once.

## Implementation order

1. Add and validate image zoom controls and gestures on Android, Windows and Linux.
2. Add Copy to… and Move to… to the image viewer, reusing the existing folder picker and repository operations.
3. Add the empty-space right-click Add menu to the Files browser without changing item context menus.
4. Fix and regression-test the folder-import / Cancel all race.
5. Re-run the existing desktop test/build command and validate Android/Linux-specific builds and runtime behavior.

## User-verified baseline

The user reports that the other previously tested TeleDrive features are working perfectly. Treat them as accepted baseline behavior for this iteration. Do not expand scope back to the older wishlist unless the user raises a new issue.


## Latest implementation checkpoint — 8 November 2026

Source changes now present on `codex/file-integrity-phase-1`:

- Android image preview wraps the existing Telephoto zoom state with dedicated Zoom in / Zoom out controls. The existing Telephoto pinch gesture pipeline is retained.
- Desktop image preview uses Telephoto's Coil 3 `ZoomableAsyncImage` with tiled/sub-sampled rendering, dedicated zoom controls, double-click zoom and mouse/keyboard zoom interactions.
- Both Android and desktop zoom buttons launch Telephoto's animated `zoomBy` operations from a remembered coroutine scope; they control the same zoom state as the gestures.
- Android and desktop preview menus now offer Copy to… and Move to… and reuse the shared folder picker. Both actions route through the existing `FileRepository` operations.
- The Files browser now handles right-click on unconsumed blank space by opening the same Add menu as the + button. Item context clicks are consumed by their own row handlers, so they should not trigger the blank-space action.
- Folder imports capture an upload-queue generation. `Cancel all` advances that generation while holding the same enqueue mutex used to insert upload rows. Enqueues from an older import generation are rejected after cancellation, while imported local files remain in the library.
- Added a focused `UploadBatchGenerationTest` covering generation snapshots and invalidation after Cancel all.
- When a folder import's upload batch is invalidated, the final message explicitly says the files were imported locally and their uploads were cancelled.

Validation state:
- These newest source changes have **not yet been compiled or runtime-tested** on the user's machine.
- The generation unit test is added but has not yet been run.
- A real-world regression test remains required: upload a large folder, Cancel all after a few transfers appear, then immediately upload a new single file and confirm that no old batch item reappears and the new file completes.
- Also validate viewer pinch/double-click/buttons, viewer Copy/Move, and blank-space right-click in both populated and empty Files folders.
- Windows is the primary runtime verification target; Android and Linux builds/gestures must be checked separately before claiming cross-platform completion.



## Runtime feedback refinements — 8 November 2026

Newly requested changes and implementation checkpoint:

- Double-click/double-tap image zoom now toggles between fit and a controlled 2x zoom around the tap point. The maximum allowed manual/pinch zoom has been reduced from 6x to 4x; the dedicated Zoom in / Zoom out buttons remain unchanged.
- Moving the currently viewed image/video now removes it from the active preview sequence. The next item takes its place immediately; moving the last item opens the new last item, and moving the only item closes the viewer.
- Right-click on empty Files space still opens Add. A subsequent left-click on any unconsumed empty Files space now closes the menu. Item context menus continue to consume their own right-click.
- Desktop video playback toggles pause/resume when the video surface is clicked.
- Desktop video/audio transport now groups Previous, 10-second rewind, Play/Pause, 10-second forward and Next together at the bottom centre. The old side pager buttons remain for static image/other non-player previews.
- The old preview-level Left/Right key handler was removed because it captured keys before the player could handle them. The focused desktop player now handles VLC-style shortcuts directly.

Desktop VLC-style shortcut map added:
- Space: play/pause; F / Esc: fullscreen / exit fullscreen; S: stop.
- N / P: next / previous preview item.
- + / - / =: faster / slower / normal speed.
- Ctrl+Up / Ctrl+Down: volume; M: mute.
- Shift+Left / Shift+Right: 3-second seek; Alt+arrows: 10-second seek; Ctrl+arrows: 1-minute seek; Ctrl+Alt+arrows: 5-minute seek.
- E: next frame; L: repeat toggle; B / V: next audio/subtitle track.
- J / K: audio delay -/+ 50 ms; G / H: subtitle delay -/+ 50 ms; O: cycle video scaling.
- Hardware media play/pause, stop, next and previous keys are also handled.

Validation still required:
- The above refinements are committed but have not yet been compiled or runtime-tested by the user.
- Re-run the established Windows command suite, then verify zoom is no longer excessive, Move advances immediately for images and videos, the Add menu closes on blank-space left-click, click-to-pause works, the central transport cluster is compact, and the VLC-style shortcuts respond without stealing text-field or menu input.
- Re-test Android pinch/double-tap/buttons and Android move-to-next behavior after the pager updates.
