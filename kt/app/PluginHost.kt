package com.mermergi.ttsrelay.js

import android.content.Context
import com.github.jing332.common.utils.limitLength
import com.github.jing332.script.engine.RhinoScriptEngine
import com.github.jing332.script.ensureArgumentsLength
import com.github.jing332.script.runtime.NativeResponse
import com.github.jing332.script.simple.CompatScriptRuntime
import com.github.jing332.script.simple.ext.JsExtensions
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.script.toMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.Response
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * TTS Server（jing332/tts-server-android，MIT）插件宿主。
 *
 * 逻辑逐行照抄它 `lib-tts/.../plugin/engine/TtsPluginEngineV2.kt` +
 * `TtsPluginUiEngineV2.kt` + `JsBridgeInputStream.kt`，
 * 只把它的 Room 实体（Plugin / PluginTtsSource）换成我们自己的两个小数据类。
 * 运行时是它自己的 `lib-script`（Rhino + Buffer/Response/WebSocket/http/fs/ttsrv）原样编出来的，
 * 不再是我手搓的等价物。
 */
class PluginHost(
    val context: Context,
    val engineId: String,
    val code: String,
    userVars: Map<String, String> = emptyMap(),
) {
    /** `ttsrv.tts` —— 每个音色自己的配置数据（插件里 `ttsrv.tts.data['xx']`）。 */
    class TtsSource {
        var data: MutableMap<String, Any?> = mutableMapOf()
    }

    /** `ttsrv` 本体：JsExtensions 的四个接口（net / crypto / ui）+ 上下文。 */
    class EngineCtx(
        var tts: TtsSource,
        val userVars: Map<String, String>,
        override val context: Context,
        override val engineId: String,
    ) : JsExtensions(context, engineId)

    companion object {
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val OBJ_UI_JS = "EditorJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val FUNC_GET_AUDIO_V2 = "getAudioV2"
        const val FUNC_ON_LOAD = "onLoad"
        const val FUNC_ON_STOP = "onStop"
        const val FUNC_ON_LOAD_DATA = "onLoadData"
        const val FUNC_LOCALES = "getLocales"
        const val FUNC_VOICES = "getVoices"
        const val FUNC_SAMPLE_RATE = "getAudioSampleRate"
        const val FUNC_ON_VOICE_CHANGED = "onVoiceChanged"
    }

    val ttsrv = EngineCtx(TtsSource(), userVars, context, engineId)
    private val runtime = CompatScriptRuntime(ttsrv)
    private val engine = RhinoScriptEngine(runtime)
    private val streamMutex by lazy { Mutex() }

    private val pluginJs: ScriptableObject
        get() = (engine.get(OBJ_PLUGIN_JS) as? ScriptableObject)
            ?: throw IllegalStateException("插件里没有 `$OBJ_PLUGIN_JS` 这个对象")

    private val uiJs: ScriptableObject?
        get() = engine.get(OBJ_UI_JS) as? ScriptableObject

    // ───────────────────────── 加载 ─────────────────────────

    /** 照抄它 `PackageImporter.default`，只留安卓那两个包（它自家 UI 类我们没搬）。 */
    private val PACKAGE_IMPORTS =
        "importPackage(android.view);importPackage(android.widget);"

    fun init() {
        engine.init()
        // 照抄它 `TtsPluginUiEngineV2.execute`：先 importPackage 再跑脚本
        engine.execute((PACKAGE_IMPORTS + code).toScriptSource(sourceName = engineId))
    }

    // ───────────────────────── 元数据（照抄它 `eval()`）─────────────────────────

    class VarDef(
        val key: String,
        val label: String,
        val hint: String,
        val loginUrl: String,
        val binding: String,
        val ua: String,
    )

    class Meta(
        val name: String,
        val id: String,
        val author: String,
        val iconUrl: String,
        val version: Int,
        val vars: List<VarDef>,
        val description: String,
    )

    fun meta(): Meta {
        val js = pluginJs
        val vars = ArrayList<VarDef>()
        val raw = js.get("vars", js)
        if (raw is Scriptable) {
            for (id in raw.ids) {
                val key = id.toString()
                val one = raw.get(key, raw)
                var label = key
                var hint = ""
                var loginUrl = ""
                var binding = ""
                var ua = ""
                if (one is Scriptable) {
                    label = str(one, "label").ifEmpty { key }
                    hint = str(one, "hint")
                    loginUrl = str(one, "loginUrl")
                    binding = str(one, "binding")
                    ua = str(one, "ua")
                }
                vars.add(VarDef(key, label, hint, loginUrl, binding, ua))
            }
        }
        val ver = try {
            org.mozilla.javascript.Context.toNumber(js.get("version", js)).toInt()
        } catch (_: Throwable) {
            -1
        }
        return Meta(
            name = str(js, "name"),
            id = str(js, "id"),
            author = str(js, "author"),
            iconUrl = str(js, "iconUrl"),
            version = ver,
            vars = vars,
            description = str(js, "description"),
        )
    }

    private fun str(o: Scriptable, key: String): String {
        val v = try {
            o.get(key, o)
        } catch (_: Throwable) {
            null
        }
        if (v == null || v === Undefined.instance || v === Scriptable.NOT_FOUND) return ""
        val s = v.toString()
        return if (s == "undefined" || s == "null") "" else s
    }

    fun hasAudioFn(): Boolean = try {
        pluginJs.get(FUNC_GET_AUDIO, pluginJs) is org.mozilla.javascript.Function ||
                pluginJs.get(FUNC_GET_AUDIO_V2, pluginJs) is org.mozilla.javascript.Function
    } catch (_: Throwable) {
        false
    }

    /** 给 Java 用：合成一次，把流读干。 */
    fun audioBytes(text: String, locale: String, voice: String,
                   rateMul: Float, volumeMul: Float, pitchMul: Float): ByteArray {
        val ins = audio(text, locale, voice, rateMul, volumeMul, pitchMul)
        return ins.use { it.readBytes() }
    }

    fun onLoad() {
        try {
            engine.invokeMethod(pluginJs, FUNC_ON_LOAD)
        } catch (_: Throwable) {
        }
    }

    fun onLoadData() {
        try {
            uiJs?.let { engine.invokeMethod(it, FUNC_ON_LOAD_DATA) }
        } catch (_: Throwable) {
        }
    }

    fun onVoiceChanged(locale: String, voice: String) {
        try {
            uiJs?.let { engine.invokeMethod(it, FUNC_ON_VOICE_CHANGED, locale, voice) }
        } catch (_: Throwable) {
        }
    }

    fun destroy() {
        try {
            engine.invokeMethod(pluginJs, FUNC_ON_STOP)
        } catch (_: Throwable) {
        }
        try {
            engine.destroy()
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── 合成 ─────────────────────────

    /** 插件交回来的东西 → 字节流。照抄 `handleAudioResult`。 */
    private fun handleAudioResult(result: Any?): InputStream? {
        if (result == null) return null
        return when (result) {
            is NativeArrayBuffer -> ByteArrayInputStream(result.buffer)
            is NativeTypedArrayView<*> -> ByteArrayInputStream(result.buffer.buffer)

            is InputStream -> result
            is ByteArray -> result.inputStream()
            is PipedOutputStream -> PipedInputStream(result)

            is NativeResponse -> result.rawResponse?.body?.check()?.byteStream()
            is CharSequence -> {
                val str = result.toString()
                if (str.startsWith("http://") || str.startsWith("https://")) {
                    val resp: Response = okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(str).build()
                    ).execute()
                    return resp.body?.check()?.byteStream()
                } else
                    throw IllegalStateException(str)
            }

            is Undefined -> null

            else -> throw IllegalArgumentException("getAudio() 返回的类型不支持：${result.javaClass.name}")
        }
    }

    /** 照抄 `ResponseBody.check()`：拿回来的是一段文本/JSON，说明插件把报错当音频交了。 */
    private fun okhttp3.ResponseBody.check(): okhttp3.ResponseBody {
        val type = contentType()?.toString() ?: return this
        if (type.startsWith("text") || type.startsWith("application/json")) {
            throw IllegalStateException("拿回来的不是音频：${this.string().limitLength(500)}")
        }
        return this
    }

    /** 照抄 `newCallback`：插件用 write/close/error 三个函数把音频一段段喂回来。 */
    private fun newCallback(ins: JsBridgeInputStream): Scriptable {
        val callback = runBlocking { ins.getCallback(streamMutex) }
        return org.mozilla.javascript.Context.enter().use { cx ->
            cx.newObject(engine.scope ?: engine.globalScope).apply {
                put("write", this, object : Callable {
                    override fun call(
                        cx: org.mozilla.javascript.Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any?>,
                    ): Any = ensureArgumentsLength(args, 1) {
                        callback.write(args[0])
                        Undefined.instance
                    }
                })
                put("close", this, object : Callable {
                    override fun call(
                        cx: org.mozilla.javascript.Context?,
                        scope: Scriptable?,
                        thisObj: Scriptable?,
                        args: Array<out Any?>?,
                    ): Any {
                        callback.close()
                        return Undefined.instance
                    }
                })
                put("error", this, object : Callable {
                    override fun call(
                        cx: org.mozilla.javascript.Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any?>,
                    ): Any = ensureArgumentsLength(args, 1) {
                        callback.error(args[0])
                        Undefined.instance
                    }
                })
            }
        }
    }

    /** 照抄 `getAudioV2(request)`。 */
    private fun getAudioV2(text: String, locale: String, voice: String,
                           rate: Int, volume: Int, pitch: Int): InputStream {
        val ins = JsBridgeInputStream()
        val jsObj = newCallback(ins)
        val request = mapOf(
            "text" to text,
            "locale" to locale,
            "voice" to voice,
            "rate" to rate,
            "speed" to rate,
            "volume" to volume,
            "pitch" to pitch,
        )
        val result = try {
            engine.invokeMethod(pluginJs, FUNC_GET_AUDIO_V2, request, jsObj)
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("插件里既没有 getAudio 也没有 getAudioV2")
        }
        return handleAudioResult(result) ?: ins
    }

    /** 照抄 `getAudio(...)`：档位是 0-100（50 正常），外面给的是 1.0 正常。 */
    fun audio(text: String, locale: String, voice: String,
              rateMul: Float, volumeMul: Float, pitchMul: Float): InputStream {
        val r = (rateMul * 50f).toInt()
        val v = (volumeMul * 50f).toInt()
        val p = (pitchMul * 50f).toInt()

        val hasV1 = try {
            pluginJs.get(FUNC_GET_AUDIO, pluginJs) is org.mozilla.javascript.Function
        } catch (_: Throwable) {
            false
        }

        if (!hasV1) return getAudioV2(text, locale, voice, r, v, p)

        val result = engine.invokeMethod(pluginJs, FUNC_GET_AUDIO, text, locale, voice, r, v, p)
        return handleAudioResult(result) ?: getAudioV2(text, locale, voice, r, v, p)
    }

    // ───────────────────────── 音色 ─────────────────────────

    /** 照抄 `getLocales()`：列表 / Map 两种形状都认。 */
    fun locales(): Map<String, String> {
        val ui = uiJs ?: return emptyMap()
        return try {
            when (val r = engine.invokeMethod(ui, FUNC_LOCALES)) {
                is List<*> -> r.associate {
                    it.toString() to it.toString()
                }
                is Map<*, *> -> r.map { (k, v) -> k.toString() to v.toString() }.toMap()
                else -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** 照抄 `getVoices(locale)`：插件返回 `{取值: {name, icon}}` 这种对象是最常见的形状。 */
    fun voices(locale: String): List<Array<String>> {
        val ui = uiJs ?: return emptyList()
        val r = try {
            engine.invokeMethod(ui, FUNC_VOICES, locale)
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Array<String>>()
        when (r) {
            is ScriptableObject -> {
                for ((k, v) in r.toMap<Any, Any>()) {
                    val key = ScriptRuntime.toString(k)
                    var name = if (v is CharSequence) v.toString() else ""
                    if (v is ScriptableObject) {
                        name = v.get("name")?.toString() ?: name
                    }
                    out.add(arrayOf(if (name.isEmpty()) key else name, key))
                }
            }
            is List<*> -> {
                for (one in r) {
                    if (one is ScriptableObject) {
                        val id = one.get("id")?.toString()
                            ?: one.get("value")?.toString()
                            ?: one.get("voice")?.toString()
                            ?: one.get("name")?.toString().orEmpty()
                        val name = one.get("name")?.toString()
                            ?: one.get("label")?.toString()
                            ?: id
                        out.add(arrayOf(name, id))
                    } else if (one != null) {
                        out.add(arrayOf(one.toString(), one.toString()))
                    }
                }
            }
        }
        return out
    }

    /** 照抄 `getSampleRate(locale, voice)`。 */
    fun sampleRate(locale: String, voice: String): Int? {
        val ui = uiJs ?: return null
        return try {
            when (val r = engine.invokeMethod(ui, FUNC_SAMPLE_RATE, locale, voice)) {
                is Int -> r
                is Double -> r.toInt()
                is Number -> r.toInt()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 插件返回的 `vars` 定义（给 UI 填变量用）。 */
    @Suppress("UNCHECKED_CAST")
    fun defVars(): Map<String, Any?> = try {
        pluginJs.get("vars") as? Map<String, Any?> ?: emptyMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    fun pluginName(): String = try {
        pluginJs.get("name")?.toString() ?: ""
    } catch (_: Throwable) {
        ""
    }
}
