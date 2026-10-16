package com.github.jing332.script.simple

import com.github.jing332.script.runtime.Environment
import com.github.jing332.script.runtime.RhinoScriptRuntime
import com.github.jing332.script.simple.ext.JsExtensions

class CompatScriptRuntime(val ttsrv: JsExtensions) :
    RhinoScriptRuntime(
        environment = Environment(
            ttsrv.context.externalCacheDir?.absolutePath
                ?: throw IllegalArgumentException("context.externalCacheDir is null"),
            ttsrv.engineId
        )
    ) {
    override fun init() {
        super.init()
        globalScope.defineGetter("ttsrv", ::ttsrv)
    }
}