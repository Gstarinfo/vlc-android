<#
.SYNOPSIS
    Automated Upstream Synchronization Script for VLC Enhanced.
.DESCRIPTION
    Safely fetches updates from official VideoLAN (upstream), updates the upstream-tracking
    branch, rebases VLC Enhanced (master) on top of the latest upstream commits,
    verifies compilation, and optionally pushes to your fork (origin).
#>

param(
    [switch]$NoBuild,
    [switch]$AutoPush
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         VLC Enhanced - Upstream Sync Tool" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check working directory status
$status = git status --porcelain
if ($status) {
    Write-Warning "Your working tree has uncommitted changes:"
    git status -s
    $choice = Read-Host "Do you want to stash these changes before proceeding? (y/n)"
    if ($choice -match "^[Yy]") {
        git stash save "Auto-stashed before upstream sync on $(Get-Date)"
        $stashed = $true
    } else {
        Write-Error "Please commit or stash your changes before syncing."
        exit 1
    }
} else {
    $stashed = $false
}

# 2. Ensure upstream remote exists
$remotes = git remote
if ($remotes -notcontains "upstream") {
    Write-Host "Adding upstream remote (https://github.com/videolan/vlc-android.git)..." -ForegroundColor Yellow
    git remote add upstream https://github.com/videolan/vlc-android.git
}

# 3. Fetch latest upstream commits
Write-Host "`n[1/4] Fetching latest commits from official VideoLAN upstream..." -ForegroundColor Green
git fetch upstream master:upstream-tracking --tags

# 4. Rebase master onto upstream-tracking
Write-Host "`n[2/4] Rebasing VLC Enhanced (master) on top of upstream-tracking..." -ForegroundColor Green
try {
    git rebase upstream-tracking
} catch {
    Write-Host "`n[!] Rebase paused due to a conflict." -ForegroundColor Red
    Write-Host "Because your customizations are modular (VideoPlayerSubtitleDelegate, VideoPlayerZoomDelegate)," -ForegroundColor Yellow
    Write-Host "conflicts are minimal and usually only in the 1-line hook calls." -ForegroundColor Yellow
    Write-Host "`nTo resolve:" -ForegroundColor Yellow
    Write-Host "  1. Review conflicting files using: git status"
    Write-Host "  2. Keep your delegate hook calls intact"
    Write-Host "  3. Mark resolved: git add <file>"
    Write-Host "  4. Continue: git rebase --continue"
    exit 1
}

# 5. Restore stash if stashed
if ($stashed) {
    Write-Host "`nRestoring stashed changes..." -ForegroundColor Green
    git stash pop
}

# 6. Verify Kotlin Compilation
if (-not $NoBuild) {
    Write-Host "`n[3/4] Verifying compilation (:application:vlc-android:compileDebugKotlin)..." -ForegroundColor Green
    .\gradlew.bat :application:vlc-android:compileDebugKotlin
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Build verification failed! Please inspect compilation errors."
        exit 1
    }
    Write-Host "Build verification passed with 0 errors!" -ForegroundColor Green
} else {
    Write-Host "`n[3/4] Build verification skipped (--NoBuild flag set)." -ForegroundColor Yellow
}

# 7. Push to origin (your fork)
Write-Host "`n[4/4] Upstream sync complete!" -ForegroundColor Green
if ($AutoPush) {
    Write-Host "Pushing rebased master to your GitHub fork (origin)..." -ForegroundColor Green
    git push origin master --force-with-lease
} else {
    $pushChoice = Read-Host "Do you want to push the updated master to your GitHub fork (origin)? (y/n)"
    if ($pushChoice -match "^[Yy]") {
        git push origin master --force-with-lease
        Write-Host "Pushed successfully to origin/master!" -ForegroundColor Green
    } else {
        Write-Host "Master updated locally. You can push manually when ready with: git push origin master --force-with-lease" -ForegroundColor Cyan
    }
}

Write-Host "`nAll done! Your VLC Enhanced fork is up to date with official VideoLAN." -ForegroundColor Cyan
