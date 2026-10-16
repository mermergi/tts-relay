#!/data/data/com.termux/files/usr/bin/bash
# 拉第三方 jar / aar —— 不塞进 git（免得仓库里堆二进制，许可也乱）。
# 出处与许可见 tools/lib/SOURCES.md
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lib="$here/lib"
mkdir -p "$lib" "$lib/aar"

MC=https://repo1.maven.org/maven2
GM=https://dl.google.com/dl/android/maven2
JIT=https://jitpack.io

TMP="$PREFIX/tmp/.fetch-deps.$$"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

get() { # url 文件名
    local url="$1" name="$2" f="$lib/$2"
    if [ -s "$f" ]; then echo "  ✓ $name"; return; fi
    curl -fsSL --max-time 180 -o "$f" "$url" && echo "  ↓ $name ($(stat -c%s "$f") 字节)" \
        || { echo "  ✗ $name ← $url" >&2; rm -f "$f"; return 0; }
}

get_aar() { # url 文件名（从 aar 里抽 classes.jar）
    local url="$1" name="$2" f="$lib/$2"
    if [ -s "$f" ]; then echo "  ✓ $name"; return; fi
    if curl -fsSL --max-time 180 -o "$TMP/a.aar" "$url"; then
        mkdir -p "$TMP/x" && unzip -oq "$TMP/a.aar" classes.jar -d "$TMP/x" \
            && mv "$TMP/x/classes.jar" "$f" && echo "  ↓ $name ($(stat -c%s "$f") 字节)" \
            || echo "  ✗ $name 里没有 classes.jar" >&2
        rm -rf "$TMP/x"
    else
        echo "  ✗ $name ← $url" >&2
    fi
}

keep_aar() { # url 文件名：整包留着（带 res 的库，build.sh 会编它的资源并生成 R 类）
    local url="$1" name="$2" f="$lib/aar/$2"
    if [ -s "$f" ]; then echo "  ✓ aar/$name"; return; fi
    curl -fsSL --max-time 180 -o "$f" "$url" && echo "  ↓ aar/$name ($(stat -c%s "$f") 字节)" \
        || { echo "  ✗ aar/$name ← $url" >&2; rm -f "$f"; return 0; }
}

echo "==> JS 引擎 / 插件宿主（TTS Server 自己用的那几样）"
get "$MC/org/mozilla/rhino/1.8.0/rhino-1.8.0.jar" rhino-1.8.0.jar
get "$MC/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.1/kotlinx-coroutines-core-jvm-1.10.1.jar" kotlinx-coroutines-core-jvm-1.10.1.jar
get "$MC/io/github/oshai/kotlin-logging-jvm/7.0.0/kotlin-logging-jvm-7.0.0.jar" kotlin-logging-jvm-7.0.0.jar
get "$MC/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar" slf4j-api-2.0.16.jar
get "$MC/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" okhttp-4.12.0.jar
get "$MC/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" okio-jvm-3.6.0.jar
get_aar "$JIT/com/github/liangjingkanji/Net/3.7.0/Net-3.7.0.aar" net-3.7.0.jar
get "$MC/cn/hutool/hutool-all/5.8.19/hutool-all-5.8.19.jar" hutool-all-5.8.19.jar
get "$MC/com/michael-bull/kotlin-result/kotlin-result-jvm/2.0.1/kotlin-result-jvm-2.0.1.jar" kotlin-result-jvm-2.0.1.jar
get "$MC/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.8.0/kotlinx-serialization-core-jvm-1.8.0.jar" kotlinx-serialization-core-jvm-1.8.0.jar
get "$MC/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.8.0/kotlinx-serialization-json-jvm-1.8.0.jar" kotlinx-serialization-json-jvm-1.8.0.jar
get "$MC/org/apache/commons/commons-text/1.13.0/commons-text-1.13.0.jar" commons-text-1.13.0.jar
get "$MC/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar" commons-lang3-3.17.0.jar

echo "==> androidx（插件实体/界面那层要的）"
get "$GM/androidx/annotation/annotation-jvm/1.11.0/annotation-jvm-1.11.0.jar" annotation-jvm-1.11.0.jar
get "$GM/androidx/collection/collection-jvm/1.7.0/collection-jvm-1.7.0.jar" collection-jvm-1.7.0.jar
get_aar "$GM/androidx/room/room-common-jvm/2.8.5/room-common-jvm-2.8.5.jar" room-common-jvm-2.8.5.jar
get "$GM/androidx/lifecycle/lifecycle-common/2.11.0/lifecycle-common-2.11.0.jar" lifecycle-common-2.11.0.jar
get_aar "$GM/androidx/versionedparcelable/versionedparcelable/1.2.1/versionedparcelable-1.2.1" versionedparcelable-1.2.1.jar
get_aar "$GM/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1" savedstate-1.2.1.jar
get_aar "$GM/androidx/core/core/1.13.1/core-1.13.1.aar" core-1.13.1.jar
get_aar "$GM/androidx/core/core-ktx/1.13.1/core-ktx-1.13.1" core-ktx-1.13.1.jar
get_aar "$GM/androidx/activity/activity/1.9.3/activity-1.9.3.aar" activity-1.9.3.jar
get_aar "$GM/androidx/fragment/fragment/1.8.5/fragment-1.8.5.aar" fragment-1.8.5.jar
get_aar "$GM/androidx/lifecycle/lifecycle-runtime/2.8.7/lifecycle-runtime-2.8.7" lifecycle-runtime-2.8.7.jar
get_aar "$GM/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.8.7/lifecycle-viewmodel-savedstate-2.8.7" lifecycle-viewmodel-savedstate-2.8.7.jar
get_aar "$GM/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1" annotation-experimental-1.4.1.jar

echo "==> media3 不下了（读采样率走平台 MediaExtractor，用不着它；详见 tools/lib/SOURCES.md）"

echo "==> 代码编辑器（TTS Server 的插件编辑器用的就是它）"
keep_aar "$MC/io/github/Rosemoe/sora-editor/editor/0.23.5/editor-0.23.5.aar" sora-editor-0.23.5.aar

echo "==> Kotlin 标准库跟着 kotlinc 走，不在这儿下（版本必须和编译器一致）"
echo "==> 收工："
ls -1 "$lib" | grep -v '^aar$' | sed 's/^/    /'
echo "    aar/: $(ls -1 "$lib/aar" 2>/dev/null | tr '\n' ' ')"
