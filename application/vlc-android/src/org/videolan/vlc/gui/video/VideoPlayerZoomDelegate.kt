/*
 * ************************************************************************
 *  VideoPlayerZoomDelegate.kt
 * *************************************************************************
 * Copyright © 2026 VLC authors, VideoLAN and Gstarinfo
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * **************************************************************************
 */

package org.videolan.vlc.gui.video

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ScaleGestureDetectorCompat
import org.videolan.vlc.R
import kotlin.math.roundToInt

/**
 * Modular delegate encapsulating continuous smooth zoom (0.25x to 5.0x),
 * two-finger focal tracking & panning, boundary clamping, and double-tap zoom reset
 * for VLC Enhanced.
 */
class VideoPlayerZoomDelegate(private val player: VideoPlayerActivity) {

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 5.0f
    }

    var currentScale: Float = 1.0f
        private set
    var currentTransX: Float = 0f
        private set
    var currentTransY: Float = 0f
        private set

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var scaleTargetView: View? = null

    val isZoomed: Boolean
        get() = currentScale < 0.95f || currentScale > 1.05f

    private val mScaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            val touchControls = player.touchDelegate.touchControls
            if (touchControls and TOUCH_FLAG_SCALE != TOUCH_FLAG_SCALE) return false
            if (player.isLocked || player.touchDelegate.touchAction == TOUCH_FASTPLAY) return false

            player.subtitleDelegate.cancelPendingTouch()

            scaleTargetView = player.videoLayout?.findViewById(R.id.player_surface_frame) ?: player.videoLayout
            scaleTargetView?.let {
                it.pivotX = it.width / 2f
                it.pivotY = it.height / 2f
            }
            player.videoLayout?.clipChildren = false
            player.videoLayout?.clipToPadding = false

            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return player.touchDelegate.screenConfig.xRange != 0 || player.fov == 0f
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val touchControls = player.touchDelegate.touchControls
            val touchAction = player.touchDelegate.touchAction

            if (player.fov != 0f && !player.isLocked && (touchControls and TOUCH_FLAG_SCALE == TOUCH_FLAG_SCALE)) {
                val diff = VideoPlayerActivity.DEFAULT_FOV * (1 - detector.scaleFactor)
                if (player.updateViewpoint(0f, 0f, diff)) {
                    player.fov = (player.fov + diff).coerceIn(MIN_FOV, MAX_FOV)
                    return true
                }
            } else if (player.fov == 0f && !player.isLocked && (touchControls and TOUCH_FLAG_SCALE == TOUCH_FLAG_SCALE) && touchAction != TOUCH_FASTPLAY) {
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor <= 0f) return true

                val target = scaleTargetView ?: return true
                val prevScale = currentScale
                val newScale = (currentScale * factor).coerceIn(0.20f, 5.5f)
                val scaleRatio = newScale / prevScale
                currentScale = newScale

                val focusX = detector.focusX
                val focusY = detector.focusY
                val deltaX = focusX - lastFocusX
                val deltaY = focusY - lastFocusY
                lastFocusX = focusX
                lastFocusY = focusY

                val targetW = target.width.takeIf { it > 0 }?.toFloat() ?: player.touchDelegate.screenConfig.xRange.toFloat()
                val targetH = target.height.takeIf { it > 0 }?.toFloat() ?: player.touchDelegate.screenConfig.yRange.toFloat()

                val centerX = (target.left + target.right) / 2f + currentTransX
                val centerY = (target.top + target.bottom) / 2f + currentTransY

                currentTransX += deltaX + (focusX - centerX) * (1f - scaleRatio)
                currentTransY += deltaY + (focusY - centerY) * (1f - scaleRatio)

                val maxTransX = if (currentScale >= 1.0f) {
                    ((targetW * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
                } else {
                    ((targetW * (1f - currentScale)) / 2f).coerceAtLeast(0f)
                }
                val maxTransY = if (currentScale >= 1.0f) {
                    ((targetH * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
                } else {
                    ((targetH * (1f - currentScale)) / 2f).coerceAtLeast(0f)
                }

                val boundedTransX = currentTransX.coerceIn(-maxTransX * 1.25f, maxTransX * 1.25f)
                val boundedTransY = currentTransY.coerceIn(-maxTransY * 1.25f, maxTransY * 1.25f)
                currentTransX = boundedTransX
                currentTransY = boundedTransY

                target.scaleX = currentScale
                target.scaleY = currentScale
                target.translationX = boundedTransX
                target.translationY = boundedTransY

                val percentage = (currentScale * 100).roundToInt()
                player.overlayDelegate.showInfo("$percentage%", 600)
                return true
            }
            return false
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            val touchControls = player.touchDelegate.touchControls
            val touchAction = player.touchDelegate.touchAction

            if (player.fov == 0f && !player.isLocked && (touchControls and TOUCH_FLAG_SCALE == TOUCH_FLAG_SCALE) && touchAction != TOUCH_FASTPLAY) {
                val target = scaleTargetView ?: return
                val targetW = target.width.takeIf { it > 0 }?.toFloat() ?: player.touchDelegate.screenConfig.xRange.toFloat()
                val targetH = target.height.takeIf { it > 0 }?.toFloat() ?: player.touchDelegate.screenConfig.yRange.toFloat()

                val targetScale = when {
                    currentScale in 0.97f..1.03f -> 1.0f
                    else -> currentScale.coerceIn(MIN_SCALE, MAX_SCALE)
                }

                currentScale = targetScale

                val (targetTransX, targetTransY) = if (targetScale == 1.0f) {
                    0f to 0f
                } else {
                    val maxTransX = if (targetScale >= 1.0f) {
                        ((targetW * (targetScale - 1f)) / 2f).coerceAtLeast(0f)
                    } else {
                        ((targetW * (1f - targetScale)) / 2f).coerceAtLeast(0f)
                    }
                    val maxTransY = if (targetScale >= 1.0f) {
                        ((targetH * (targetScale - 1f)) / 2f).coerceAtLeast(0f)
                    } else {
                        ((targetH * (1f - targetScale)) / 2f).coerceAtLeast(0f)
                    }
                    currentTransX.coerceIn(-maxTransX, maxTransX) to currentTransY.coerceIn(-maxTransY, maxTransY)
                }

                currentTransX = targetTransX
                currentTransY = targetTransY

                target.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationX(targetTransX)
                    .translationY(targetTransY)
                    .setDuration(150L)
                    .start()

                val percentage = (targetScale * 100).roundToInt()
                player.overlayDelegate.showInfo("$percentage%", 800)
                if (targetScale == 1.0f) {
                    player.window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    private val scaleGestureDetector by lazy(LazyThreadSafetyMode.NONE) {
        ScaleGestureDetector(player, mScaleListener).apply {
            ScaleGestureDetectorCompat.setQuickScaleEnabled(this, false)
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!player.isLocked && player.touchDelegate.touchAction != TOUCH_FASTPLAY) {
            scaleGestureDetector.onTouchEvent(event)
            if (scaleGestureDetector.isInProgress) {
                return true
            }
        }
        return false
    }

    fun onDoubleTap(): Boolean {
        if (!player.isLocked && isZoomed) {
            resetZoom(animate = true)
            player.window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            player.overlayDelegate.showInfo("100%", 800)
            return true
        }
        return false
    }

    fun resetZoom(animate: Boolean = false) {
        val target = player.videoLayout?.findViewById<FrameLayout>(R.id.player_surface_frame) ?: player.videoLayout
        currentScale = 1.0f
        currentTransX = 0f
        currentTransY = 0f
        target?.let {
            if (animate) {
                it.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(250L)
                    .start()
            } else {
                it.scaleX = 1.0f
                it.scaleY = 1.0f
                it.translationX = 0f
                it.translationY = 0f
            }
        }
    }

    fun updateZoomOnLayoutChange() {
        val target = player.videoLayout?.findViewById<FrameLayout>(R.id.player_surface_frame) ?: player.videoLayout ?: return
        target.post {
            if (target.width <= 0 || target.height <= 0) return@post
            target.pivotX = target.width / 2f
            target.pivotY = target.height / 2f

            if (isZoomed) {
                val targetW = target.width.toFloat()
                val targetH = target.height.toFloat()
                val maxTransX = if (currentScale >= 1.0f) {
                    ((targetW * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
                } else {
                    ((targetW * (1f - currentScale)) / 2f).coerceAtLeast(0f)
                }
                val maxTransY = if (currentScale >= 1.0f) {
                    ((targetH * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
                } else {
                    ((targetH * (1f - currentScale)) / 2f).coerceAtLeast(0f)
                }
                currentTransX = currentTransX.coerceIn(-maxTransX, maxTransX)
                currentTransY = currentTransY.coerceIn(-maxTransY, maxTransY)

                target.scaleX = currentScale
                target.scaleY = currentScale
                target.translationX = currentTransX
                target.translationY = currentTransY
            } else {
                target.scaleX = 1.0f
                target.scaleY = 1.0f
                target.translationX = 0f
                target.translationY = 0f
            }
        }
    }
}
