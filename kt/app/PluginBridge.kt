package com.mermergi.ttsrelay.js

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import kotlinx.coroutines.runBlocking

/**
 * 这一层只是<b>接线</b>：把我们的引擎配置翻译成 TTS Server 的实体，
 * 然后调它自己的 [TtsPluginUiEngineV2]。
 *
 * 插件协议（`getAudio` / `getAudioV2(request, callback)` / `EditorJS.getVoices` /
 * `onLoad` / `onLoadData` / `onVoiceChanged`、以及 `ttsrv.*` 全套全局）都在它那份代码里，
 * 我们一个字节都没重写 —— 见 `kt/ttsrv/`（jing332/tts-server-android，MIT）。
 */
class PluginBridge(
    val context: Context,
    engineId: String,
    code: String,
    userVars: Map<String, String>? = null,
    ttsData: Map<String, String>? = null,
    locale: String = "zh-CN",
    voice: String = "",
) {
    /** 它自己的插件实体：脚本 + 用户填的变量。 */
    val plugin: Plugin = Plugin(code = code, pluginId = engineId, name = engineId).apply {
        this.userVars = LinkedHashMap(userVars ?: emptyMap())
    }

    /** 它自己的"这一路音色的源"：参数 + 插件自己塞的持久数据（`ttsrv.tts.data`）。 */
    var source: PluginTtsSource = PluginTtsSource(
        locale = locale, voice = voice, pluginId = engineId, data = LinkedHashMap(ttsData ?: emptyMap())
    )

    companion object {
        /**
         * 最近一次"插件真的吐了字节"的时间戳（毫秒）。全局一份就够了 —— 合成是串行的。
         * 看门狗靠它区分"慢"和"吊死"：还在吐就用不着超时。
         */
        @JvmStatic
        @Volatile
        var lastActivity: Long = 0L
            private set

        @JvmStatic
        fun touch() { lastActivity = System.currentTimeMillis() }
    }

    /** 上一次真正应用过的音色/参数（一样就不必再 setSource + onVoiceChanged 折腾插件）。 */
    @Volatile
    var appliedSig: String = ""

    /** 插件用它自己的控件画出来的界面（它们的 onLoadUI 往这儿 addView）。 */
    val uiContainer: android.widget.LinearLayout =
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

    /** 它自己的引擎：eval / getAudio / getVoices / getLocales / getSampleRate 全在里头。 */
    val engine: TtsPluginUiEngineV2 = TtsPluginUiEngineV2(context, plugin)

    init {
        // 插件里的 console.log / logger.* 接到我们的日志页 —— 不然插件报什么错我们都看不见
        // （它自己就是用 console 吐槽"登录过期/连不上"这类事的）
        try {
            engine.runtime.console.addLogListener { entry ->
                try {
                    // Kotlin 编译在 javac 之前，看不到我的 Java 类 —— 直接写 logcat（adb 能读）
                    android.util.Log.i("ttsrelay", "插件：" + entry.message)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        engine.source = source
        engine.eval()          // 跑脚本 + 读 name/id/author/version/vars
        engine.onLoad()
        // 它们的界面是 onLoadUI 建出来的：插件把控件句柄存在自己的闭包里，
        // 不建就会在 onVoiceChanged 里对 null 调 setVisibility（火山那个插件就是这么死的）。
        // 我们没有插件界面，但给它一个真容器，让那些控件句柄至少是个真对象。
        try {
            engine.onLoadUI(context, uiContainer)
        } catch (_: Throwable) {
        }
        // onLoadData / onVoiceChanged 是它们界面上的回调，插件报错不该把合成一起带走
        onLoadDataOnce()
    }

    // ── 元数据（`eval()` 读回来的，和它界面显示的是同一份）──

    fun name(): String = plugin.name
    fun id(): String = plugin.pluginId
    fun author(): String = plugin.author
    fun version(): Int = plugin.version
    fun iconUrl(): String = plugin.iconUrl

    /** 插件声明的变量：变量名 → {name, hint, loginUrl, loginDesc, binding}。 */
    fun defVars(): Map<String, Map<String, String>> = plugin.defVars

    /** 插件改过的每音色数据 —— 调用方负责存起来，下次接着用。 */
    fun ttsData(): Map<String, String> = source.data

    /** 换一路音色/参数。 */
    fun setSource(locale: String, voice: String, speed: Float, volume: Float, pitch: Float) {
        source = PluginTtsSource(
            locale = locale, voice = voice, pluginId = source.pluginId,
            speed = speed, volume = volume, pitch = pitch, data = source.data
        )
        engine.source = source
    }

    // ── 生命周期 ──

    fun onLoadData() = onLoadDataOnce()

    private fun onLoadDataOnce() {
        try {
            engine.onLoadData()
        } catch (_: Throwable) {
        }
    }

    /** 插件在界面回调里出错（比如它那些控件的 API 我们没有），不许影响合成。 */
    fun onVoiceChanged(locale: String, voice: String) {
        try {
            engine.onVoiceChanged(locale, voice)
        } catch (_: Throwable) {
        }
    }

    fun destroy() {
        try {
            engine.onStop()
        } catch (_: Throwable) {
        }
    }

    // ── 合成 / 音色 ──

    /**
     * 合成一次，把流读干。档位换算（1.0 → 50）在它 `getAudio` 里做。
     *
     * <b>必须带硬超时</b>：插件的 WebSocket 要是吊住不返回，播放线程就一直卡在这一句上，
     * 后面的队列看着就是"不念了、列表还剩几句"（真机上遇到过）。到点就抛，让调用方跳过这句。
     */
    @JvmOverloads
    fun audioBytes(text: String, rate: Float, volume: Float, pitch: Float,
                   timeoutMs: Long = 60_000L): ByteArray = runBlocking {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            val ins = engine.getAudio(text, source.locale, source.voice, rate, volume, pitch)
            // 一块一块读，每读到一块就把"它还在吐字节"的时间戳更新一次。
            // 外面那层硬超时看的就是这个时间戳：只要还在吐，慢也让它慢完；
            // 真的一个字节都不来（WS 会话废了）才算吊死。以前直接 readBytes()，
            // 外面只能按总时长卡，长句就被误杀 → 丢宿主 → 重建 → 开头断断续续。
            val bos = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(8192)
            ins.use {
                while (true) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    touch()
                }
            }
            bos.toByteArray()
        }
    }


    /** 它界面上的音色表：先 `getLocales()`，再逐个 `getVoices(locale)`。 */
    fun locales(): Map<String, String> = engine.getLocales()

    /** 平铺成 我们界面的 `名字‖取值`。 */
    fun voices(locale: String): List<Array<String>> =
        engine.getVoices(locale).map { arrayOf(it.name.ifEmpty { it.id }, it.id) }

    fun sampleRate(locale: String, voice: String): Int? = engine.getSampleRate(locale, voice)
}
