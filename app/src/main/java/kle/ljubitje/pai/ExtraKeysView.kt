package kle.ljubitje.pai

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

/**
 * A horizontal bar of extra keys (Esc, Ctrl, Tab, arrows, etc.) for terminal use.
 */
class ExtraKeysView(
    context: Context,
    private val terminalView: TerminalView
) : HorizontalScrollView(context) {

    private var ctrlActive = false
    private var ctrlButton: Button? = null

    init {
        setBackgroundColor(Color.parseColor("#1a1a1a"))
        isHorizontalScrollBarEnabled = false

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(4)
            setPadding(pad, dp(2), pad, dp(2))
        }

        // Key definitions: label, action
        val keys = listOf(
            KeyDef("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) },
            KeyDef("CTRL") { toggleCtrl() },
            KeyDef("TAB") { sendKey(KeyEvent.KEYCODE_TAB) },
            KeyDef("|") { sendText("|") },
            KeyDef("-") { sendText("-") },
            KeyDef("/") { sendText("/") },
            KeyDef("~") { sendText("~") },
            KeyDef("\u2190") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },  // ←
            KeyDef("\u2191") { sendKey(KeyEvent.KEYCODE_DPAD_UP) },    // ↑
            KeyDef("\u2193") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },  // ↓
            KeyDef("\u2192") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }, // →
        )

        for (keyDef in keys) {
            val btn = createButton(keyDef.label, keyDef.action)
            if (keyDef.label == "CTRL") ctrlButton = btn
            row.addView(btn)
        }

        addView(row)
    }

    private fun createButton(label: String, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            setTextColor(Color.parseColor("#cccccc"))
            setBackgroundColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isAllCaps = false
            gravity = Gravity.CENTER

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            layoutParams = params

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
                // Return focus to terminal for continued typing
                terminalView.requestFocus()
            }
        }
    }

    private fun sendKey(keyCode: Int) {
        var keyMod = 0
        if (ctrlActive) {
            keyMod = keyMod or KeyHandler.KEYMOD_CTRL
            deactivateCtrl()
        }
        terminalView.handleKeyCode(keyCode, keyMod)
    }

    private fun sendText(text: String) {
        val session = terminalView.currentSession ?: return
        if (ctrlActive) {
            for (ch in text) {
                val code = ch.code
                if (code in 64..127) {
                    session.write(String(charArrayOf((code - 64).toChar())))
                } else {
                    session.write(text)
                }
            }
            deactivateCtrl()
        } else {
            session.write(text)
        }
    }

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        ctrlButton?.let {
            if (ctrlActive) {
                it.setBackgroundColor(Color.parseColor("#0078d4"))
                it.setTextColor(Color.WHITE)
            } else {
                it.setBackgroundColor(Color.parseColor("#333333"))
                it.setTextColor(Color.parseColor("#cccccc"))
            }
        }
    }

    private fun deactivateCtrl() {
        ctrlActive = false
        ctrlButton?.let {
            it.setBackgroundColor(Color.parseColor("#333333"))
            it.setTextColor(Color.parseColor("#cccccc"))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private data class KeyDef(val label: String, val action: () -> Unit)
}
