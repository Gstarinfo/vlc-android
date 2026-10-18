/*
 * ************************************************************************
 *  VideoPlayerSubtitleDelegate.kt
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

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewStub
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.ViewStubCompat
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import org.videolan.tools.KEY_SUBTITLES_DEFAULT_POSITION
import org.videolan.tools.KEY_SUBTITLES_DRAGGABLE
import org.videolan.tools.KEY_SUBTITLES_SURFACE_Y_OFFSET
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.R
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Modular delegate encapsulating all draggable subtitle positioning logic,
 * surface discovery, bounds calculation, overlay pill UI, detent snapping,
 * and preference persistence for VLC Enhanced.
 */
class VideoPlayerSubtitleDelegate(private val player: VideoPlayerActivity) {

    val isSubtitleDragEnabled: Boolean
        get() = player.settings.getBoolean(KEY_SUBTITLES_DRAGGABLE, true)

    var currentSubtitleUserOffsetRatio: Float = 0f
    val currentSubtitleUserOffset: Float
        get() {
            val bounds = getSubtitleBounds()
            return currentSubtitleUserOffsetRatio * bounds.availableUp
        }

    private var subtitleOffsetAnimator: ValueAnimator? = null

    // Subtitle Editor UI elements
    private var subtitleEditorRoot: View? = null
    private var subtitleEditorPill: View? = null
    private var subtitleEditorOffsetTextView: TextView? = null
    var isSubtitleEditorActive: Boolean = false
        private set

    // Touch and drag state
    private val handler = Handler(Looper.getMainLooper())
    private var initialDragY = 0f
    private var initialSubtitleOffset = 0f
    private var subtitleDragged = false
    private var wasNearZero = false
    private var touchDownMs = 0L

    val autoDismissSubtitleEditorRunnable = Runnable {
        if (isSubtitleEditorActive) {
            hideSubtitleEditor()
        }
    }

    private val subtitleDragRunnable = Runnable {
        if (isSubtitleDragEnabled && player.service != null) {
            isSubtitleEditorActive = true
            subtitleDragged = false
            wasNearZero = false
            player.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            player.overlayDelegate.hideOverlay(fromUser = true)
            initialSubtitleOffset = currentSubtitleUserOffset
            showSubtitleEditor(fromTouch = true)
        }
    }

    fun isSubtitleEditorVisible(): Boolean = subtitleEditorRoot?.isVisible == true

    fun hasSubtitles(): Boolean {
        val s = player.service ?: return false
        val track = s.spuTrack
        if (track.isNotEmpty() && track != "-1") return true
        val tracks = s.spuTracks
        return !tracks.isNullOrEmpty() && tracks.any { it.getId() != "-1" }
    }

    fun getSubtitleContainerHeight(): Float {
        val frame = player.videoLayout?.findViewById<FrameLayout>(R.id.player_surface_frame)
        val h = frame?.height?.toFloat()?.takeIf { it > 0 }
        return h ?: player.resources.displayMetrics.heightPixels.toFloat()
    }

    fun getSubtitleSurface(): View? {
        val frame = player.videoLayout?.findViewById<FrameLayout>(R.id.player_surface_frame) ?: return null
        val sub = frame.findViewById<View>(R.id.surface_subtitles)
        if (sub != null) return sub
        val stub = frame.findViewById<ViewStub>(R.id.subtitles_surface_stub)
        if (stub != null) {
            try {
                return stub.inflate()
            } catch (e: Throwable) {
                // Ignore if already inflated or parent removed
            }
        }
        return frame.findViewById<View>(R.id.surface_subtitles)
            ?: frame.children.firstOrNull { it.id != R.id.surface_video && it !is ViewStub }
    }

    data class SubtitleBounds(
        val containerHeight: Float,
        val untranslatedBottom: Float,
        val baseCropTranslation: Float,
        val maxTranslationUp: Float,
        val maxTranslationDown: Float,
        val availableUp: Float
    )

    fun getSubtitleBounds(): SubtitleBounds {
        val containerHeight = getSubtitleContainerHeight()
        val frame = player.videoLayout?.findViewById<FrameLayout>(R.id.player_surface_frame)
        val videoSurface = frame?.findViewById<View>(R.id.surface_video) ?: frame?.findViewById<View>(R.id.texture_video)
        val subtitleSurface = getSubtitleSurface()

        val subHeight = subtitleSurface?.height?.takeIf { it > 0 }?.toFloat()
            ?: videoSurface?.height?.takeIf { it > 0 }?.toFloat()
            ?: videoSurface?.layoutParams?.height?.takeIf { it > 0 }?.toFloat()
            ?: containerHeight

        val untranslatedBottom = if (subtitleSurface != null && subtitleSurface.bottom > 0) {
            subtitleSurface.bottom.toFloat()
        } else if (videoSurface != null && videoSurface.bottom > 0) {
            videoSurface.bottom.toFloat()
        } else {
            (containerHeight + subHeight) / 2f
        }

        val minTopMargin = 100.dp.toFloat().coerceAtLeast(containerHeight * 0.12f)
        val bottomMargin = 16.dp.toFloat()

        val maxTranslationDown = containerHeight - bottomMargin - untranslatedBottom
        val maxTranslationUp = minTopMargin - untranslatedBottom

        val baseCropTranslation = if (untranslatedBottom > containerHeight - bottomMargin) {
            containerHeight - bottomMargin - untranslatedBottom
        } else {
            0f
        }

        val availableUp = (baseCropTranslation - maxTranslationUp).coerceAtLeast(1f)

        return SubtitleBounds(
            containerHeight = containerHeight,
            untranslatedBottom = untranslatedBottom,
            baseCropTranslation = baseCropTranslation,
            maxTranslationUp = maxTranslationUp,
            maxTranslationDown = maxTranslationDown,
            availableUp = availableUp
        )
    }

    fun updateSubtitlePosition() {
        val subtitleSurface = getSubtitleSurface() ?: return
        val bounds = getSubtitleBounds()

        val targetTranslationY = (bounds.baseCropTranslation + currentSubtitleUserOffsetRatio * bounds.availableUp)
            .coerceIn(bounds.maxTranslationUp, bounds.maxTranslationDown)
        subtitleSurface.translationY = targetTranslationY

        if (isSubtitleEditorVisible()) {
            updateSubtitleEditor(
                currentSubtitleUserOffset.roundToInt(),
                currentSubtitleUserOffsetRatio,
                isSnapped = currentSubtitleUserOffsetRatio.absoluteValue < 0.015f
            )
        }
    }

    fun setSubtitleUserOffset(offset: Float) {
        val subtitleSurface = getSubtitleSurface() ?: return
        val bounds = getSubtitleBounds()

        val targetTranslationY = (bounds.baseCropTranslation + offset)
            .coerceIn(bounds.maxTranslationUp, bounds.maxTranslationDown)
        subtitleSurface.translationY = targetTranslationY

        currentSubtitleUserOffsetRatio = if (bounds.availableUp > 0f) {
            (targetTranslationY - bounds.baseCropTranslation) / bounds.availableUp
        } else {
            0f
        }
    }

    fun setSubtitleOffsetRatio(targetRatio: Float, animated: Boolean = true, onEnd: (() -> Unit)? = null) {
        val clampedRatio = targetRatio.coerceIn(-1.0f, 0.25f)
        if (animated) {
            subtitleOffsetAnimator?.cancel()
            val startRatio = currentSubtitleUserOffsetRatio
            subtitleOffsetAnimator = ValueAnimator.ofFloat(startRatio, clampedRatio).apply {
                duration = 250L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    currentSubtitleUserOffsetRatio = anim.animatedValue as Float
                    updateSubtitlePosition()
                    updateSubtitleEditor(
                        currentSubtitleUserOffset.roundToInt(),
                        currentSubtitleUserOffsetRatio,
                        isSnapped = currentSubtitleUserOffsetRatio.absoluteValue < 0.015f
                    )
                }
                doOnEnd {
                    saveSubtitleUserOffset()
                    onEnd?.invoke()
                }
                start()
            }
        } else {
            currentSubtitleUserOffsetRatio = clampedRatio
            updateSubtitlePosition()
            saveSubtitleUserOffset()
            updateSubtitleEditor(
                currentSubtitleUserOffset.roundToInt(),
                currentSubtitleUserOffsetRatio,
                isSnapped = currentSubtitleUserOffsetRatio.absoluteValue < 0.015f
            )
            onEnd?.invoke()
        }
    }

    fun saveSubtitleUserOffset() {
        if (isSubtitleDragEnabled) {
            player.settings.putSingle(KEY_SUBTITLES_SURFACE_Y_OFFSET, currentSubtitleUserOffsetRatio)
        }
    }

    fun loadSubtitleUserOffset() {
        val defaultOffsetRatio = getDefaultSubtitleOffsetRatio()
        currentSubtitleUserOffsetRatio = if (isSubtitleDragEnabled && player.settings.contains(KEY_SUBTITLES_SURFACE_Y_OFFSET)) {
            val savedSubtitleOffset = player.settings.getFloat(KEY_SUBTITLES_SURFACE_Y_OFFSET, defaultOffsetRatio)
            if (savedSubtitleOffset.absoluteValue > 1.5f) {
                savedSubtitleOffset / 1080f
            } else {
                savedSubtitleOffset
            }.coerceIn(-1.0f, 0.25f)
        } else {
            defaultOffsetRatio
        }
    }

    fun getDefaultSubtitleOffsetRatio(): Float {
        val defaultPosition = player.settings.getString(KEY_SUBTITLES_DEFAULT_POSITION, "bottom") ?: "bottom"
        return when (defaultPosition) {
            "top" -> -0.75f
            "center" -> -0.37f
            else -> 0f
        }
    }

    fun resetSubtitleUserOffset(animated: Boolean = true) {
        val defaultRatio = getDefaultSubtitleOffsetRatio()
        setSubtitleOffsetRatio(defaultRatio, animated) {
            player.settings.edit().remove(KEY_SUBTITLES_SURFACE_Y_OFFSET).apply()
            player.overlayDelegate.showInfo(player.getString(R.string.subtitles_position_reset), 1000)
        }
    }

    // UI Overlay management
    fun initSubtitleEditorOverlay() {
        if (subtitleEditorRoot != null) return
        val vsc = player.findViewById<ViewStubCompat>(R.id.player_subtitle_editor_stub)
        if (vsc != null) {
            vsc.setVisible()
            subtitleEditorRoot = player.findViewById(R.id.subtitle_editor_root)
            subtitleEditorPill = player.findViewById(R.id.subtitle_editor_pill)
            subtitleEditorOffsetTextView = player.findViewById(R.id.subtitle_editor_offset_text)

            player.findViewById<View>(R.id.subtitle_btn_up)?.setOnClickListener {
                val containerH = getSubtitleContainerHeight()
                val stepRatio = if (containerH > 0) 10f / containerH else 0.01f
                setSubtitleOffsetRatio(currentSubtitleUserOffsetRatio - stepRatio, animated = false)
            }

            player.findViewById<View>(R.id.subtitle_btn_down)?.setOnClickListener {
                val containerH = getSubtitleContainerHeight()
                val stepRatio = if (containerH > 0) 10f / containerH else 0.01f
                setSubtitleOffsetRatio(currentSubtitleUserOffsetRatio + stepRatio, animated = false)
            }

            player.findViewById<View>(R.id.subtitle_btn_reset)?.setOnClickListener {
                resetSubtitleUserOffset(animated = true)
            }

            player.findViewById<View>(R.id.subtitle_btn_done)?.setOnClickListener {
                hideSubtitleEditor()
            }

            player.findViewById<View>(R.id.chip_preset_bottom)?.setOnClickListener {
                setSubtitleOffsetRatio(0f, animated = true)
            }

            player.findViewById<View>(R.id.chip_preset_middle)?.setOnClickListener {
                setSubtitleOffsetRatio(-0.35f, animated = true)
            }

            player.findViewById<View>(R.id.chip_preset_top)?.setOnClickListener {
                setSubtitleOffsetRatio(-0.70f, animated = true)
            }
        }
    }

    fun showSubtitleEditor(fromTouch: Boolean = false) {
        if (player.isInPictureInPictureMode || !isSubtitleDragEnabled) return
        initSubtitleEditorOverlay()
        isSubtitleEditorActive = true
        player.overlayDelegate.hideOverlay(true)
        player.overlayDelegate.hideInfo()

        subtitleEditorRoot?.setVisible()
        subtitleEditorPill?.setVisible()

        updateSubtitleEditor(
            currentSubtitleUserOffset.roundToInt(),
            currentSubtitleUserOffsetRatio,
            isSnapped = currentSubtitleUserOffsetRatio.absoluteValue < 0.015f
        )
    }

    fun updateSubtitleEditor(offsetPx: Int, ratio: Float, isSnapped: Boolean) {
        val offsetTv = subtitleEditorOffsetTextView ?: return
        val offsetDisplay = -offsetPx
        offsetTv.text = when {
            isSnapped || offsetPx == 0 -> player.getString(R.string.subtitles_position_default)
            offsetDisplay > 0 -> "+$offsetDisplay px"
            else -> "$offsetDisplay px"
        }
        offsetTv.setTextColor(if (isSnapped || offsetPx == 0) ContextCompat.getColor(player, R.color.orange500) else Color.WHITE)
    }

    fun hideSubtitleEditor() {
        isSubtitleEditorActive = false
        subtitleEditorRoot?.setGone()
        saveSubtitleUserOffset()
    }

    // Touch and drag handling
    fun isTouchInsideSubtitlePill(x: Float, y: Float): Boolean {
        val pill = subtitleEditorPill ?: player.findViewById<View>(R.id.subtitle_editor_pill) ?: return false
        if (!pill.isVisible) return false
        val location = IntArray(2)
        pill.getLocationOnScreen(location)
        val pillX = location[0].toFloat()
        val pillY = location[1].toFloat()
        return x >= pillX && x <= pillX + pill.width && y >= pillY && y <= pillY + pill.height
    }

    fun isNearSubtitleRegion(y: Float): Boolean {
        val screenHeight = player.resources.displayMetrics.heightPixels.toFloat()
        val bounds = getSubtitleBounds()
        val currentSubY = bounds.untranslatedBottom + currentSubtitleUserOffset

        val diffFromSub = (y - currentSubY).absoluteValue
        return diffFromSub <= 160.dp || y >= screenHeight * 0.48f
    }

    fun onTouchDown(event: MotionEvent, isInAllowedBounds: Boolean): Boolean {
        handler.removeCallbacks(subtitleDragRunnable)
        handler.removeCallbacks(autoDismissSubtitleEditorRunnable)

        val initTouchX = event.x
        val initTouchY = event.y
        touchDownMs = System.currentTimeMillis()

        if (isSubtitleDragEnabled && isSubtitleEditorActive && !isTouchInsideSubtitlePill(initTouchX, initTouchY)) {
            subtitleDragged = false
            wasNearZero = false
            initialDragY = initTouchY
            initialSubtitleOffset = currentSubtitleUserOffset
            return true
        }

        val isNearSubtitles = isSubtitleDragEnabled && isNearSubtitleRegion(initTouchY) && (hasSubtitles() || isSubtitleEditorActive)
        if (isNearSubtitles && isInAllowedBounds) {
            handler.postDelayed(subtitleDragRunnable, 350L)
        }
        return false
    }

    fun onTouchMove(event: MotionEvent): Boolean {
        val dragDeltaY = event.y - initialDragY
        if (dragDeltaY.absoluteValue > 3f) {
            subtitleDragged = true
        }
        val rawNewOffset = initialSubtitleOffset + dragDeltaY
        val isNearDetent = rawNewOffset.absoluteValue <= 18f
        val finalOffset = if (isNearDetent) 0f else rawNewOffset

        if (isNearDetent && !wasNearZero) {
            player.window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            wasNearZero = true
        } else if (!isNearDetent) {
            wasNearZero = false
        }

        setSubtitleUserOffset(finalOffset)
        updateSubtitleEditor(
            finalOffset.roundToInt(),
            currentSubtitleUserOffsetRatio,
            isSnapped = isNearDetent || finalOffset == 0f
        )
        return true
    }

    fun onTouchUp(event: MotionEvent): Boolean {
        handler.removeCallbacks(subtitleDragRunnable)
        val now = System.currentTimeMillis()

        if (subtitleDragged) {
            saveSubtitleUserOffset()
            handler.removeCallbacks(autoDismissSubtitleEditorRunnable)
            handler.postDelayed(autoDismissSubtitleEditorRunnable, 3000L)
        } else if (isSubtitleEditorActive) {
            if (now - touchDownMs < ViewConfiguration.getLongPressTimeout()) {
                hideSubtitleEditor()
            } else {
                handler.removeCallbacks(autoDismissSubtitleEditorRunnable)
                handler.postDelayed(autoDismissSubtitleEditorRunnable, 4000L)
            }
        }
        return true
    }

    fun cancelPendingTouch() {
        handler.removeCallbacks(subtitleDragRunnable)
    }
}
