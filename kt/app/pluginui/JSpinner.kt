package com.github.jing332.tts.speech.plugin.engine.type.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner

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
            bar.adapter = ArrayAdapter(context,
                android.R.layout.simple_spinner_dropdown_item, value.map { it.name.toString() })
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

    var value: Any
        get() = mItems.getOrNull(mSelectedPosition)?.value ?: ""
        set(v) {
            val i = mItems.indexOfFirst { it.value == v }
            if (i >= 0) selectedPosition = i
        }

    init {
        bar.contentDescription = hint
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
}
