package kle.ljubitje.pai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.termux.view.TerminalView

/**
 * Input bar with arrow keys, a multiline text field, and a send button.
 * Enter in the text field = newline. Send button = submit to terminal.
 */
class ExtraKeysView(
    context: Context,
    private val terminalView: TerminalView
) : LinearLayout(context) {

    private val inputField: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1a1a1a"))
        val pad = dp(4)
        setPadding(pad, dp(2), pad, dp(2))

        // Row 1: arrow keys
        val arrowRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }

        val arrows = listOf(
            "\u2190" to KeyEvent.KEYCODE_DPAD_LEFT,   // ←
            "\u2193" to KeyEvent.KEYCODE_DPAD_DOWN,    // ↓
            "\u2191" to KeyEvent.KEYCODE_DPAD_UP,      // ↑
            "\u2192" to KeyEvent.KEYCODE_DPAD_RIGHT,   // →
        )
        for ((label, keyCode) in arrows) {
            arrowRow.addView(createButton(label) { sendKey(keyCode) })
        }
        addView(arrowRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Row 2: text input + send button
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        inputField = EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#666666"))
            hint = "Type here..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#2a2a2a"))
                cornerRadius = dp(10).toFloat()
                setStroke(1, Color.parseColor("#555555"))
            })
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(42)
            minimumHeight = dp(42)
            maxLines = 4
            // Allow multiline input — Enter = newline, not submit
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            isSingleLine = false

            // When field is empty, Enter sends newline to terminal (accept defaults)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    if (text.isNullOrEmpty()) {
                        val session = terminalView.currentSession ?: return@setOnKeyListener false
                        session.write("\n")
                        true
                    } else false
                } else false
            }
        }
        inputRow.addView(inputField, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        })

        val sendBtn = createButton("\u25B6") {  // ▶
            val session = terminalView.currentSession ?: return@createButton
            val text = inputField.text.toString()
            if (text.isNotEmpty()) {
                session.write(text + "\n")
                inputField.text.clear()
            } else {
                session.write("\n")
            }
        }
        inputRow.addView(sendBtn)

        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Focus the input field and show keyboard */
    fun focusInput() {
        inputField.requestFocus()
    }

    private fun createButton(label: String, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            setTextColor(Color.parseColor("#e0e0e0"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#383838"))
                cornerRadius = dp(8).toFloat()
                setStroke(1, Color.parseColor("#4a4a4a"))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            minWidth = dp(50)
            minimumWidth = dp(50)
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isAllCaps = false
            gravity = Gravity.CENTER
            stateListAnimator = null  // remove default elevation/shadow

            val params = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            layoutParams = params

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }
    }

    private fun sendKey(keyCode: Int) {
        terminalView.handleKeyCode(keyCode, 0)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
