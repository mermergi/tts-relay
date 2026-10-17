package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * TTS Server 插件界面控件（`JSeekBar`）的 View 版 —— 把它 **LabelSlider** 那套原样搬过来：
 *
 * <pre>
 *   温度(Temperature): 0.7        ← hint + 当前值（小数位由 setFloatType 决定）
 *   [−]  ●──────────────  [+]
 * </pre>
 *
 * 包名/类名/成员名和上游完全一致（插件脚本里 `new JSeekBar(ctx, "温度…")` 就是这么写的）；
 * 上游用 Compose 的 LabelSlider，我们这边没有 Compose，就用 View 拼出同样的东西：
 * 标签行、两端 −/+ 按钮、可拖动滑杆，value / max / setFloatType / 回调逐个对齐。
 */
@SuppressLint("ViewConstructor")
class JSeekBar(context: Context, val hint: CharSequence) : FrameLayout(context) {

    interface OnSeekBarChangeListener {
        fun onStartTrackingTouch(seekBar: JSeekBar)
        fun onProgressChanged(seekBar: JSeekBar, progress: Int, fromUser: Boolean)
        fun onStopTrackingTouch(seekBar: JSeekBar)
    }

    private var mListener: OnSeekBarChangeListener? = null

    fun setOnChangeListener(listener: OnSeekBarChangeListener?) {
        mListener = listener
    }

    /** 上游是 `@JvmField var max`：脚本直接赋值，别改成带 setter 的属性。 */
    @JvmField
    var max = 0

    /** 小数位数：value = mValue / x */
    private var n = 0
    private var x = 1f
    fun setFloatType(n: Int) {
        this.n = n
        x = 1f
        for (i in 1..n) x *= 10f
    }

    private var mValue = 0f

    var value: Float
        get() = mValue / x
        set(v) {
            mValue = v * x
            bar.progress = mValue.toInt().coerceIn(0, if (max > 0) max else Int.MAX_VALUE)
            updateLabel()
            try { mListener?.onProgressChanged(this, value.toInt(), false) } catch (_: Throwable) { }
        }

    private val label = TextView(context)
    private val bar = SeekBar(context)

    init {
        label.textSize = 13.5f
        label.setTextColor(TEXT)
        label.setPadding(0, dp(8), 0, dp(2))
        updateLabel()
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        row.addView(step("−", -1), LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.max = if (max > 0) max else 100
        row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(step("+", 1), LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                mValue = progress.toFloat()
                updateLabel()
                try {
                    mListener?.onProgressChanged(this@JSeekBar, value.toInt(), fromUser)
                } catch (_: Throwable) {
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                try { mListener?.onStartTrackingTouch(this@JSeekBar) } catch (_: Throwable) { }
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                try { mListener?.onStopTrackingTouch(this@JSeekBar) } catch (_: Throwable) { }
            }
        })
    }

    /** 两端那个 − / ＋ 按钮（上游 buttonSteps=1、长按 10 步）。 */
    private fun step(text: String, dir: Int): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextColor(MUTED)
        t.isClickable = true
        val one = { steps: Int ->
            val hi = if (max > 0) max.toFloat() else Float.MAX_VALUE
            mValue = (mValue + dir * steps).coerceIn(0f, hi)
            bar.progress = mValue.toInt()
            updateLabel()
            try {
                mListener?.onProgressChanged(this, value.toInt(), true)
                mListener?.onStopTrackingTouch(this)
            } catch (_: Throwable) {
            }
        }
        t.setOnClickListener { one(1) }
        t.setOnLongClickListener { one(10); true }
        return t
    }

    /** 上游就是 `text = hint.toString() + value.toScale(n)`。 */
    private fun updateLabel() {
        label.text = hint.toString() + String.format(java.util.Locale.US, "%.${n}f", mValue / x)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val TEXT = 0xFFE9ECF6.toInt()
        private const val MUTED = 0xFF9AA0B4.toInt()
    }
}
