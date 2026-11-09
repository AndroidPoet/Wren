/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * A pointer for a screen nobody can touch. The D-pad moves a cursor over the page; the centre
 * key presses where it points, as a finger would, so links, buttons, scrolling regions and
 * video controls all work without the page knowing it is on a TV. Holding the centre key is a
 * long press, which the page turns into its context menu. At an edge the cursor pushes the page
 * along instead of leaving the screen, and it fades after a few idle seconds.
 *
 * The view is transparent to touch and draws nothing but the cursor, so it can sit over the
 * whole browser; [target] names the view that receives the synthetic touches, which is the
 * page, or the fullscreen video when one is showing.
 */
class CursorLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {

  /** The view under the cursor that receives its presses: the page, or a fullscreen video. */
  var target: () -> View? = { null }

  /** Called when the cursor is pushed off the top of a page that is already at its top. */
  var onExitTop: (() -> Unit)? = null

  private var cursorX = 0f
  private var cursorY = 0f
  private var dirX = 0
  private var dirY = 0
  private var speed = 0f
  private var lastFrame = 0L
  private var visibleUntil = 0L
  private var pressDownTime = 0L
  private var pressing = false

  private val radius: Float get() = width / 100f
  private val maxSpeed: Float get() = width / 25f * FRAMES_PER_SECOND
  private val ringWidth: Float get() = radius / RING_FRACTION

  // A pale disc inside a white ring inside a dark ring: one of the two rings stands out on any page.
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
  private val lightRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.STROKE
  }
  private val darkRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(230, 20, 20, 20)
    style = Paint.Style.STROKE
  }

  init {
    isFocusable = true
    isFocusableInTouchMode = false
    isClickable = false
    setWillNotDraw(false)
  }

  /**
   * The activity hands the D-pad here whenever the page, and not the chrome, is what the user
   * is looking at. The page keeps view focus for itself, so a field it focuses still takes the
   * remote's letters; only the arrows and the centre key are the cursor's.
   */
  fun dispatchKey(event: KeyEvent): Boolean = when (event.action) {
    KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
    KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
    else -> false
  }

  /** Shows the cursor where it was, or at the centre of the screen the first time. */
  fun show() {
    if (cursorX == 0f && cursorY == 0f) {
      cursorX = width / 2f
      cursorY = height / 2f
    }
    cursorY = cursorY.coerceAtLeast(pageTop(target()))
    wake()
  }

  private val frame = object : Runnable {
    override fun run() {
      val now = SystemClock.uptimeMillis()
      val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
      lastFrame = now
      if (dirX != 0 || dirY != 0) {
        speed = min(maxSpeed, speed + maxSpeed * ACCELERATION * dt)
        move(dirX * speed * dt, dirY * speed * dt)
        if (pressing) sendTouch(MotionEvent.ACTION_MOVE)
        postOnAnimation(this)
      } else {
        speed = 0f
        lastFrame = 0L
      }
      invalidate()
    }
  }

  private fun move(dx: Float, dy: Float) {
    val page = target()
    var nx = cursorX + dx
    var ny = cursorY + dy
    // At an edge the page scrolls by what the cursor could not travel.
    var scrollX = 0
    var scrollY = 0
    if (nx < 0f) {
      scrollX = nx.toInt()
      nx = 0f
    }
    if (nx > width) {
      scrollX = (nx - width).toInt()
      nx = width.toFloat()
    }
    // The chrome above the page is the D-pad's own ground, so the page's top edge is the cursor's.
    val top = pageTop(page)
    if (ny < top) {
      scrollY = (ny - top).toInt()
      ny = top
    }
    if (ny > height) {
      scrollY = (ny - height).toInt()
      ny = height.toFloat()
    }
    if ((scrollX != 0 || scrollY != 0) && page != null && !pressing) {
      // A WebView does not clamp its own scroll offset, so each axis is moved only while the
      // page can still scroll that way, in small pieces that cannot overshoot its end by much.
      if (scrollY < 0 && !page.canScrollVertically(-1)) {
        dirX = 0
        dirY = 0
        // The address bar has the D-pad now, so the cursor goes at once instead of lingering,
        // parked just inside the page so that it is whole when it comes back.
        cursorX = nx
        cursorY = top + radius * 2
        visibleUntil = 0L
        invalidate()
        onExitTop?.invoke()
        return
      } else {
        nudge(page, 0, scrollY)
        nudge(page, scrollX, 0)
      }
    }
    cursorX = nx
    cursorY = ny
    wake()
  }

  private fun pageTop(page: View?): Float {
    if (page == null) return 0f
    val mine = IntArray(2).also(::getLocationInWindow)
    val theirs = IntArray(2).also(page::getLocationInWindow)
    return (theirs[1] - mine[1]).coerceAtLeast(0).toFloat()
  }

  private fun nudge(page: View, dx: Int, dy: Int) {
    var leftX = dx
    var leftY = dy
    while (leftX != 0 || leftY != 0) {
      val stepX = leftX.coerceIn(-NUDGE_PX, NUDGE_PX).takeIf { page.canScrollHorizontally(it) } ?: 0
      val stepY = leftY.coerceIn(-NUDGE_PX, NUDGE_PX).takeIf { page.canScrollVertically(it) } ?: 0
      if (stepX == 0 && stepY == 0) return
      page.scrollBy(stepX, stepY)
      leftX -= stepX
      leftY -= stepY
    }
  }

  private fun wake() {
    visibleUntil = SystemClock.uptimeMillis() + HIDE_AFTER_MS
    removeCallbacks(hide)
    postDelayed(hide, HIDE_AFTER_MS)
    invalidate()
  }

  private val hide = Runnable { invalidate() }

  override fun onFocusChanged(
    gainFocus: Boolean,
    direction: Int,
    previouslyFocusedRect: android.graphics.Rect?,
  ) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    if (gainFocus) {
      show()
    } else {
      dirX = 0
      dirY = 0
      if (pressing) sendTouch(MotionEvent.ACTION_CANCEL)
      pressing = false
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT -> dirX = -1
      KeyEvent.KEYCODE_DPAD_RIGHT -> dirX = 1
      KeyEvent.KEYCODE_DPAD_UP -> dirY = -1
      KeyEvent.KEYCODE_DPAD_DOWN -> dirY = 1
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
        if (event.repeatCount == 0 && !pressing) {
          if (cursorX == 0f && cursorY == 0f) show()
          pressing = true
          pressDownTime = SystemClock.uptimeMillis()
          sendTouch(MotionEvent.ACTION_DOWN)
          wake()
        }
        return true
      }
      else -> return super.onKeyDown(keyCode, event)
    }
    if (event.repeatCount == 0) {
      if (cursorX == 0f && cursorY == 0f) show()
      // A tap on the D-pad is a step; a held key accelerates from there in the frame loop.
      move(dirX * STEP_RADII * radius, dirY * STEP_RADII * radius)
      if (pressing) sendTouch(MotionEvent.ACTION_MOVE)
      removeCallbacks(frame)
      postOnAnimation(frame)
    }
    wake()
    return true
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> dirX = 0
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> dirY = 0
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
        if (pressing) {
          pressing = false
          sendTouch(MotionEvent.ACTION_UP)
        }
      }
      else -> return super.onKeyUp(keyCode, event)
    }
    return true
  }

  /**
   * Presses a point of the page, given in the page view's own pixels, the way a finger would.
   * The cursor goes there first, so what was pressed can be seen.
   */
  fun tapPage(x: Float, y: Float) {
    val page = target() ?: return
    val mine = IntArray(2).also(::getLocationInWindow)
    val theirs = IntArray(2).also(page::getLocationInWindow)
    cursorX = (x + theirs[0] - mine[0]).coerceIn(0f, width.toFloat())
    cursorY = (y + theirs[1] - mine[1]).coerceIn(pageTop(page), height.toFloat())
    wake()
    pressDownTime = SystemClock.uptimeMillis()
    sendTouch(MotionEvent.ACTION_DOWN)
    sendTouch(MotionEvent.ACTION_UP)
  }

  /** A finger at the cursor, in the target's own coordinates. */
  private fun sendTouch(action: Int) {
    val page = target() ?: return
    val mine = IntArray(2).also(::getLocationInWindow)
    val theirs = IntArray(2).also(page::getLocationInWindow)
    val x = cursorX + mine[0] - theirs[0]
    val y = cursorY + mine[1] - theirs[1]
    val now = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(pressDownTime, now, action, x, y, 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    page.dispatchTouchEvent(event)
    event.recycle()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (SystemClock.uptimeMillis() > visibleUntil) return
    lightRing.strokeWidth = ringWidth
    darkRing.strokeWidth = ringWidth
    canvas.drawCircle(cursorX, cursorY, radius - ringWidth, fill)
    canvas.drawCircle(cursorX, cursorY, radius - ringWidth, lightRing)
    canvas.drawCircle(cursorX, cursorY, radius, darkRing)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean = false

  override fun onDetachedFromWindow() {
    removeCallbacks(frame)
    removeCallbacks(hide)
    super.onDetachedFromWindow()
  }

  private companion object {
    const val FRAMES_PER_SECOND = 60f

    /** Full speed is reached after about half a second of holding a direction. */
    const val ACCELERATION = 2f
    const val HIDE_AFTER_MS = 5_000L

    /** How far one tap of the D-pad moves the cursor, in cursor radii. */
    const val STEP_RADII = 4f

    /** The largest single scroll a page is asked for; bounds any overshoot past its end. */
    const val NUDGE_PX = 8

    /** Each ring is this fraction of the radius thick. */
    const val RING_FRACTION = 6f
  }
}
