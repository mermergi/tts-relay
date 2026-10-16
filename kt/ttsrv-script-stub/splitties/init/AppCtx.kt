// 只为替掉 splitties-appctx 那个包：它就是个"全局 Context 取值器"。
// 真实现（splitties-init 的 ContentProvider）我们在自己的 Application 里手动塞。
package splitties.init

import android.content.Context

internal object AppCtxHolder {
    @Volatile var context: Context? = null
}

val appCtx: Context
    get() = AppCtxHolder.context
        ?: throw IllegalStateException("appCtx 还没装 —— 启动时调 splitties.init.AppCtxHolder 不行，改调 installAppCtx(ctx)")

fun installAppCtx(ctx: Context) { AppCtxHolder.context = ctx.applicationContext }
