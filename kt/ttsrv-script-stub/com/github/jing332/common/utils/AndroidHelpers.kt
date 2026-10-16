// 他们 lib-common 里那几个 Android 一行辅助（dp/toast/…）夹着一堆 androidx.fragment/activity，
// 我们只要这几个一行货，就照原样的签名补上 —— 插件语义一个都没动。
package com.github.jing332.common.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()

val Float.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()

fun Context.toast(msg: CharSequence) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}

fun Context.longToast(msg: CharSequence) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}

fun runOnUI(block: () -> Unit) { Handler(Looper.getMainLooper()).post(block) }
