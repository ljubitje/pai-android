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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#2a2a2a"))
                cornerRadius = dp(8).toFloat()
                setStroke(1, Color.parseColor("#444444"))
            })
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minHeight = dp(36)
            minimumHeight = dp(36)
            maxLines = 4
            // Allow multiline input — Enter = newline, not submit
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            isSingleLine = false
        }
        inputRow.addView(inputField, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(4)
        })

        val sendBtn = createButton("\u25B6") {  // ▶
            val text = inputField.text.toString()
            if (text.isNotEmpty()) {
                val session = terminalView.currentSession ?: return@createButton
                session.write(text + "\n")
                inputField.text.clear()
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
            setTextColor(Color.parseColor("#cccccc"))
            setBackgroundColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isAllCaps = false
            gravity = Gravity.CENTER

            val params = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
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
