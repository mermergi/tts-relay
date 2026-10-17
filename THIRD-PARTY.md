# 第三方组件（THIRD-PARTY NOTICES）

本 App 不自带任何 TTS 服务的密钥 —— 朗读用什么"嗓子"取决于你自己配的引擎。
下面是编进 APK 里的开源代码；版本号、来源 URL、获取脚本都在
[`tools/lib/SOURCES.md`](tools/lib/SOURCES.md)（`bash tools/fetch-deps.sh` 自动拉）。

## 构建期（不随 APK 分发）

| 组件 | 许可 |
|---|---|
| Android SDK：`android.jar` / aapt2 / d8 / apksigner | Apache-2.0 |
| Kotlin 编译器与标准库（跟 kotlinc 走） | Apache-2.0 |

## 随 APK 分发

| 组件 | 许可 | 说明 |
|---|---|---|
| [TTS Server 插件运行时](https://github.com/jing332/tts-server-android)（`kt/ttsrv/`） | MIT | 源码原样搬入，改动逐处写在文件注释里 |
| [sora-editor](https://github.com/Rosemoe/sora-editor) 0.23.5 | **LGPL-2.1** | 未修改、作为独立库链接；可替换为同版本或更新版本 |
| [Mozilla Rhino](https://github.com/mozilla/rhino) 1.8.0 | MPL-2.0 | |
| [Material Components for Android](https://github.com/material-components/material-components-android) 1.12.0 | Apache-2.0 | 底部抽屉 / 弹窗 / 卡片 |
| AndroidX（annotation / collection / room-common / lifecycle / core / core-ktx / activity / fragment / savedstate / versionedparcelable / appcompat / recyclerview / cardview / coordinatorlayout / drawerlayout / dynamicanimation / transition / vectordrawable / cursoradapter / customview / loader / viewpager / viewpager2 / constraintlayout） | Apache-2.0 | 界面与插件宿主共用 |
| OkHttp / Okio | Apache-2.0 | |
| KotlinX Coroutines / Serialization | Apache-2.0 | |
| kotlin-logging / kotlin-result | Apache-2.0 / MIT | |
| [Drake Net](https://github.com/liangjingkanji/Net) | Apache-2.0 | 插件的 http 走它 |
| hutool / commons-text / commons-lang3 / snakeyaml-engine / gson | MPL-2.0 / Apache-2.0 | |
| joni / jcodings / TextMate 语法与主题（`assets/textmate/`） | MIT | 代码高亮 |
| [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict) 衍生的性别词表（`assets/gender_words.txt`） | **CC BY-SA 4.0** | 判断音色名男女；衍生数据同样以 CC BY-SA 4.0 提供 |
| [Chinese-Names-Corpus](https://github.com/wainshine/Chinese-Names-Corpus) 统计出的字级性别表（`assets/gender_table.txt`） | Apache-2.0 | 同上，兜底用 |

## 再分发时

请连同本文件、`LICENSE` 与 `tools/lib/SOURCES.md` 一起保留 ——
上面这些许可证都要求保留版权与许可声明。sora-editor 是 LGPL-2.1：
它是独立库（没有静态编进你的代码里），替换它只需换掉
`tools/lib/aar/sora-editor-*.aar` 再重新构建。
