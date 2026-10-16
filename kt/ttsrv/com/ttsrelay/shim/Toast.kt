// ToastUtils.kt 里那两个 Fragment 扩展要 androidx.lifecycle 的一整套（KMP 之后 jar 名全变了），
// 我们只用得到 Context 上那两个 —— 照它们原样（Toast.makeText(...).show()）补上，插件语义无关。
package com.github.jing332.common.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

fun Context.toast(msg: CharSequence) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}

fun Context.longToast(msg: CharSequence) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
