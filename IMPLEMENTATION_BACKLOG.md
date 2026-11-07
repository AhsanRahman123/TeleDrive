# TeleDrive — Practical Library Build Backlog

Last updated: 2026-11-08  
Branch: `codex/file-integrity-phase-1`  
Target platforms: Windows, Android and Linux. macOS is out of scope.

This is the authoritative checklist for the practical feature set to complete before the user starts building the full library. It records the user's newest player, folder, context-menu and transfer feedback alongside the previously agreed 13 required capabilities.

## Ground rules

- Keep the interface minimal. Rich capability is fine; clutter and features without a clear everyday use are not.
- Preserve original file bytes. Never compress, downsample, resize, convert, or recompress uploaded/viewed originals for convenience.
- Thumbnails are separate preview derivatives only. Original files must remain available for original-resolution viewing, download, copy, sharing and re-upload.
- Preserve folder hierarchy when importing, moving, copying and downloading directories.
- Keep all large library screens paged/virtualised. Avoid loading entire libraries or large files into memory.
- Never auto-delete duplicates. Duplicate detection presents candidates for user review.
- Use recoverable Trash for ordinary deletion. Permanent Telegram message deletion must not be a normal file-management action.
- Reuse the existing repository, TDLib transfer queue, preview pipeline and platform abstractions wherever possible.
- Keep `master`, original app profiles, TDLib sessions and Telegram channel messages untouched during development.
- Track each capability separately as: implemented in source, compiled, automated tests passed, Windows runtime tested, Android runtime tested, Linux runtime tested, and user accepted. A button on screen is not proof of working behavior.

## A. New feedback from current testing

### A1. In-player volume control

Required:
- Add a visible volume slider in the player so the user can raise/lower playback volume continuously, not only mute/unmute.
- Make it work in both Android and desktop video/audio playback.
- Keep mute/unmute and slider state in sync.
- On Android, keep it consistent with the existing swipe gesture behavior without surprising changes to the device's unrelated audio streams.

Current source audit:
- Android already has vertical swipe adjustment: right half changes system music-stream volume and left half changes the player window's brightness override.
- Android player controls currently expose mute/unmute but not a dedicated volume slider.
- Desktop player controls currently expose mute/unmute but not a dedicated volume slider.

### A2. Brightness control

Required:
- Add an in-player brightness slider for video viewing.
- Android must control brightness for the player session/window (not silently change the user's permanent system brightness setting).
- Desktop should expose brightness adjustment on its supported video-rendering path if the backend allows it; validate on Windows and Linux separately.
- Restore the previous/window brightness state correctly when leaving playback or switching screens.
- Keep the existing Android left-side vertical brightness gesture and synchronize its HUD/slider state where feasible.

Current source audit:
- Android has a window-scoped brightness gesture already, but no visible slider.
- Desktop currently has no equivalent brightness adjustment control in its player UI. Feasibility depends on its VLC video output path and needs platform validation.

### A3. Load an external subtitle file manually

Required:
- Add an action from the player to choose a subtitle file from local storage.
- Attach that selected subtitle to the currently playing video without modifying the video file.
- Provide track selection, enable/disable and a way to remove the manually added subtitle.
- Support common formats that the underlying platform player can reliably render (prioritise SRT and VTT; include ASS/SSA only where supported).
- Keep embedded subtitle tracks, audio tracks and language preferences working.
- Test both locally downloaded videos and Telegram-streamed videos on Android, Windows and Linux. Clearly explain when the selected player/backend cannot attach an external track to a particular stream.

Current source audit:
- Embedded subtitle-track selection already exists in Android Media3 and the desktop VLC player.
- Selecting a local external subtitle file and attaching it to the active video is not implemented.

### A4. Desktop right-click menu at the pointer

Required:
- On Windows/Linux desktop, right-click should open the selected item's context menu at or near the pointer, like Windows Explorer.
- Right-click an unselected item selects it and opens the menu.
- Right-click an already-selected item preserves the existing multi-selection.
- Keep keyboard-accessible context actions and current touch/long-press behavior on Android.
- Avoid opening the menu at a distant toolbar location. Ensure menus remain within screen bounds.

### A5. Complete folder copy and move actions

Required:
- Make Move to… clearly available for selected folders as well as files.
- Add Copy to… for folders.
- Folder copy must recursively copy the folder structure and its files, including nested folders and empty folders.
- Preserve file names, MIME types, metadata, local/remote availability and integrity information where applicable.
- For backed-up Telegram files, prefer safe server-side Telegram document copies rather than downloading/re-uploading where existing mechanisms permit.
- Resolve destination name conflicts with a clear Keep both / Merge / Cancel flow; never overwrite a source or silently replace destination content.
- Prevent moving a folder into itself or one of its descendants.
- Make the actions available from the same clear context/selection menu.
- Verify folder counts, nested structure, file hashes and remote mappings after copy/move.

Current source audit:
- `FileRepository.moveFolder` and the Files ViewModel's selected-folder move path exist.
- The current selection UI exposes Copy to… only when the selection contains files and no folders.
- `FileRepository` exposes `copyFiles`, but no recursive `copyFolder` operation.
- The present gap is therefore both a missing folder-copy implementation and an incomplete/discoverable folder-action UI, not a need to replace the entire file repository.

### A6. More capable Transfers workspace

Existing functionality to preserve:
- Per-transfer pause, resume, retry and cancel.
- Global pause, resume and cancel.
- Clear finished transfers.
- Progress, transfer speed, ETA, stage and failure message display.
- Bounded active/paused/failed/completed sections.
- Priority already exists in the transfer task model, but user-facing priority controls are not currently present.

Practical enhancements requested:
- Change priority / reorder queued transfers (including move to top and lower priority).
- Search transfers by filename.
- Filter by uploads, downloads, backups and state (queued, active, paused, failed, completed).
- Bulk select and pause/resume/cancel/retry applicable transfers.
- Choose download destination and optional output filename per download, while keeping the configured default directory.
- Show clearer per-transfer error detail and an obvious retry action.
- Preserve accurate progress, speed and ETA; do not display misleading progress for work that is waiting, joining, sealing or verifying.
- Keep the queue responsive with thousands of items; paginate or bound completed history.
- Respect existing flood-safe transfer pacing and concurrency controls. Avoid adding continuous background scheduling unless later justified.

### A7. Full Gallery and file-viewing experience inside Archived and Hidden

Required:
- Treat **Archived** and **Hidden** as complete library browsing scopes, not simple lists or storage bins.
- Provide the same practical Gallery and file-viewing capabilities available in the normal library, using the same shared components and behavior wherever possible.
- Inside each scope, automatically categorize eligible items into Photos, Videos, Audio, Documents, Archives, Apps/APKs and Other using reliable MIME type/extension metadata.
- Keep categories scoped: Archived categories show only archived content; Hidden categories show only hidden content. Do not leak these items into the ordinary Gallery or ordinary category counts.
- Support Grid, List and Details layouts, sorting, type/date/size filters, search within the current scope, multi-selection, breadcrumbs and navigation through nested folders.
- Show category and folder counts that reflect the current Archived/Hidden scope.
- Photos must open in the same original-quality viewer as ordinary Gallery images, with full-resolution zoom/pan and thumbnail-only derivatives.
- Videos and audio must use the same players and controls as the main library, including volume/brightness controls, track selection and external subtitles where supported.
- Documents and ZIP/7z archives must use the same supported preview/viewing flows as the main library. Unsupported formats should show a clear fallback rather than a broken viewer.
- Preserve the usual file operations where meaningful: copy, move, download, share, rename, restore/unarchive/unhide, and recoverable delete. Folder operations must work recursively where applicable.
- Clearly expose Restore/Unarchive and Unhide actions. Browsing, previewing, searching, copying or moving an item must not silently clear its archived/hidden status; only an explicit state-changing action should do so.
- Keep Archived and Hidden separate from one another, and make the current scope visible so users know where they are working.
- Reuse the normal thumbnail cache, paging/virtualisation, preview pipeline and low-memory behavior. Do not load all archived/hidden media at once.
- This must work consistently on Windows, Android and Linux, with platform-appropriate file pickers and context actions.

Acceptance checks:
- An archived photo appears under Archived > Photos, opens at original resolution, and remains archived after viewing.
- A hidden video appears under Hidden > Videos, streams/plays with the same controls as a normal video, and remains hidden after playback.
- Search, filters, sorting, Grid/List/Details and folder navigation operate only within the selected Archived or Hidden scope by default.
- Restoring/unarchiving or unhiding explicitly removes the item from that special scope and makes it visible in the ordinary library again.
- Copy/move/download/share/delete actions do not silently change archive/hidden state or overwrite another item.
- Large Archived/Hidden collections remain paged and responsive.

## B. The complete required 13-capability scope

These are all required before the user begins building the full library. Some parts already exist; the checklist means complete and verify the overall capability, not rebuild everything from scratch.

### B1. Complete file and folder management
- Upload individual files and whole folders while preserving relative folder structure.
- Create, rename, copy, move, share, download, hide, archive, favorite, mark offline and move to recoverable Trash.
- Support recursive folder copy/move, empty folders and conflict-safe operations.
- Grid, List and Details views, sorting, multi-selection, keyboard navigation, right-click context menus at the pointer, breadcrumbs and folder-tree navigation.
- Consistent actions across Files, Gallery, Search, Collections and Trash where relevant.
- Archived and Hidden each expose the same complete Gallery/file-viewing feature set in their own scoped categories and folder navigation (see A7); they must not be reduced to plain lists.

### B2. Simple automatic categorization
- Derive useful Photos, Videos, Audio, Documents, Archives, Apps/APKs and Other views from MIME type/extension and reliable metadata.
- Keep the user's actual folder hierarchy as the primary organization system.
- No mandatory manual tagging of every file or photo.
- Apply the same automatic categories inside Archived and Hidden, with category results restricted to the current scope rather than mixing archived/hidden items into the ordinary library.

### B3. Global search and easy discovery
- Search by name, extension, folder/path, type, size, date, backup/offline/favorite status.
- Fast debounced local search against indexed metadata and paged results.
- Helpful filters and predictable sort options.
- Search defaults to the current browsing scope; Archived and Hidden browsing/search remain isolated unless the user explicitly chooses to include those scopes.
- AI semantic search and deep OCR/content indexing remain deferred.

### B4. Original-quality image viewer with fast loading
- Display the original uploaded image, not a resized/compressed replacement.
- Full-resolution zoom and pan; maintain correct orientation where supported.
- Use a separate cached thumbnail for grid cards only.
- Optimize fetch, caching and request deduplication without changing original bytes.
- Verify the exact file hash through download/re-upload tests where the path supports it.

### B5. Video and audio playback
- Reliable local and Telegram-streamed playback on each target platform.
- Seek, skip, speed, repeat, audio-track selection and embedded subtitle selection.
- Add the requested volume and brightness sliders and external subtitle loading described in A1–A3.
- Graceful fallback when a codec/stream is unsupported; no silent corruption or fake "streaming" state.

### B6. Core ZIP/7z support
- Preview/list archive contents for ZIP and 7z.
- Safely browse entry names, sizes and folder structure without modifying the original archive.
- Basic extraction where supported, with path traversal protection and conflict handling.
- Advanced archive editing, nested-archive workflows, encryption/password support and exhaustive format support remain deferred.

### B7. Exact duplicate detection
- Group exact duplicates by SHA-256/content hash and verify size/hash before showing a match.
- Include local-only and backed-up library entries without confusing separate legitimate copies.
- Provide location/path and backup/local status to help the user decide.
- Never auto-delete.

### B8. Basic perceptual image duplicate detection
- Implement local perceptual hashing (for example pHash or an equivalent robust method) for image candidates.
- Start with reasonable similarity thresholds and show candidates as reviewable groups with a similarity indicator.
- Keep processing off the UI thread, incremental and throttled or on-demand; don't block normal library browsing or uploads.
- Do not auto-merge or auto-delete. The user chooses what to keep.
- Treat this as a required basic capability, not the advanced AI recognition feature that remains deferred.

### B9. Complete upload/download/copy/move/share/delete workflows
- Make workflows available for both files and folders where meaningful.
- No partial share of mixed selections: explain when a cloud-only item needs download first.
- Use verified remote copies before offering safe local-space cleanup.
- Keep ordinary deletion recoverable through Trash.

### B10. Reliable transfer queues, retry and recovery
- Preserve individual and global pause/resume/retry/cancel.
- Recover safely after app close, process termination, device restart and network loss.
- Handle retries, rate limits, interrupted multipart transfers and invalid/missing remote parts.
- Keep clear queue status and actionable error information.
- Verify the output with hashes after download/restore.

### B11. File integrity and protection against accidental loss
- Preserve the existing per-part and whole-file SHA-256 integrity checks.
- Verify uploads/downloads and multipart resume paths.
- Avoid destructive Telegram message operations through normal file-management actions.
- Keep recoverable Trash separate from Free up space.
- Report backup health and recovery state clearly; never silently discard a surviving valid copy.

### B12. Thumbnail caching and performance
- Reuse a bounded thumbnail cache and avoid full media fetch just to render cards.
- Keep lazy/paged lists and avoid unbounded in-memory library collections.
- Deduplicate identical in-flight thumbnail/preview requests where possible.
- Keep full original media downloads out of the thumbnail path.
- Keep optional subtle animations short and low-cost.
- Profile memory/CPU on the user's Windows machine and lower-memory Android device.

### B13. Minimal, consistent Windows/Android/Linux experience
- Shared behavior and design where possible, with native platform integration where required.
- Validate file pickers, folder access, sharing, player controls, menus, transfer recovery and archive support separately on each platform.
- Windows is the current primary test platform; Android and Linux runtime validation remain open.
- macOS is explicitly out of scope.

## C. Features still deferred until after the user builds the library

Do not mix these into the practical build:
- AI photo recognition, semantic visual search and automatic scene descriptions.
- Face recognition / people grouping.
- Extensive OCR and full-content indexing of all document formats.
- Elaborate calendar/map/GPS photo timelines and advanced EXIF intelligence.
- Smart albums, complex rules engines and other features without a demonstrated need.
- Advanced archive editing, nested archive operations and password-protected archive workflows.
- Sophisticated media processing, transcoding or adaptive streaming beyond what is required for reliable playback.

## D. Verification and acceptance

### Before code changes during the current test pass
- Collect the user's current Windows build/runtime findings first.
- Do not assume the recent Details view and 7z changes compile until Gradle confirms it.
- Keep new feedback documented without interrupting the user's active test run.

### Required regression workflow after implementation
1. Run `.\gradlew.bat :ui:desktopTest :shared:desktopTest :desktop:test :desktop:compileKotlin --console=plain`.
2. Run the desktop app and verify Grid/List/Details, file/folder selection, context menu placement, folder copy/move and actions.
3. Test local and remote-only media, original image resolution, streaming playback, volume/brightness controls and external subtitles.
4. Test ZIP and 7z archives with many entries and empty folders; confirm source archives remain unchanged.
5. Test exact and perceptual duplicates with known identical, resized/recompressed, cropped and unrelated photos. Check false positives manually; never delete automatically.
6. Test transfer queues with multiple simultaneous uploads/downloads, priorities, filtering, errors, retries, pause/resume, app restart and interrupted network.
7. Confirm file hashes after representative upload/download/copy/restore workflows.
8. Verify Archived and Hidden as full Gallery scopes: categories, scoped search/filters, Grid/List/Details, folder navigation, original-quality image viewing, video/audio playback, document/archive previews, file operations and explicit restore/unhide behavior.
9. Build and runtime-test Android and Linux separately. Do not mark those platforms complete after desktop-only tests.

## E. Current implementation-status snapshot

As inspected from source on 2026-11-08 (not a claim of local build success):
- Gallery core workspace and actions: substantially present in source; user is testing it.
- SHA-256 per-part and whole-file integrity: existing verified foundation, including a user-reported real 2 GiB round trip on Windows in the earlier project checkpoint.
- Files Details view: newly added to branch; compile/runtime verification pending.
- Basic 7z entry listing: newly added to branch; compile/runtime verification pending.
- Android swipe gestures for volume and brightness: present; requested visible sliders are missing.
- Embedded subtitle selection: present on Android Media3 and desktop VLC; external subtitle-file loading is missing.
- Folder move: repository and ViewModel support exists; UI discoverability/user-reported behavior needs testing.
- Folder copy: missing for folders (file copy exists).
- Desktop context menu: right-click selection/action menu exists; pointer-position placement is requested.
- Transfers: basic controls exist; advanced queue management remains to implement.
- Exact duplicate discovery and perceptual image duplicate discovery: not yet implemented as a user-facing review workflow.
- Folder-tree navigation pane and cross-platform validation: outstanding.

