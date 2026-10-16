#!/data/data/com.termux/files/usr/bin/bash
# 拉第三方 jar / aar —— 不塞进 git（免得仓库里堆二进制，许可也乱）。
# 全部来自 Maven Central / Google Maven / JitPack，出处与许可见 tools/lib/SOURCES.md
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lib="$here/lib"
mkdir -p "$lib"

get() { # url [输出名]
    local url="$1" name="${2:-$(basename "$1")}" f="$lib/${2:-$(basename "$1")}"
    if [ -s "$f" ]; then echo "  ✓ $name"; return; fi
    curl -fsSL -o "$f" "$url" || { echo "  ✗ 下载失败：$url" >&2; rm -f "$f"; return 1; }
    echo "  ↓ $name ($(stat -c%s "$f") 字节)"
}

# aar → 里面的 classes.jar
get_aar() { # url 输出名
    local url="$1" name="$2" tmp="$lib/.aar.$$"
    if [ -s "$lib/$name" ]; then echo "  ✓ $name"; return; fi
    mkdir -p "$tmp"
    curl -fsSL -o "$tmp/a.aar" "$url" || { echo "  ✗ 下载失败：$url" >&2; rm -rf "$tmp"; return 1; }
    unzip -o -q "$tmp/a.aar" classes.jar -d "$tmp" || { echo "  ✗ aar 里没 classes.jar：$url" >&2; rm -rf "$tmp"; return 1; }
    mv "$tmp/classes.jar" "$lib/$name"
    rm -rf "$tmp"
    echo "  ↓ $name ($(stat -c%s "$lib/$name") 字节)"
}

MC=https://repo1.maven.org/maven2
GM=https://dl.google.com/dl/android/maven2

echo "==> Rhino（JS 引擎，TTS Server 用的就是它）"
get "$MC/org/mozilla/rhino/1.8.0/rhino-1.8.0.jar"

echo "==> Kotlin 运行时（协程 + 日志门面）"
get "$MC/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.1/kotlinx-coroutines-core-jvm-1.10.1.jar"
get "$MC/io/github/oshai/kotlin-logging-jvm/7.0.0/kotlin-logging-jvm-7.0.0.jar"
get "$MC/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"

echo "==> OkHttp（插件的网络全走它）"
get "$MC/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
get "$MC/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"

echo "==> Drake Net（TTS Server 的 http 封装）"
get_aar "https://jitpack.io/com/github/liangjingkanji/Net/3.7.0/Net-3.7.0.aar" net-3.7.0.jar

echo "==> hutool（插件 API 里的 base64/hex/加解密）"
get "$MC/cn/hutool/hutool-all/5.8.19/hutool-all-5.8.19.jar"

echo "==> media3 datasource（只用来读音频采样率）"
get_aar "$GM/androidx/media3/media3-datasource/1.5.1/media3-datasource-1.5.1.aar" media3-datasource-1.5.1.jar
get_aar "$GM/androidx/media3/media3-common/1.5.1/media3-common-1.5.1.aar" media3-common-1.5.1.jar

echo "==> 收工，tools/lib/："
ls -1 "$lib" | sed 's/^/    /'
