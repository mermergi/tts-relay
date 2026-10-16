# tools/lib —— 编译/运行要用的第三方 jar

不进 git（`.gitignore` 挡着）。缺什么跑：`bash tools/fetch-deps.sh`。

| 文件 | 出处 | 许可证 |
|---|---|---|
| rhino-1.8.0.jar | [org.mozilla:rhino](https://github.com/mozilla/rhino) | MPL-2.0 |
| kotlinx-coroutines-core-jvm-1.10.1.jar | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlin-logging-jvm-7.0.0.jar | [oshai/kotlin-logging](https://github.com/oshai/kotlin-logging) | Apache-2.0 |
| slf4j-api-2.0.16.jar | [qos-ch/slf4j](https://github.com/qos-ch/slf4j) | MIT |
| okhttp-4.12.0.jar | [square/okhttp](https://github.com/square/okhttp) | Apache-2.0 |
| okio-jvm-3.6.0.jar | [square/okio](https://github.com/square/okio) | Apache-2.0 |
| net-3.7.0.jar | [liangjingkanji/Net](https://github.com/liangjingkanji/Net)（JitPack 的 aar 里抽的 classes.jar） | Apache-2.0 |
| hutool-all-5.8.19.jar | [dromara/hutool](https://github.com/dromara/hutool) | MPL-2.0 |
| media3-datasource-1.5.1.jar / media3-common-1.5.1.jar | [androidx.media3](https://github.com/androidx/media)（Google Maven 的 aar 里抽的 classes.jar） | Apache-2.0 |

Kotlin 标准库不在这儿 —— 它跟着 `kotlinc` 走（`$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar`），
版本必须和编译器一致，自己下容易错版。

## 还有一份「真源码」不是 jar

`kt/ttsrv-script/` 是 [jing332/tts-server-android](https://github.com/jing332/tts-server-android)
的 `lib-script` 模块源码（**MIT**），原样搬进来的：Rhino 运行时、`Buffer`、`Response`、
`Websocket`、`http`/`fs`/`logger`/`console`/`environment`、老式 `ttsrv.*` 全在里面。
`kt/app/PluginHost.kt` 是从它 `lib-tts/.../plugin/engine/TtsPluginEngineV2.kt`、
`TtsPluginUiEngineV2.kt`、`JsBridgeInputStream.kt` 逐行搬过来的协议层，只把它的 Room 实体换成了我们的小数据类。

搬动时只做了这几处改动（每一处都在文件里有注释）：
1. 摘掉 `BackstageWebView.kt` / `GlobalWebview.kt`（`webview` 全局要 WebView + 一堆 androidx，我们不支持），
   并去掉注册表里对它的引用；
2. `Global.kt` 里它自己 import 了 `GlobalWebview.NAME` 却在基类里用（重构留的坑）→ 改回用它自己的 `name` 参数；
3. `LogEntry.kt` 去掉 `@Parcelize`（那要 kotlin-parcelize 编译器插件；这日志只在进程内用）；
4. `splitties-appctx`、`androidx.annotation.*`、`androidx.core.net.toFile` 三个纯接口/一行货用本地替身顶上，
   替身都在 `kt/ttsrv-script-stub/`。
