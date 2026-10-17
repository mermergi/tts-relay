package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout

/**
 * TTS Server 的插件界面控件之一。
 *
 * 它原版是 Compose 写的（`ComposeView` + `OutlinedTextField`）。那要为它拉进整个
 * lib-compose（44 文件 4058 行）+ coil + markwon + accompanist + composeBom + floatingx…，
 * 我们这是个手搓打包的 8MB APK，不值当。所以这儿用 <b>View 版</b>顶上，
 * 但**包名、类名、成员名和它一模一样** —— 插件的 `onLoadUI` 代码（`JTextInput(ctx, "…")`、
 * `.text.set(…)`、`.addTextChangedListener(…)`、`.setVisibility(…)`）一个字都不用改。
 */
class JTextInput(context: Context, val hint: CharSequence? = null) : FrameLayout(context) {

    interface OnTextChangedListener {
        fun onChanged(text: CharSequence)
    }

    private val listeners = mutableSetOf<OnTextChangedListener>()
    private val input = EditText(context)

    /** 插件里是 `editor.text.set("x")` / `editor.text.toString()`，所以得给这几个方法。 */
    inner class JEditable {
        fun set(s: CharSequence?) {
            input.setText(s ?: "")
        }

        fun get(): CharSequence = input.text ?: ""

        fun length(): Int = input.text?.length ?: 0

        override fun toString(): String = input.text?.toString() ?: ""
    }

    val text: JEditable by lazy { JEditable() }

    var maxLines: Int
        get() = input.maxLines
        set(value) {
            input.maxLines = value
            input.setSingleLine(value == 1)
        }

    /** 上游用的是带浮动 label 的 OutlinedTextField；这里就把 hint 画成上面那行小字标签。 */
    private val label = android.widget.TextView(context)

    init {
        input.hint = ""
        input.setSingleLine(false)
        if (hint != null && hint.length > 0) {
            label.text = hint
            label.textSize = 12.5f
            label.setTextColor(0xFF9AA0B4.toInt())
            label.setPadding(0, dp(8), 0, dp(2))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(20)
            addView(input, lp)
        } else {
            addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val t = s ?: ""
                for (l in ArrayList(listeners)) {
                    try {
                        l.onChanged(t)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) { }
        })
    }

    fun addTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    fun removeTextChangedListener(listener: OnTextChangedListener) {
        listeners.remove(listener)
    }

    fun setOnTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    private fun dp(v: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
