# tools/lib —— 编译/运行要用的第三方 jar / aar

不进 git（`.gitignore` 挡着）。缺什么跑：`bash tools/fetch-deps.sh`。

## 插件宿主那套（TTS Server 自己用的）

| 文件 | 出处 | 许可证 |
|---|---|---|
| rhino-1.8.0.jar | [mozilla/rhino](https://github.com/mozilla/rhino) | MPL-2.0 |
| kotlinx-coroutines-core-jvm-1.10.1.jar | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlin-logging-jvm-7.0.0.jar | [oshai/kotlin-logging](https://github.com/oshai/kotlin-logging) | Apache-2.0 |
| slf4j-api-2.0.16.jar | [qos-ch/slf4j](https://github.com/qos-ch/slf4j) | MIT |
| okhttp-4.12.0.jar | [square/okhttp](https://github.com/square/okhttp) | Apache-2.0 |
| okio-jvm-3.6.0.jar | [square/okio](https://github.com/square/okio) | Apache-2.0 |
| net-3.7.0.jar | [liangjingkanji/Net](https://github.com/liangjingkanji/Net)（JitPack aar 里抽的） | Apache-2.0 |
| hutool-all-5.8.19.jar | [dromara/hutool](https://github.com/dromara/hutool) | MPL-2.0 |
| kotlin-result-jvm-2.0.1.jar | [michael-bull/kotlin-result](https://github.com/michael-bull/kotlin-result) | Apache-2.0 |
| kotlinx-serialization-{core,json}-jvm-1.8.0.jar | [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| commons-text-1.13.0.jar / commons-lang3-3.17.0.jar | [apache/commons-text](https://github.com/apache/commons-text) | Apache-2.0 |

## androidx（插件实体 + 插件引擎用得着的那些）

annotation-jvm / collection-jvm / room-common-jvm / lifecycle-common / versionedparcelable /
savedstate / core / core-ktx / activity / fragment / lifecycle-runtime /
lifecycle-viewmodel-savedstate / annotation-experimental —— 全部
[AndroidX](https://github.com/androidx/androidx)，**Apache-2.0**。

## media3（只用来读音频采样率：`ttsrv.getAudioSampleRate`）

media3-common / media3-datasource / media3-exoplayer / media3-ui 1.5.1 ——
[androidx/media](https://github.com/androidx/media)，**Apache-2.0**。

## 代码编辑器 —— 这是 TTS Server 的插件编辑器用的那个

`aar/sora-editor-0.23.5.aar`（`io.github.Rosemoe.sora-editor:editor`）——
[Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor)，**LGPL-2.1**。

我们**未做任何修改**地把它作为独立库链接进 APK（`tools/build.sh` 会编它的 `res/` 并用
`--extra-packages io.github.rosemoe.sora` 生成它自己的 `R` 类）。LGPL 要求署名 + 允许替换，
所以：源码/许可证在此登记，库本体可随时换成同版本或更新版本。

## Kotlin 标准库

不在这儿 —— 它跟着 `kotlinc` 走（`$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar`），
版本必须和编译器一致，自己下容易错版。

## 还有一份「真源码」不是 jar

`kt/ttsrv/` 是 [jing332/tts-server-android](https://github.com/jing332/tts-server-android)
的 **lib-script + lib-common + lib-tts/speech/plugin + 相关数据库实体** 源码（**MIT**），
原样搬进来的：Rhino 运行时、`Buffer` / `Response` / `Websocket` / `http` / `fs` / `logger` /
`console` / `environment` / 老式 `ttsrv.*`，以及它那两个插件引擎类
（`TtsPluginEngineV2` / `TtsPluginUiEngineV2`）、`JsBridgeInputStream`、`PackageImporter`、
插件实体 `Plugin` / `PluginTtsSource`。

`kt/app/PluginBridge.kt` 只是**接线**：把我们的引擎配置翻译成它的实体，然后调它的引擎类。

搬动时只做了这几处（都在文件里有注释）：
1. `Global.kt` 里它 import 了 `GlobalWebview.NAME` 却在基类里用（重构留的坑）→ 改回用它自己的 `name` 参数；
2. 它那三个插件界面控件（`JTextInput` / `JSpinner` / `JSeekBar`）要 Compose Material3 + 它自家的
   compose 模块，而插件只在 `onLoadUI` 里用它们、我们又没有插件界面 → 不编这三个文件；
3. `AndroidExtension.kt` / `ToastUtils.kt` 里那几个 `dp` / `toast` 一行辅助夹着一堆
   androidx.lifecycle 的 KMP 依赖（发布出来的 jar 是空壳）→ 用 `kt/ttsrv/` 下那两个几行的替身顶上；
4. `splitties-appctx` 发布出来的包是空壳 jar → 用一个六行的全局 Context 取值器顶上。

除这四处，插件语义一个字节没改。
