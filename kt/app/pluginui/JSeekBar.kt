package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.SeekBar

/**
 * TTS Server 的插件界面控件之一（原版是 Compose，这儿是 View 版，包名/API 与它一致）。
 * 插件用法：`seek.max = 100`、`seek.value`、`seek.setOnChangeListener{…}`；`setFloatType(n)` 表示值保留 n 位小数。
 */
class JSeekBar(context: Context, val hint: CharSequence) : FrameLayout(context) {

    interface OnSeekBarChangeListener {
        fun onStartTrackingTouch(seekBar: JSeekBar)
        fun onProgressChanged(seekBar: JSeekBar, progress: Int, fromUser: Boolean)
        fun onStopTrackingTouch(seekBar: JSeekBar)
    }

    private var mListener: OnSeekBarChangeListener? = null

    var max = 0
        set(value) {
            field = value
            bar.max = value
        }

    /** 小数位数（它 `setFloatType(n)` 就是这个意思：value = progress / 10^n）。 */
    private var n = 0

    private val bar = SeekBar(context)

    fun setOnChangeListener(listener: OnSeekBarChangeListener?) {
        mListener = listener
    }

    fun setFloatType(n: Int) {
        this.n = n
    }

    var value: Float
        get() = if (n <= 0) bar.progress.toFloat()
        else bar.progress.toFloat() / Math.pow(10.0, n.toDouble()).toFloat()
        set(v) {
            val p = if (n <= 0) v.toInt()
            else (v * Math.pow(10.0, n.toDouble())).toInt()
            bar.progress = p.coerceIn(0, if (max > 0) max else p)
        }

    init {
        bar.contentDescription = hint
        bar.max = if (max > 0) max else 100
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                try {
                    mListener?.onProgressChanged(this@JSeekBar, progress, fromUser)
                } catch (_: Throwable) {
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                try {
                    mListener?.onStartTrackingTouch(this@JSeekBar)
                } catch (_: Throwable) {
                }
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                try {
                    mListener?.onStopTrackingTouch(this@JSeekBar)
                } catch (_: Throwable) {
                }
            }
        })
    }
}
