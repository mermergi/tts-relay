package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.util.TypedValue

/**
 * TTS Server 的插件界面控件之一（原版是 Compose，这儿是 View 版，包名/API 与它一致）。
 * 插件用法：`spinner.items = [Item("名字", "值")]`、`spinner.value = "值"`、`spinner.setOnItemSelected{…}`
 */
class JSpinner(context: Context, val hint: CharSequence) : FrameLayout(context) {

    interface OnItemSelectedListener {
        fun onItemSelected(spinner: JSpinner, position: Int, item: Item)
    }

    private var mListener: OnItemSelectedListener? = null
    private val bar = Spinner(context)
    private var mItems: List<Item> = emptyList()
    private var mSelectedPosition = 0

    var items: List<Item>
        get() = mItems
        set(value) {
            mItems = value
            // 不用系统的 simple_spinner_*_item：那套字号/内边距是按列表页设计的，
            // 塞进这种窄条里会变成"一行占半屏"。自己给个紧凑的 TextView 当行。
            bar.adapter = object : ArrayAdapter<String>(context, 0, value.map { it.name.toString() }) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                    row(position, convertView)

                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                    row(position, convertView)

                private fun row(position: Int, convertView: android.view.View?): android.view.View {
                    val t = (convertView as? android.widget.TextView) ?: android.widget.TextView(context)
                    t.text = getItem(position)
                    t.textSize = 14f
                    t.setPadding(dp(14), dp(11), dp(14), dp(11))
                    t.setTextColor(0xFFE9ECF6.toInt())
                    return t
                }
            }
            val pos = if (mSelectedPosition in value.indices) mSelectedPosition else 0
            mSelectedPosition = pos
            bar.setSelection(pos, false)
        }

    var selectedPosition: Int
        get() = mSelectedPosition
        set(value) {
            mSelectedPosition = value
            if (value in mItems.indices) bar.setSelection(value, false)
        }

    /** 上游的 getter 返回的就是选中的那个 Item（不是它的 value）；插件里有写 `spinner.value.value` 的。 */
    var value: Any
        get() = mItems.getOrNull(mSelectedPosition) ?: Item("", "")
        set(v) {
            val i = mItems.indexOfFirst { it.value == v }
            if (i >= 0) selectedPosition = i
        }

    /** 上游 AppSpinner 上面有 labelText；这里画成一行小字标签。 */
    private val label = android.widget.TextView(context)

    init {
        label.text = hint
        label.textSize = 12.5f
        label.setTextColor(0xFF9AA0B4.toInt())
        label.setPadding(0, dp(8), 0, dp(2))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(20)
        addView(bar, lp)
        bar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                mSelectedPosition = position
                val item = mItems.getOrNull(position) ?: return
                try {
                    mListener?.onItemSelected(this@JSpinner, position, item)
                } catch (_: Throwable) {
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
    }

    fun setOnItemSelected(listener: OnItemSelectedListener?) {
        mListener = listener
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
