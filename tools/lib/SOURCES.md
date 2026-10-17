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

## androidx（插件实体 + 插件引擎 + 界面用得着的那些）

annotation-jvm / collection-jvm / room-common / lifecycle-common / lifecycle-runtime /
lifecycle-viewmodel / lifecycle-viewmodel-savedstate / versionedparcelable / savedstate /
annotation-experimental —— 全部 [AndroidX](https://github.com/androidx/androidx)，**Apache-2.0**。

> core / core-ktx / activity / fragment 这四个原来是从 aar 里抽 `classes.jar` 单独放的，
> 现在界面这层要用它们的**资源**，所以改成整包放在 `aar/` 里（见下）。

## 界面这一层 —— Material Components（整包 aar，资源要编进去）

| 文件 | 出处 | 许可证 |
|---|---|---|
| aar/material-1.12.0.aar | [material-components-android](https://github.com/material-components/material-components-android) | Apache-2.0 |
| aar/appcompat-1.7.0.aar / appcompat-resources-1.7.0.aar | [androidx.appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | Apache-2.0 |
| aar/recyclerview-1.3.2.aar | [androidx.recyclerview](https://developer.android.com/jetpack/androidx/releases/recyclerview) | Apache-2.0 |
| aar/core-1.13.1.aar / core-ktx-1.13.1.aar | [androidx.core](https://developer.android.com/jetpack/androidx/releases/core) | Apache-2.0 |
| aar/activity-1.9.3.aar / fragment-1.8.5.aar | androidx.activity / fragment | Apache-2.0 |
| aar/cardview-1.0.0.aar / coordinatorlayout-1.2.0.aar / drawerlayout-1.2.0.aar | androidx | Apache-2.0 |
| aar/dynamicanimation-1.1.0.aar / transition-1.5.0.aar | androidx | Apache-2.0 |
| aar/vectordrawable-1.2.0.aar / vectordrawable-animated-1.2.0.aar | androidx | Apache-2.0 |
| aar/cursoradapter-1.0.0.aar / customview-1.1.0.aar / loader-1.1.0.aar | androidx | Apache-2.0 |
| aar/viewpager-1.0.0.aar / viewpager2-1.1.0.aar | androidx | Apache-2.0 |
| aar/constraintlayout-2.1.4.aar + constraintlayout-core-1.0.4.jar | [androidx.constraintlayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) | Apache-2.0 |

界面为什么挂这一整套：以前每个控件（片子 / 抽屉 / 弹窗 / 列表）都是手画的，
现在是
* **BottomSheetDialog** —— 音色面板 / 「音色 → 名称」面板；遮罩、拖动、下滑关闭是库里的；
* **RecyclerView + ListAdapter(DiffUtil)** —— 音色库列表（`VoiceListAdapter.java`），
  分组的两种视图类型 + 行内 🎧；
* **MaterialCardView / Material 弹窗壳** —— 行和弹窗的圆角、水波、描边。

`build.sh` 会把 `aar/` 下所有包的 `res/` 合并后一起编（同名 `values.xml` 按
"并 children"的规矩合，见 `tools/merge-aar-res.py`），并用 `--extra-packages`
给每个库生成它自己的 `R` 类。

## media3 —— 已移除（原来写错了）

曾有 `media3-common / datasource / exoplayer / ui 1.5.1`，理由是"读音频采样率要用"。
其实 `ttsrv.getAudioSampleRate` 走的是 `AudioDecoder` → **平台自己的
`android.media.MediaExtractor`**（见 `kt/ttsrv/com/github/jing332/common/audio/AudioDecoder.kt`），
跟 media3 没关系；我们自己的代码也一处没引用（`grep -rn media3 src/ kt/` 为空）。
再挂着它纯粹是白占 APK（四个 jar 全被 d8 打进 dex），所以删掉。

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

## 性别识别的两张数据表（`assets/`，不是代码）

音色列表里很多音色只有名字，没有 `zh_male` / `female` 这种标记；靠名字判断男女时
不再手写词库，用两份开放数据离线算出来，随 APK 打包：

| 文件 | 来源 | 许可 | 生成脚本 |
|---|---|---|---|
| `assets/gender_words.txt` | [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) 的英文释义（释义里写了 girl/woman/sister/male/boy 等） | **CC BY-SA 4.0** | `tools/build-gender-words.py` |
| `assets/gender_table.txt` | [wainshine/Chinese-Names-Corpus](https://github.com/wainshine/Chinese-Names-Corpus)（105 万条「名字,性别」）统计出的字 / 双字倾向 | Apache-2.0 | `tools/build-gender-table.py` |

判断顺序：① 值里写明的标记（`female` / `_male_` / `女` / `男`）→ ② CC-CEDICT 词表里最长匹配
（萝莉、御姐、一姐、老铁、学长、闺蜜…）→ ③ 字表兜底（要两个字以上、且倾向明显）。

`gender_words.txt` 是 CC-CEDICT 的衍生数据，按 CC BY-SA 4.0 同样开放，可用上面那个脚本重算。
