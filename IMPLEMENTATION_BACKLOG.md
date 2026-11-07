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
- `FileRepository.copyFolder` is now implemented in source: it recursively clones nested and empty folders, generates Keep Both names when folder names conflict, and delegates file copies to the existing file-copy path (including Telegram-side copies for backed-up files).
- The selection UI now exposes Copy to for file, folder and mixed selections; folder move is also directly exposed in the same menu.
- The current recursive implementation is a first pass: compile/runtime validation, overlapping parent/child multi-selection handling, more explicit partial-failure reporting, and full Merge / Keep both / Cancel conflict UX remain to be verified or improved.

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

### A7. Full Gallery and file-viewing experience across all special library views

Required:
- Treat **Archived, Hidden, Trash, Offline and Favourites** as complete library browsing scopes, not simple lists or storage bins.
- Provide the same practical Gallery and file-viewing capabilities available in the normal library, using the same shared components and behavior wherever possible.
- Inside every scope, automatically categorize eligible items into Photos, Videos, Audio, Documents, Archives, Apps/APKs and Other using reliable MIME type/extension metadata.
- Keep categories and counts scoped: each view shows only items that match its own membership/status, without polluting ordinary Gallery/category counts.
- Support Grid, List and Details layouts, sorting, type/date/size filters, search within the current scope, multi-selection, breadcrumbs and navigation through nested folders.
- Show category and folder counts that reflect the current Archived/Hidden scope.
- Photos must open in the same original-quality viewer as ordinary Gallery images, with full-resolution zoom/pan and thumbnail-only derivatives.
- Videos and audio must use the same players and controls as the main library, including volume/brightness controls, track selection and external subtitles where supported.
- Documents and ZIP/7z archives must use the same supported preview/viewing flows as the main library. Unsupported formats should show a clear fallback rather than a broken viewer.
- Preserve the usual file operations where meaningful: copy, move, download, share, rename, restore/unarchive/unhide, and recoverable delete. Folder operations must work recursively where applicable.
- Clearly expose Restore/Unarchive and Unhide actions. Browsing, previewing, searching, copying or moving an item must not silently clear its archived/hidden status; only an explicit state-changing action should do so.
- Keep each special view clearly labelled and make the current scope visible so users know where they are working.
- Treat Archived, Hidden, Offline and Favourites as independent, overlapping attributes/views rather than mutually exclusive folders: a file may be both a favourite and available offline, or archived and favourited, for example. Changing one attribute must not silently clear another.
- Trash is a deletion lifecycle state: trashed items must be excluded from ordinary active library views while deleted; restore must reinstate their prior archive/hidden/favourite/offline attributes where those still apply.
- Reuse the normal thumbnail cache, paging/virtualisation, preview pipeline and low-memory behavior. Do not load all special-view media at once.
- This must work consistently on Windows, Android and Linux, with platform-appropriate file pickers and context actions.

Acceptance checks:
- An archived photo appears under Archived > Photos, opens at original resolution, and remains archived after viewing.
- A hidden video appears under Hidden > Videos, streams/plays with the same controls as a normal video, and remains hidden after playback.
- Trashed photos/videos/documents appear under the correct Trash categories and can be previewed where the underlying file is still available; restore and permanent-delete actions are explicit and distinguishable.
- Offline items appear under Offline categories and can be browsed/opened without a network connection when their local copy is complete; removing an offline copy must not delete the backed-up cloud item.
- Favourite items appear under Favourites categories regardless of their ordinary folder location; unfavouriting must not delete or move them.
- Search, filters, sorting, Grid/List/Details and folder navigation operate only within the selected special scope by default.
- A single item can appear in multiple applicable views (for example Favourites and Offline) without creating duplicate file records; toggling one status does not clear the others.
- Restoring/unarchiving or unhiding explicitly removes an item from that special scope only when its corresponding state is cleared; trash restore preserves other applicable statuses.
- Copy/move/download/share/delete actions respect the rules of the current scope and never silently change unrelated status flags or overwrite another item.
- Large special-view collections remain paged and responsive.

### A8. Scope-specific behavior for Trash, Offline and Favourites

These views share the Gallery experience in A7, but their actions must respect what each view means:

**Trash**
- Provide all media-type categories, scoped search/filter/sort, Grid/List/Details, folder navigation, multi-selection and supported previews.
- Offer Restore as the primary reversible action and Permanent delete as a separate, clearly destructive action with confirmation. Support bulk restore and bulk permanent deletion.
- Where the trashed original is still available locally or remotely, allow safe preview and download/export without requiring restoration first. Explain clearly when content is unavailable.
- Do not present normal rename/move/archive/hide operations as though the item were active. Restore first for ordinary file management.
- Preserve any other applicable attributes (favourite, offline, archived/hidden metadata) when restoring, unless the user explicitly changes them. Permanent deletion must clearly communicate that recovery is no longer available through TeleDrive.

**Offline**
- Provide the same categories and viewing modes, with scoped search/filter/sort, folder navigation, multi-selection and supported preview/playback.
- Offline must mean a complete verified local copy that can actually be opened without network access, not merely an item queued for download or partially cached.
- Show useful local-availability state (available, downloading, paused/failed, incomplete) without incorrectly treating incomplete transfers as offline-ready.
- Allow Make available offline / Download and Remove offline copy actions. Removing the local copy must preserve the Telegram/cloud-backed item and all unrelated attributes.
- Offline preview must use the local file and must not silently fall back to network streaming when the user is browsing specifically to access offline content.

**Favourites**
- Provide the same categories and viewing modes, scoped search/filter/sort, folder/path context, multi-selection and supported preview/playback.
- Favourites is a cross-folder collection: show favourite files from anywhere in the library without flattening away their original folder/path information.
- Allow favourite/unfavourite from the view and from other library scopes. Unfavouriting removes only the favourite status; it must not delete, move, archive, hide or remove an offline copy.
- Favourites can overlap with Offline, Archived and Hidden. Trash items remain in Trash rather than appearing as active favourites; their favourite status should be preserved across restore.

**Cross-view composition**
- A file can be both Offline and a Favourite, or Archived and a Favourite/Offline, and should appear in every applicable view without duplicate underlying records.
- One status change must affect only that status. Examples: Remove offline copy does not unfavourite; Unfavourite does not delete the local copy; Unarchive does not unhide; Unhide does not unarchive.
- Trash is the exception for active visibility: while an item is trashed, it is browsed in Trash, not in ordinary active Files/Gallery/Favourites/Offline views. Restore returns it to the views matching its preserved attributes.
- Show consistent counts, selection behavior, breadcrumbs/path context, and loading/empty/error states across all five views.

### A9. Share cloud-backed files directly — no forced download-first flow

User requirement:
- The Share action must start sharing directly. The current flow that first downloads a cloud-only file and only then offers the share option is not acceptable.
- Tapping Share should immediately open the relevant share destination/chooser or direct-share flow, without requiring the user to manually download the file first.
- Prefer sharing the existing remote Telegram-backed item where the target supports it (for example, a Telegram message/document reference or accessible share link), rather than routing through a local download.
- On Android, investigate a read-only, stream-backed content URI/provider for targets that require file bytes. The receiving app may read/stream the bytes on demand from the remote source; do not first materialize a second full copy in TeleDrive's normal Downloads folder. Respect URI permissions and revoke temporary access safely.
- On Windows and Linux, provide the best native direct-share experience available. Where a target accepts links, share a valid recipient-accessible link/reference; do not silently download the entire file merely to populate a share dialog.
- A link/reference must actually be usable by the intended recipient. Do not expose private Telegram channel/message identifiers as though they were public URLs, and do not claim that a private remote file is externally accessible unless permissions/access have been verified.
- If the chosen destination fundamentally requires a local file and no stream-backed or remote share method is supported, explain that limitation and offer an explicit user-initiated download fallback. Never start a hidden/automatic download and then unexpectedly show the share sheet.
- Preserve the original filename, MIME type, file bytes and integrity. Sharing must not mutate, recompress, resize, move, archive, hide, favourite, or remove the original item.
- Support multi-selection and folder sharing where the destination/platform permits it; otherwise clearly explain the supported selection constraints.
- Keep the same direct-share behavior available from Files, Gallery, Archived, Hidden, Trash (only where safe/meaningful), Offline and Favourites, respecting each view's state rules.

Acceptance checks:
- Tapping Share on a cloud-only file opens the share flow immediately and does not create a full local download first.
- A target that accepts a remote link receives a valid link that the intended recipient can actually open.
- A target that requests file bytes can read them through the supported streaming/provider path without TeleDrive first saving a complete duplicate into its normal Downloads location.
- If a destination cannot accept a remote link or streamed content, TeleDrive reports that limitation before any user-confirmed fallback download.
- Local-only and already-offline files continue to share normally without an unnecessary second download.
- The source file remains unchanged and its SHA-256 stays the same after sharing.

### A10. Compact right-click menu with direct actions and Google Drive-style folder colours

User's reference:
- Google Drive shows common actions such as Download, Rename, Share, Move, Add shortcut, Add to starred, Folder color and Folder information.
- TeleDrive currently has Share, Rename, Copy to…, Storage >, Organize >, Info and Move to trash. The user wants the common actions they need visible together, not buried inside Storage/Organize submenus.
- File Pilot is a reference for discoverable context actions, but TeleDrive should not copy its very large menu. Keep TeleDrive's menu compact and limited to the small set of relevant actions.

Required context-menu behavior:
- Keep the desktop right-click menu anchored at the actual pointer (see A4).
- Put common actions directly in the menu at the top level; do not require opening a Storage or Organize submenu to reach Download, Copy, Move, Favourite or other common actions.
- Make actions context-sensitive. Show only actions applicable to the current item type and state; for multi-selection, show actions that can safely apply to the whole selection. This keeps the menu compact instead of displaying irrelevant/disabled actions.
- For normal files, provide the relevant direct actions: Share, Download, Rename, Copy to…, Move to…, Add to/Remove from Favourites, Make available offline/Remove offline copy, Archive/Unarchive, Hide/Unhide, Info and Move to Trash.
- For folders, provide the relevant direct actions: Open, Share where supported, Rename, Copy to…, Move to…, Add shortcut, Add to/Remove from Favourites, Folder color, Info and Move to Trash. Include Archive/Unarchive and Hide/Unhide where those states are supported for folders.
- Download, Copy to… and Move to… must be independently visible actions. Copy must leave the source in place; Move must relocate the selected item/folder and preserve its content and metadata. Do not treat Copy as a substitute for Move.
- Add shortcut should create a lightweight reference to the existing item/folder in another folder, not a second uploaded copy of its bytes. Make shortcuts visually distinguishable, resolve actions/previews to the original item, and prevent dangling or recursive shortcuts. If this cannot be supported safely by the current data model, record the implementation limitation rather than pretending it is a normal copy.
- Info should open the existing information/details presentation without hiding basic actions in a separate nested menu.
- Preserve right-click multi-selection behavior: right-clicking an unselected item selects it; right-clicking an already-selected item keeps the multi-selection intact.
- Keep touch/long-press and keyboard-accessible actions on Android and desktop.
- The same action availability and state rules should apply in Files, Gallery, Archived, Hidden, Trash, Offline and Favourites where meaningful.

Google Drive-style folder colour:
- Add a small preset colour palette to folders, similar to Google Drive's Folder color menu. This is a visual organization aid, not a change to the folder's contents or name.
- Apply the chosen colour consistently to that folder's icon/visual marker in Grid, List, Details, folder navigation/tree, breadcrumbs and folder pickers where relevant.
- Persist folder colours across app restart and sync/restore them with folder metadata where the existing data model permits.
- Provide a default/reset colour option. Do not add a custom colour picker; use a restrained predefined palette.
- Folder colours must not affect file bytes, folder hierarchy, Telegram backup integrity or existing favorite/archive/hidden/offline state.
- Colour selection must be usable from the folder context menu and remain available when browsing that folder in Archived/Hidden/Trash/Offline/Favourites where applicable.
- Do not silently apply folder colours to files. File-level coloured labels/tags are not part of this requirement unless separately requested.

Acceptance checks:
- Right-clicking a file/folder opens a compact menu at the pointer with the appropriate direct actions; common actions are not buried inside Storage/Organize submenus.
- Download, Copy to… and Move to… are separate, working operations and appear directly when applicable.
- A file is not copied when the user chooses Move, and the source remains in place when the user chooses Copy.
- Add shortcut creates a reference rather than duplicating the file data, and opening/previewing it reaches the original.
- A chosen folder colour persists after restarting TeleDrive and appears consistently in all folder representations.
- A mixed or multi-item selection exposes only safe, applicable shared actions without breaking selection.
- The menu remains compact and is not expanded into a File Pilot-sized list of unrelated commands.

### A11. User-configurable media fields, metadata filters and date-based discovery

Required:
- Add a configurable **Fields / Display information** menu, inspired by MX Player's media-list options. Use a tick/checkmark next to each field so the user can choose exactly which metadata is displayed and used as useful discovery criteria.
- Include these selectable fields/options:
  - Thumbnail (show/hide preview thumbnails).
  - Length / duration.
  - File extension / format.
  - Played time (last played date/time where available, and playback/resume position as a separate useful value where supported).
  - Resolution.
  - Frame rate (FPS).
  - Full folder path / location.
  - File size.
  - Date information (use available created, added/imported, modified and last-played timestamps; label each meaning clearly).
- Let users select one field, several fields or all fields. A checked field should be available in the current view's display/details and corresponding filter/sort/group controls where that field can meaningfully be used; unchecking it removes that field from the selected display/filter configuration.
- Do not force every field into a narrow row. On desktop, provide a Details/column configuration; on Android, use compact metadata rows/cards and a fields/filter panel with touch-friendly controls. The available information and discovery capabilities should match across platforms even when presentation differs.
- Provide filters and sorting that can be combined: e.g. type/extension + resolution + date range, or duration + size + path. Allow ascending/descending sort where meaningful.
- Provide date-based grouping/categorization (such as year/month/day or date ranges) and date sorting. Make the date basis explicit (created, added/imported, modified or last played); do not silently treat these timestamps as interchangeable.
- Apply the same metadata fields, filters and sorting to Files, Gallery, Archived, Hidden, Trash, Offline and Favourites. Results must remain scoped to the current view and respect that view's membership rules.
- Keep thumbnail visibility independent from the availability of file metadata. Turning thumbnails off must not disable metadata search, sorting or the ability to preview a file when opened.
- Extract media metadata lazily/incrementally and cache it safely. Do not decode every full-resolution image or video just to show a list. Use existing media metadata when present and show a clear unavailable/unknown value if a field cannot be read.
- Keep user field selections and sort/filter preferences persistent. Prefer sensible defaults and allow the user to reset to defaults.
- Maintain responsiveness for large libraries: indexed queries, paging/virtualisation, debounced filters and no unbounded in-memory scans.

Acceptance checks:
- The user can tick only Resolution, Size and Date, for example, and see/filter/sort by those fields without unrelated metadata clutter.
- The user can enable every available field and use combined filters/sorts.
- Date grouping/sorting clearly distinguishes modified vs added vs last played dates.
- Fields and filtering work in all five special views plus Files and Gallery, without leaking results between scopes.
- Missing metadata does not break the list; thumbnails can be hidden without affecting original-quality viewing.
- The same fields and discovery behavior work on Windows, Android and Linux, with platform-appropriate layouts.

### A12. Complete cross-platform media-player controls

Required:
- All capabilities below must be available on Windows, Android and Linux. The controls, gestures, menus, shortcuts and native integrations may differ to fit each platform; feature parity means the user can perform the same underlying action on each supported platform.
- Keep controls discoverable without overcrowding the player. Use a compact controls panel / overflow menu for less frequently used settings and remember suitable per-user defaults.

Playback and queue:
- Playback speed: selectable speed options to increase/decrease playback speed, with clear current-speed indication and reset to normal speed.
- Shuffle: shuffle the current playback queue/playlist, with a clear on/off state.
- Loop/repeat: Off, Repeat current item and Repeat all/queue where a queue exists.
- Sleep timer: user-selectable countdown to stop/pause playback, plus a useful option to stop after the current item. Show remaining time and allow cancel/reset.
- Background playback: audio must continue when the app is backgrounded or the screen is off where the OS permits it; provide native media-session/notification/lock-screen controls where available. Video-to-audio background behavior should be clear and predictable.
- Picture-in-Picture / floating mini-player: provide native PiP on Android where supported; on desktop provide an equivalent compact always-on-top/floating mini-player where the platform/window manager supports it. Preserve playback state when entering/exiting.
- Screen rotation/orientation: on Android provide orientation behavior/rotation lock appropriate to the device. On desktop provide video rotation controls (e.g. rotate by 90-degree steps and reset) rather than attempting to rotate the physical monitor. Keep this capability discoverable and platform-appropriate.
- Audio/video synchronization: provide independent audio delay and subtitle delay adjustments, with positive/negative offset controls, fine adjustment steps, a visible current offset and reset-to-zero. Do not conflate audio delay with playback speed.
- Keep the previously requested in-player volume and brightness sliders, embedded/external subtitle selection and audio-track selection, plus seeking and other existing working controls.
- Ensure player settings that are sensible to persist (e.g. speed, preferred subtitle/audio track) are saved intentionally; keep transient settings (e.g. sleep timer and sync offsets) scoped appropriately unless there is a well-defined per-file preference.

Platform-specific behavior and implementation:
- Implement through each platform's suitable media backend and native APIs: Android Media3/system media session/PiP/orientation; desktop VLC/player output and native window/taskbar/media integration where supported. Reuse shared domain state and behavior where practical without forcing identical UI controls across mouse, keyboard and touch.
- On Windows/Linux, background playback should continue when TeleDrive is minimized or not focused; expose native media controls when supported by the desktop environment.
- On desktop, PiP means a small separate floating video window with always-on-top behavior where supported. On Android, use the OS PiP lifecycle.
- On desktop, rotation applies to the video image/rendering. On Android, rotation/orientation should follow device and player-screen rules without unexpectedly changing global device settings.
- Where a backend, device or OS version genuinely lacks a capability, provide a clear explanation/fallback rather than a dead control or silently omitting the feature. Do not mark overall cross-platform parity complete until each target platform has been tested.

Acceptance checks:
- Sleep timer reliably stops playback at the selected time or after the current item and can be cancelled.
- Shuffle and all repeat modes work correctly across a multi-item queue and a single item.
- Playback speed changes, displays the current rate and returns to normal on reset.
- Background audio keeps playing when switching apps/minimizing and exposes native transport controls where supported.
- PiP/floating mini-player can be entered and exited without losing playback position.
- Rotation behaves correctly on mobile and desktop using each platform's appropriate interpretation.
- Audio delay and subtitle delay can be adjusted independently, in both directions, and reset.
- Every capability is validated on Windows, Android and Linux; a desktop-only implementation is not counted as parity.

## B. The complete required 13-capability scope

These are all required before the user begins building the full library. Some parts already exist; the checklist means complete and verify the overall capability, not rebuild everything from scratch.

### B1. Complete file and folder management
- Upload individual files and whole folders while preserving relative folder structure.
- Create, rename, copy, move, add shortcut, share directly without forced download-first behavior, download, hide, archive, favourite, mark offline, set folder colour and move to recoverable Trash (see A9–A10).
- Support recursive folder copy/move, empty folders and conflict-safe operations.
- Grid, List and Details views, sorting, multi-selection, keyboard navigation, compact right-click context menus at the pointer with direct Download/Copy/Move/Share and other applicable actions, persistent Google Drive-style folder colours, breadcrumbs and folder-tree navigation (see A10).
- Consistent actions across Files, Gallery, Search, Collections and all special views where relevant.
- Archived, Hidden, Trash, Offline and Favourites each expose the same complete Gallery/file-viewing feature set in their own scoped categories and folder navigation (see A7–A8); they must not be reduced to plain lists.

### B2. Simple automatic categorization
- Derive useful Photos, Videos, Audio, Documents, Archives, Apps/APKs and Other views from MIME type/extension and reliable metadata.
- Keep the user's actual folder hierarchy as the primary organization system.
- No mandatory manual tagging of every file or photo.
- Apply the same automatic categories inside Archived, Hidden, Trash, Offline and Favourites, with category results restricted to the current scope rather than mixing special-view items into ordinary library counts.

### B3. Global search and easy discovery
- Search by name, extension, folder/path, type, size, date, backup/offline/favorite status and available media metadata (duration, resolution, frame rate, last played).
- Fast debounced local search against indexed metadata and paged results.
- User-selectable ticked metadata fields, combined filters, date-basis-aware grouping and predictable sort options (see A11).
- Search defaults to the current browsing scope; special-view browsing/search remain isolated unless the user explicitly chooses to search across scopes. Filters should support intersecting attributes (for example favourites that are also offline) without duplicating records.
- AI semantic search and deep OCR/content indexing remain deferred.

### B4. Original-quality image viewer with fast loading
- Display the original uploaded image, not a resized/compressed replacement.
- Full-resolution zoom and pan; maintain correct orientation where supported.
- Use a separate cached thumbnail for grid cards only.
- Optimize fetch, caching and request deduplication without changing original bytes.
- Verify the exact file hash through download/re-upload tests where the path supports it.

### B5. Video and audio playback
- Reliable local and Telegram-streamed playback on each target platform.
- Seek, skip, speed, shuffle, repeat, sleep timer, background playback, PiP/floating mini-player, rotation/orientation, audio delay, subtitle delay, audio-track selection and embedded subtitle selection (see A12).
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
- Share cloud-backed items directly through valid remote references/links or an on-demand streaming provider where supported; do not force a prior full download. Be explicit about destinations that require a user-confirmed download fallback (see A9).
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
- **Feature parity is required across Windows, Android and Linux:** every agreed capability must have a usable implementation on all three. Identical UI is not required; mouse/keyboard, touch, gestures, native system controls and platform conventions should be respected.
- For any capability with different platform semantics (such as Android screen orientation vs desktop video rotation, or Android PiP vs desktop floating mini-player), provide the closest appropriate native equivalent that preserves the user's intended outcome.
- Do not count a feature as complete just because it exists on one platform. Track implementation, build, automated tests and runtime validation per platform.
- Validate file pickers, folder access, sharing, player controls, menus, transfer recovery, metadata discovery and archive support separately on each platform.
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
2. Run the desktop app and verify Grid/List/Details, file/folder selection, context menu placement and compact direct actions, folder copy/move/shortcut, persistent folder colours and action behavior across special views.
3. Test local and remote-only media, original image resolution, streaming playback, volume/brightness controls and external subtitles.
4. Test ZIP and 7z archives with many entries and empty folders; confirm source archives remain unchanged.
5. Test exact and perceptual duplicates with known identical, resized/recompressed, cropped and unrelated photos. Check false positives manually; never delete automatically.
6. Test transfer queues with multiple simultaneous uploads/downloads, priorities, filtering, errors, retries, pause/resume, app restart and interrupted network.
7. Confirm file hashes after representative upload/download/copy/restore workflows.
8. Verify Archived, Hidden, Trash, Offline and Favourites as full Gallery scopes: categories, scoped search/filters, Grid/List/Details, folder navigation, original-quality image viewing, video/audio playback, document/archive previews, and scope-appropriate actions.
9. Verify overlapping attributes (Favourite + Offline, Archived + Favourite, etc.), independent status changes, Trash restore preservation, permanent deletion safeguards and offline-only playback without network fallback.
10. Verify direct sharing for cloud-only, local-only and offline files on Android, Windows and Linux. Confirm cloud-only Share opens immediately without a forced full download, destination links are genuinely accessible, streaming providers preserve file bytes, and unsupported destinations offer only an explicit download fallback.
11. Verify selectable metadata fields, combined filters, date grouping/sorting and persistent preferences on Windows, Android and Linux.
12. Verify every player feature in A12 on Windows, Android and Linux using each platform's native interaction model and media integration.
13. Build and runtime-test Android and Linux separately. Do not mark those platforms complete after desktop-only tests.

## E. Current implementation-status snapshot

As inspected from source on 2026-11-08 (not a claim of local build success):
- Gallery core workspace and actions: substantially present in source; user is testing it.
- SHA-256 per-part and whole-file integrity: existing verified foundation, including a user-reported real 2 GiB round trip on Windows in the earlier project checkpoint.
- Files Details view: newly added to branch; compile/runtime verification pending.
- Basic 7z entry listing: newly added to branch; compile/runtime verification pending.
- Android swipe gestures for volume and brightness: present; requested visible sliders are missing.
- Embedded subtitle selection: present on Android Media3 and desktop VLC; external subtitle-file loading is missing.
- Folder move: repository and ViewModel support exists; UI discoverability/user-reported behavior needs testing.
- Folder copy: recursive implementation and folder-selection UI are now present in source; build/test/runtime verification pending. Overlapping selected folder roots and detailed partial-failure reporting still need review.
- Desktop context menu: right-click selection/action menu exists; pointer-position placement is requested.
- Transfers: basic controls exist; advanced queue management remains to implement.
- Exact duplicate discovery and perceptual image duplicate discovery: not yet implemented as a user-facing review workflow.
- Folder-tree navigation pane and cross-platform validation: outstanding.

