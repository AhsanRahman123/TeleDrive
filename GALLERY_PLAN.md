# TeleDrive Gallery — Product and Implementation Plan

Last updated: 2026-11-07
Target platforms: Windows, Android, Linux. macOS is out of scope.

## Product goal

Gallery should be the media workspace for the TeleDrive library, not an isolated thumbnail screen. A user should be able to find, inspect, stream, share, copy, move, download, upload, organise and safely remove photos/videos without bouncing between screens unnecessarily.

Gallery must reuse the existing file repository, transfer queue, preview resolver/player, folder picker, platform sharing abstraction, trash and offline-copy protections. Do not create a second media-storage path.

## Existing functionality confirmed in the current branch

- Tabs: All, Photos, Videos, Albums.
- Albums are derived from folders that contain image/video files; the unfiled group is represented as an album.
- Paged media list (page size is driven by the file repository) and lazy grid/list presentation.
- Thumbnail loading through ThumbnailModel and the existing thumbnail store/cache.
- Day headers when sorted by date added or date modified.
- Sort by name, size, date modified, date added, type and backup status.
- Grid-size changes through pinch zoom; gallery view mode is stored in user preferences.
- Multi-selection, right-click, persistent checkboxes, range selection and edge auto-scroll.
- Bulk move, trash, download, upload, favorite, offline availability, hide and archive.
- Open a selected media item through PreviewSequence. The existing preview resolver supports image preview and local/remote video streaming when the platform player is available; it falls back to download/external playback when needed.
- FileRepository already supports server-side copyFiles(ids, targetFolderId).
- LocalFileSharer is already provided by both platform compositions: Android opens the system share sheet for local paths; desktop reveals the containing folder. Cloud-only items cannot be passed directly to a local-file share sheet.
- FileInfoSheet and the Preview info panel already provide a foundation for metadata details.

## Gallery experience design

### 1. Primary browsing
- Keep All, Photos, Videos and Albums as the primary content modes.
- Add a fast inline filename search scoped to media results.
- Add filter chips / an advanced filter surface for Favorites, Offline, Backed up, Not backed up, extension, file size, and date range.
- Keep sort controls and grid/list controls visible and predictable.
- Preserve pagination, scroll position, thumbnail caching and keyboard navigation.
- Provide useful empty states that distinguish an empty library from an empty filter result.

### 2. Timeline and discovery
- Keep day grouping as a base and improve it to a navigable date timeline with month/year jumping.
- Add a capture-date timeline only after a real capture timestamp can be extracted and synced. At present DriveFile has width, height and duration, but no EXIF capture date, GPS or camera fields. Current day headers use file modified/added timestamps, not necessarily the moment a photo was taken.
- Later: calendar view, location map (opt-in, privacy-aware), Recently Added, Favorites, Offline and smart collections.
- Duplicate detection should start with exact contentHash matches. Perceptual similarity (pHash) is a later opt-in local CPU feature, not part of the initial release.

### 3. Album management
- Albums currently map to folders; retain this as the single source of truth.
- Allow creating an empty album/folder from Gallery.
- Show empty folders as empty albums, while excluding folders containing only non-media files.
- Sort albums by name, item count or most recent media activity.
- Reuse the Files folder picker for copy/move targets. Avoid introducing a separate album database.
- Later: album covers, album descriptions, smart albums and multi-folder nesting improvements.

### 4. One-place actions
- Open / Preview: use the existing PreviewSequence and PreviewViewModel; do not implement another player.
- Stream: keep existing Telegram-backed range streaming and platform playback.
- Download: enqueue through TransferRepository and show the existing View downloads snackbar action.
- Copy to: use FileRepository.copyFiles; this can duplicate local copies and server-side Telegram documents without downloading/re-uploading backed-up files.
- Move to: use FileRepository.moveFiles.
- Share: use LocalFileSharer for locally available paths. If any selected item is cloud-only, explain that it must be downloaded first; never silently share only a subset.
- Upload: keep the existing enqueueUpload pathway for local copies.
- Favorite, make available offline, hide and archive: reuse existing repository/use-case operations.
- Delete: Gallery's primary action is Move to Trash. Do not expose permanent remote Telegram deletion here.
- Free up space: distinct from Trash; only remove a local copy after remote verification, using FileRepository.deleteLocalCopy.
- Info: show file type, size, dimensions, duration, local/remote availability, backup state, timestamps and checksum when available.

### 5. Viewer experience
- Reuse the current preview screen for next/previous navigation, image display, video stream/local playback, audio/subtitle tracks and download progress.
- Improve image zoom/pan/fit/fill, rotate, slideshow and playback controls in place; don't build a second media engine.
- Add side-by-side compare for selected images later.
- Show a persistent information panel with safe metadata.
- Sharing from the viewer and Gallery must use the same LocalFileSharer semantics.

### 6. Metadata and database
- Existing persisted metadata: width, height, duration, file MIME type, timestamps and contentHash.
- Missing: EXIF capture date, camera make/model, GPS coordinates, orientation and description/tags.
- A later metadata milestone will require a Room schema migration (next version after 18), migration tests, manifest/sync updates and Android/Desktop extractors. Keep metadata extraction off the UI thread and avoid full-resolution image decode just to read metadata.
- GPS must be private by default. Never create location-sharing links or include GPS in public exports without explicit user choice.
- Tags should be local/indexed metadata with sync/manifest support; don't overload folder names or free-form captions.

### 7. Performance and safety constraints
- Preserve paging and lazy grid/list; never load the complete media library into memory.
- Reuse cached thumbnails; bound memory and disk caches.
- Generate thumbnails and extract metadata off the UI thread.
- Do not fetch full media just to display a card.
- Use remote streaming for supported media rather than downloading full files.
- All destructive bulk operations must be confirmed. Trash is recoverable; permanent delete is not a Gallery action.
- Test local-only, remote-only, split/multipart, encrypted and mixed selections.

## Current implementation checkpoint — Gallery 1

Code is pushed to `codex/file-integrity-phase-1`; the local Gradle build and runtime verification are still pending.

Implemented in source:
- Debounced local media-name search.
- Quick filters: Favorites, Offline, Backed up, Not backed up. Backup filters are mutually exclusive.
- Search and filter criteria are carried into the Preview next/previous sequence, so the viewer stays within the filtered result set.
- Explicit Grid/List view switch for media and albums.
- Album list-row presentation as well as thumbnail grid.
- Albums sort by name, item count, and latest media activity; album sort menu only offers meaningful fields.
- Empty folders can appear as zero-item albums. Folders with document-only content are not shown as photo/video albums.
- New album action creates a folder, retaining folders as the single source of truth.
- Gallery selection menu includes Copy to…, Share, Details, Move to…, Download, Upload, Favorites, Offline, Hide, Archive, Trash and safe Free up space when applicable.
- Share uses the existing platform FileSharer and refuses partial sharing if some selected media is cloud-only.
- Free up space uses the existing verified-local-copy deletion and platform consent flow. Trash remains separate and recoverable.
- Gallery download notifications include View downloads navigation.
- Empty/populated album SQL scenarios have desktop database tests.

Not yet built or runtime-verified on the user's Windows/Android/Linux devices.

## Implementation milestones

### Gallery 1 — Core workspace and action completeness
- Add Gallery Copy to… and Share to the selection action menu.
- Add a single-file information sheet action.
- Add inline media search and quick filters backed by the existing FileQuerySpec fields.
- Add explicit grid/list buttons and keep current pinch zoom.
- Add create-album workflow; albums remain folders.
- Include empty folders in Albums, excluding folders that contain only non-media files.
- Add album sorting by name, count and recent activity.
- Add tests for query/filter behavior and Gallery view-model action routing.

### Gallery 2 — Timeline and metadata
- Add schema/manifest metadata for capture timestamp, orientation, camera make/model, optional GPS.
- Extract metadata safely on Android and desktop, without blocking UI or decoding full-size images.
- Timeline grouping by capture date with fallback to file added/modified date.
- Calendar/year/month navigator and optional map view.

### Gallery 3 — Viewer polish
- Image fit/fill, zoom/pan, rotate and slideshow.
- Video playback resume/seek polish, playback speed, audio/subtitle track picker consistency.
- Side-by-side image comparison and info panel.
- Make player/preview behaviour consistent across Windows, Android and Linux.

### Gallery 4 — Smart library tools
- Saved filters and smart albums.
- Exact duplicate groups using contentHash; optional perceptual duplicates later.
- Ratings/labels/tags with batch edit and search.
- Optional people/object AI only as a later opt-in module; no AI dependency for core Gallery.

## Verification requirements

For each Gallery feature, track separately: implemented, compiled, unit-tested, desktop runtime-tested, Android runtime-tested and Linux runtime-tested.

Real-media smoke tests should include:
1. image and video thumbnails with remote-only and local-only copies;
2. image preview and video stream without full download;
3. copy a remote-only media item to another album and verify the remote copy and local database;
4. move a file between albums and confirm both counts/covers update;
5. share local media and confirm cloud-only media is not silently omitted;
6. enqueue download and use the transfer shortcut;
7. Trash and restore a media item without deleting the Telegram source;
8. large album pagination, scroll performance and memory usage.
