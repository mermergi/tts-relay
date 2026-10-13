# 朗读中转

**让你手机上任何"能改 API 地址"的 AI 对话客户端，自动把 AI 的回复念出来。**

跑在安卓上的本地 API 中继：不改客户端、不要 root、不用 Xposed。

<p align="center">
  <img src="docs/screenshot.png" width="300" alt="朗读中转">
</p>

---

## 为什么会有这东西

市面上大把 OpenAI 兼容的对话客户端**根本没有朗读功能**——不是"不支持某种 TTS"，是整块都不存在。装个包扒一下就知道：没有 `android.speech.tts`，没有 TTS 权限，前端 bundle 里连"语音""朗读"两个字都搜不到。

但这类客户端**绝大多数都能自定义 API 地址**。所以换个思路：

> **别去改它的界面，去它的 API 那一层动手。**

```
你的客户端 ──API主机──► 127.0.0.1:8788 (本 App) ──► 你的模型服务
                             │
                             │ 边转发边扒 SSE 流里的 delta.content
                             ▼
                    断句 ─► TTS Server ─► MediaPlayer 🔊
```

请求和响应**逐字节原样透传**，你的 API Key 只在 header 里路过，本 App 不解析、不保存、不打印。

---

## 三个角色

| 角色 | 是谁 | 干什么 |
|---|---|---|
| 🧠 **大脑** | 你的对话客户端 | 产生文字 |
| 📮 **传话的** | **朗读中转**（本 App） | 把流式正文扒出来、断句、排队、播出来 |
| 🔊 **嗓子** | **[TTS Server](https://github.com/jing332/tts-server-android)** | 把文字合成音频 |

三者里，**本 App 只管"传"和"排"，一个字都不合成**。嗓子是 TTS Server 的活儿——所以你觉得声音不好听，是去 TTS Server 里换音色，不是改这里。

---

## 能挂哪些客户端

**凡是能自定义 API 地址的 OpenAI 兼容客户端都能用**：

| 客户端 | |
|---|---|
| Chatbox（安卓 / 桌面） | ✅ |
| SillyTavern 酒馆 | ✅ 走 `Custom (OpenAI-compatible)` |
| NextChat / ChatGPT-Next-Web | ✅ |
| LobeChat | ✅ |
| Open WebUI | ✅ |
| Cherry Studio | ✅ |
| 其它自建的 OpenAI 兼容前端 | ✅ |
| 官方 ChatGPT / DeepSeek / Grok App | ❌ 不让改 API 地址 |

判断标准就一条：**设置里能不能把 API 主机改成自定义地址**。能，就能挂。

---

## 依赖：TTS Server

本 App 不发声，它把文字交给 **TTS Server** 合成音频。

### 装 & 配

1. 装 [TTS Server](https://github.com/jing332/tts-server-android)
2. 打开它 → 底部 **「转发器」** 页 → **启动服务**
3. 记下**监听端口**（默认 `1221`）
4. 在「系统 TTS」页里配好音色（它自带一大堆，也能接自己的 HTTP TTS 引擎）

### 本 App 用它的哪个接口

| 接口 | 用途 |
|---|---|
| `GET /api/tts?text=&engine=&rate=&pitch=&voice=` | 合成音频（返回 WAV） |
| `GET /api/voices?engine=` | 列音色 |

两者都在 **同一个局域网/本机 loopback** 上，所以**用 `127.0.0.1` 就行，不用联网权限，也不用配置任何密钥**。

> 如果 TTS Server 挂了，本 App 的日志会直接告诉你，而不是干等着没声音。

---

## 用法

**1.** 按上面把 **TTS Server 的转发器启动起来**

**2.** 装本 App（[ Releases 下载 APK ](../../releases)），点 **「启动中转」**

**3.** 打开你用的客户端，把 **API 主机 / Base URL** 改成：

```
http://127.0.0.1:8788/v1
```

**API 路径 `/chat/completions` 不用动。** 改完发消息，AI 就开始念了。

> 想改回去就填回原来的地址。

---

## 它做对了哪些细节

- **原样透传** —— 流式、非流式、`/v1/models` 全部照转，客户端的"打字机"效果不受影响
- **流水线** —— 合成第 N+1 句时第 N 句正在播，**句与句之间零空档**
- **一句是一句** —— 上一句没播完，下一句绝不插队
- **懒打断** —— 只有真的要念新正文时才打断旧朗读；"会话标题生成"这类不发声的请求**不会**把正在念的回复清空
- **播放看门狗** —— 音频焦点被抢导致静默暂停、或播放循环意外停摆，都会自动救回
- **自动跳过代码块** —— ` ``` ` 围栏内的内容不念（连跨 chunk 的半个围栏都认）
- **Markdown 清洗** —— 链接、图片、井号、列表符号、emoji 全刮掉再念
- **跳过思考过程** —— `reasoning_content` 不念，只念正文
- **CORS 全开** —— 网页版客户端直连也能用，预检 `OPTIONS` 已处理
- **不需要 root** —— 就是一个普通的前台服务

---

## 本地调试接口

中继在 `127.0.0.1:8788` 上开了一组 `/_` 开头的接口，出问题一查就知道：

| 接口 | 作用 |
|---|---|
| `GET /_status` | 运行状态、已念句数、失败次数、卡死救回次数 |
| `GET /_log` | **完整日志**（落盘，重启不丢）—— 排障主要看这个 |
| `GET /_selftest` | 自检：不连模型，直接跑一遍断句→跳代码块→合成→播放 |
| `GET /_voices` | 列出 TTS Server 的全部音色 |
| `GET /_voice?v=名字` | 换音色 |
| `GET /_say?text=...` | 立刻念一句 |
| `GET /_stop` | 打断当前朗读 |
| `GET /_mute` / `/_unmute` | 闭嘴 / 恢复 |

日志里每一发请求都会记一笔，并且标明念不念：

```
[00:06:13] ← POST /v1/chat/completions model=deepseek-chat msgs=2 末条=user(2字) max_tokens=4096 stream=true  →  念
[00:06:13] ← POST /v1/chat/completions model=deepseek-chat msgs=1 末条=user(9字) max_tokens=50  stream=false →  跳过
```

---

## 自己编译（全程在手机上，不用电脑）

需要 Termux + `pkg install aapt2 apksigner d8 openjdk-17`，另外准备一份 `android.jar`（见下）。

```bash
git clone https://github.com/mermergi/tts-relay.git
cd tts-relay
./tools/build.sh            # 编出 out/ttsrelay.apk
./tools/build.sh --install  # 编完直接 adb 装
```

`android.jar` 有 26MB，**没有进仓库**。编译脚本会自己找，找不到就设环境变量：

```bash
export ANDROID_JAR=/path/to/android.jar   # 从任意 Android SDK 的 platforms/android-34/android.jar 拷一份
```

### 目录结构

```
├── AndroidManifest.xml
├── res/                      图标、主题、明文流量配置
├── src/com/mermergi/ttsrelay/
│   ├── MainActivity.java     界面：开关 / 状态 / 音色 / 试听 / 自检 / 日志
│   ├── RelayService.java     前台服务 + 看门狗
│   ├── HttpServer.java       手写 HTTP/1.1 + SSE 流式透传 + CORS
│   ├── Speaker.java          合成/播放双线程流水线 + 卡死看门狗
│   ├── Splitter.java         断句 + 跳代码块 + Markdown 清洗
│   ├── TtsClient.java        跟 TTS Server 说话
│   ├── Prefs.java            配置
│   └── LogBus.java           日志环 + 落盘
└── tools/
    ├── build.sh              编译+签名全流程
    └── make-icon.py          纯标准库生成图标
```

---

## 已知限制

1. **必须保持本 App 运行**。它是前台服务，划掉任务卡片也不会死，但**被强行停止**（系统设置里点"强行停止"）就会断——这时候客户端会报连接错误，重新打开即可。
2. **本 App 只在中继，不合成**。声音好不好听、中文断句准不准，取决于 **TTS Server** 和它里面配的引擎。TTS Server 偶尔会自己崩（作为系统语音引擎被调用时可能抛 `IllegalArgumentException: Bad audio format 0`），撞上了本 App 的日志会提示你去重启它。
3. **中转不鉴权**。所以默认只监听 `127.0.0.1`。App 里有个「允许局域网访问」开关，开了绑 `0.0.0.0`，**局域网里谁连上都能用你的 API Key** —— 家里 WiFi 无所谓，公共网络别开。
4. **客户端必须支持自定义 API 地址**，且用 OpenAI 兼容协议（`/chat/completions`）。用 Anthropic / Gemini 原生协议的挂不上。

---

## 参考

- [jing332/tts-server-android](https://github.com/jing332/tts-server-android) —— 嗓子的提供者
- 朗读被做在 **OpenAI 兼容 API 层**，而不是绑死某个客户端——所以它对所有"能改 API 地址"的软件通用

---

## License

MIT
