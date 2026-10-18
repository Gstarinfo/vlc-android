# VLC Enhanced — Modular Architecture & Fork Maintenance Guide

This document describes the modular architecture of **VLC Enhanced** and provides step-by-step instructions for staying updated with the official VideoLAN (`videolan/vlc-android`) repository without losing your custom features or encountering painful merge conflicts.

---

## 1. Architectural Philosophy: The Delegate Pattern

In the official VLC Android repository, files like [VideoPlayerActivity.kt](file:///application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt) (~3,000 lines) and [VideoTouchDelegate.kt](file:///application/vlc-android/src/org/videolan/vlc/gui/video/VideoTouchDelegate.kt) (~1,000 lines) are frequently updated by VideoLAN developers.

Directly editing these files causes severe merge conflicts whenever upstream releases an update. To prevent this, VLC Enhanced separates all custom features into **standalone delegate classes**:

```
application/vlc-android/src/org/videolan/vlc/gui/video/
├── VideoPlayerActivity.kt          # Upstream core player (holds only 1-line delegate hooks)
├── VideoTouchDelegate.kt           # Upstream touch handler (delegates gestures)
├── VideoPlayerOverlayDelegate.kt   # Upstream overlay handler (delegates editor UI)
│
├── VideoPlayerSubtitleDelegate.kt  # [CUSTOM] All subtitle dragging, bounds, detents & UI
└── VideoPlayerZoomDelegate.kt      # [CUSTOM] All smooth pinch-to-zoom, pan & spring-back
```

### Key Benefits
- **Zero Inline Clutter:** Upstream files contain only clean, 1-line pass-through calls.
- **Independent Feature Evolution:** Changes or improvements to subtitles or zoom happen entirely in their own files without touching upstream VLC files.
- **Frictionless Upstream Rebase:** When upstream VideoLAN commits new features or bug fixes, Git merges them cleanly because our footprint in upstream files is minimal.

---

## 2. Component Breakdown

### A. [VideoPlayerSubtitleDelegate.kt](file:///application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerSubtitleDelegate.kt)
Encapsulates all logic related to subtitle positioning:
- **Surface Discovery:** Safely finds `surface_subtitles` with `ViewStub` inflation fallback so subtitles are discoverable in all media formats.
- **Bounds Calculation:** Calculates normalized container bounds, top/bottom safety margins, and letterbox-aware baselines.
- **Touch & Gesture Handling:** Hit-tests subtitle proximity across any aspect ratio (`16:9`, `2.39:1`, anamorphic) with detent haptic snapping at `0 px`.
- **Editor UI:** Manages the top floating glassmorphic pill controls (`▲`, `▼`, `Bottom`, `Middle`, `Top`, `Reset`, `Done`).
- **Persistence:** Automatically saves and restores offsets using `SharedPreferences`.

### B. [VideoPlayerZoomDelegate.kt](file:///application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerZoomDelegate.kt)
Encapsulates all continuous pinch-to-zoom logic:
- **Smooth Scaling:** Arbitrary continuous scale factor (`1.0x` to `5.0x`).
- **Two-Finger Tracking:** Focal point tracking and natural pan translation while zoomed in.
- **Boundary Clamping:** Prevents panning beyond video frame edges.
- **Elastic Feedback:** Spring-back to `100%` with haptic feedback when zooming out below `1.05x`.
- **Quick Reset:** Double-tap anywhere while zoomed in resets back to `100%`.

---

## 3. How to Update Your Fork from Official VideoLAN

### Automatic Synchronization (Recommended)
Run the automated PowerShell sync script:

```powershell
.\scripts\sync-upstream.ps1
```

This script will automatically:
1. Verify your working directory is clean.
2. Fetch the latest commits and tags from official VideoLAN (`upstream/master`).
3. Update your local `upstream-tracking` branch.
4. Rebase your `master` branch cleanly on top of `upstream-tracking`.
5. Run a build verification check (`:application:vlc-android:compileDebugKotlin`).
6. Prompt you to push the updated `master` branch to your GitHub fork (`origin`).

### Manual Synchronization Workflow
If you prefer running Git commands manually:

```powershell
# 1. Fetch latest commits from official VideoLAN
git fetch upstream master:upstream-tracking --tags

# 2. Rebase master onto the updated upstream branch
git rebase upstream-tracking

# 3. Verify compilation
.\gradlew.bat :application:vlc-android:compileDebugKotlin

# 4. Push rebased master to your fork
git push origin master --force-with-lease
```

---

## 4. Best Practices for Adding Future Custom Features

Whenever you want to add a new feature to VLC Enhanced:
1. **Create a Dedicated Delegate or Utility:** Place it in `org.videolan.vlc.gui.video` or a dedicated package.
2. **Keep Upstream Modifications to 1-Line Hooks:** In `VideoPlayerActivity.kt` or `VideoTouchDelegate.kt`, only add a single hook call (e.g., `player.myFeatureDelegate.onEvent(...)`).
3. **Keep Custom Resources Isolated:** Give custom XML layouts, drawables, or menus unique filenames (e.g., `player_my_feature.xml`) so upstream never conflicts with them.
