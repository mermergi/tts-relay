# 朗读中转

**让 Chatbox 把 AI 的每一条回复念出来。** 一个跑在安卓上的本地中继，不改客户端、不要 root、不用 Xposed。

<p align="center">
  <img src="docs/screenshot.png" width="300" alt="朗读中转">
</p>

---

## 为什么会有这东西

Chatbox 安卓版**本身没有任何朗读功能**——不是"不支持某种 TTS"，是整块都不存在。它的包里没有 `android.speech.tts`，没有 TTS 权限，485 个前端 chunk 里连 `语音`、`朗读` 两个字都搜不到。

但它是**可自定义 API 地址**的 OpenAI 兼容客户端。所以换个思路：**别去改它的界面，去它的 API 那一层动手。**

```
Chatbox ──API主机──► 127.0.0.1:8788 (本 App) ──► https://api.deepseek.com
                          │
                          │ 边转发边扒 SSE 流里的 delta.content
                          ▼
                 断句 ─► TTS Server ─► MediaPlayer 🔊
```

请求和响应**逐字节原样透传**，你的 API Key 只在 header 里路过，本 App 不解析、不保存、不打印。

**一句话：它蹲在你和模型中间，把 AI 说出口的每个字顺手抄给 TTS Server 念出来。**

---

## 能挂哪些客户端

凡是**能自定义 API 地址**的 OpenAI 兼容客户端都能用：

| 客户端 | |
|---|---|
| Chatbox（安卓 / 桌面） | ✅ |
| SillyTavern 酒馆 | ✅ 走 `Custom (OpenAI-compatible)` |
| NextChat / ChatGPT-Next-Web | ✅ |
| LobeChat / Open WebUI | ✅ |
| ChatGPT / DeepSeek / Grok 官方 App | ❌ 改不了 API 地址 |

---

## 它做对了哪些细节

- **原样透传** —— 流式、非流式、`/v1/models` 全部照转，Chatbox 的"打字机"效果不受影响
- **流水线** —— 合成第 N+1 句时第 N 句正在播，**句与句之间零空档**
- **一句是一句** —— 上一句没播完，下一句绝不插队
- **懒打断** —— 只有真的要念新正文时才打断旧朗读；"会话标题生成"这类不发声的请求**不会**把正在念的回复清空
- **播放看门狗** —— 音频焦点被抢导致静默暂停、或播放循环意外停摆，都会自动救回
- **自动跳过代码块** —— ` ``` ` 围栏内的内容不念（连跨 chunk 的半个围栏都认）
- **Markdown 清洗** —— 链接、图片、井号、列表符号、emoji 全刮掉再念
- **跳过思考过程** —— `reasoning_content` 不念，只念正文
- **CORS 全开** —— 网页版客户端（酒馆等）直连也能用，预检 `OPTIONS` 已处理
- **不需要 root** —— 就是一个普通的前台服务

---

## 依赖

| 组件 | 说明 |
|---|---|
| **TTS Server** | [jing332/tts-server-android](https://github.com/jing332/tts-server-android)，在它的「转发器」页启动服务（默认端口 **1221**） |
| 安卓 8.0+ | `minSdk 26` |

---

## 用法

**1. 在 TTS Server 里点开「转发器」，启动服务**（记下端口，默认 1221）

**2. 装上本 App，点「启动中转」**

**3. 在 Chatbox 里改一行：**

```
设置 → 模型提供方 → 你的提供方 → API 主机

   https://api.deepseek.com/v1   ──►   http://127.0.0.1:8788/v1
```

**API 路径 `/chat/completions` 不用动。** 改完发消息，AI 就开始念了。

> 想改回去就填回原来的地址。

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
git clone https://github.com/<you>/tts-relay.git
cd tts-relay
./tools/build.sh            # 编出 out/ttsrelay.apk
./tools/build.sh --install  # 编完直接 adb 装
```

`android.jar` 有 26MB，**没有进仓库**。编译脚本会自己找，找不到就设环境变量：

```bash
export ANDROID_JAR=/path/to/android.jar     # 从任意 Android SDK 的 platforms/android-34/android.jar 拷一份
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

1. **必须保持本 App 运行**。它是前台服务，划掉任务卡片也不会死，但**被强行停止**（系统设置里点"强行停止"）就会断——这时候 Chatbox 会报连接错误，重新打开即可。
2. **TTS Server 偶尔会自己崩**。它作为"系统语音引擎"被调用时可能抛 `IllegalArgumentException: Bad audio format 0`。撞上了本 App 的日志会提示你去重启它。
3. **中转不鉴权**。所以默认只监听 `127.0.0.1`。App 里有个「允许局域网访问」开关，开了绑 `0.0.0.0`，**局域网里谁连上都能用你的 API Key** —— 家里 WiFi 无所谓，公共网络别开。

---

## 原理补充

想深入了解 Legado / 阅读那套 HttpTTS 是怎么设计的，可以看这个思路：朗读被做在 **OpenAI 兼容 API 层**，而不是绑死某个客户端——所以它对所有"能改 API 地址"的软件通用。

---

## License

MIT
