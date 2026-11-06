package com.codebox.installer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.hypot

class ScreenView(context: Context, private val vnc: VncClient) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }
    private var ox = 0f
    private var oy = 0f
    private var scale = 1f
    var ctrl = false
    var onCtrlReset: (() -> Unit)? = null

    private var state = 3
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var acc = 0f
    private var comp = ""

    private val longPress = Runnable {
        if (state == 0) {
            ptr(0, downX, downY); ptr(4, downX, downY); ptr(0, downX, downY)
            state = 3
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val bm = vnc.bitmap ?: return
        synchronized(vnc.lock) {
            val s = minOf(width.toFloat() / bm.width, height.toFloat() / bm.height)
            val dw = bm.width * s
            val dh = bm.height * s
            ox = (width - dw) / 2
            oy = (height - dh) / 2
            scale = s
            canvas.drawBitmap(bm, null, RectF(ox, oy, ox + dw, oy + dh), paint)
        }
    }

    private fun ptr(mask: Int, vx: Float, vy: Float) {
        val bm = vnc.bitmap ?: return
        if (scale <= 0f) return
        val x = ((vx - ox) / scale).toInt().coerceIn(0, bm.width - 1)
        val y = ((vy - oy) / scale).toInt().coerceIn(0, bm.height - 1)
        vnc.pointer(mask, x, y)
    }

    private fun avgY(e: MotionEvent): Float {
        var t = 0f
        for (i in 0 until e.pointerCount) t += e.getY(i)
        return t / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vnc.bitmap == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; state = 0
                postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (state == 1) ptr(0, e.x, e.y)
                state = 2
                lastY = avgY(e)
                acc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == 2) {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        acc += y - lastY
                        lastY = y
                        while (acc > 40f) { ptr(8, e.x, e.y); ptr(0, e.x, e.y); acc -= 40f }
                        while (acc < -40f) { ptr(16, e.x, e.y); ptr(0, e.x, e.y); acc += 40f }
                    }
                } else if (state == 0) {
                    if (hypot(e.x - downX, e.y - downY) > 25f) {
                        removeCallbacks(longPress)
                        state = 1
                        ptr(1, downX, downY)
                        ptr(1, e.x, e.y)
                    }
                } else if (state == 1) {
                    ptr(1, e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (state == 0) {
                    ptr(0, e.x, e.y); ptr(1, e.x, e.y); ptr(0, e.x, e.y)
                } else if (state == 1) {
                    ptr(0, e.x, e.y)
                }
                state = 3
                postDelayed({ vnc.refresh() }, 250)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (state == 1) ptr(0, e.x, e.y)
                state = 3
            }
        }
        return true
    }

    fun sendKey(sym: Int) {
        val c = ctrl
        if (c) vnc.key(0xffe3, true)
        vnc.key(sym, true)
        vnc.key(sym, false)
        if (c) {
            vnc.key(0xffe3, false)
            ctrl = false
            onCtrlReset?.invoke()
        }
    }

    private fun sendChar(c: Char) {
        val code = c.code
        val sym = when {
            c == '\n' -> 0xff0d
            c == '\t' -> 0xff09
            code in 32..126 -> code
            code in 160..255 -> code
            else -> 0x01000000 or code
        }
        sendKey(sym)
    }

    fun pasteText(t: String) {
        for (ch in t) if (ch != '\r') sendChar(ch)
    }

    private fun keycodeToSym(k: Int): Int = when (k) {
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        else -> 0
    }

    private fun handleKey(ev: KeyEvent): Boolean {
        if (ev.isCtrlPressed) ctrl = true
        val sym = keycodeToSym(ev.keyCode)
        if (sym != 0) { sendKey(sym); return true }
        val u = ev.unicodeChar
        if (u != 0) { sendChar(u.toChar()); return true }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        if (event != null && handleKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun setComp(n: String) {
        var i = 0
        while (i < comp.length && i < n.length && comp[i] == n[i]) i++
        repeat(comp.length - i) { sendKey(0xff08) }
        for (k in i until n.length) sendChar(n[k])
        comp = n
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                setComp(text?.toString() ?: "")
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                setComp(text?.toString() ?: "")
                comp = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                comp = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                comp = ""
                repeat(beforeLength) { sendKey(0xff08) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) handleKey(event)
                return true
            }
        }
    }

    private fun imm() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    fun showKb() {
        requestFocus()
        imm().showSoftInput(this, 0)
    }

    fun hideKb() {
        imm().hideSoftInputFromWindow(windowToken, 0)
    }
}
