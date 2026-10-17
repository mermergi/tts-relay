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
get_aar "$GM/androidx/room/room-common-jvm/2.8.5/room-common-jvm-2.8.5.aar" room-common-2.8.5.jar
get "$GM/androidx/lifecycle/lifecycle-common/2.11.0/lifecycle-common-2.11.0.jar" lifecycle-common-2.11.0.jar
get_aar "$GM/androidx/versionedparcelable/versionedparcelable/1.2.1/versionedparcelable-1.2.1" versionedparcelable-1.2.1.jar
get_aar "$GM/androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1" savedstate-1.2.1.jar
get_aar "$GM/androidx/lifecycle/lifecycle-runtime/2.8.7/lifecycle-runtime-2.8.7" lifecycle-runtime-2.8.7.jar
get_aar "$GM/androidx/lifecycle/lifecycle-viewmodel-android/2.8.7/lifecycle-viewmodel-android-2.8.7.aar" lifecycle-viewmodel-2.8.7.jar
get_aar "$GM/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.8.7/lifecycle-viewmodel-savedstate-2.8.7" lifecycle-viewmodel-savedstate-2.8.7.jar
get_aar "$GM/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1" annotation-experimental-1.4.1.jar

echo "==> 界面这一层：Material Components + AppCompat + RecyclerView（都是整包 aar，资源要编进去）"
keep_aar "$GM/com/google/android/material/material/1.12.0/material-1.12.0.aar" material-1.12.0.aar
keep_aar "$GM/androidx/appcompat/appcompat/1.7.0/appcompat-1.7.0.aar" appcompat-1.7.0.aar
keep_aar "$GM/androidx/appcompat/appcompat-resources/1.7.0/appcompat-resources-1.7.0.aar" appcompat-resources-1.7.0.aar
keep_aar "$GM/androidx/recyclerview/recyclerview/1.3.2/recyclerview-1.3.2.aar" recyclerview-1.3.2.aar
keep_aar "$GM/androidx/core/core/1.13.1/core-1.13.1.aar" core-1.13.1.aar
keep_aar "$GM/androidx/core/core-ktx/1.13.1/core-ktx-1.13.1.aar" core-ktx-1.13.1.aar
keep_aar "$GM/androidx/activity/activity/1.9.3/activity-1.9.3.aar" activity-1.9.3.aar
keep_aar "$GM/androidx/fragment/fragment/1.8.5/fragment-1.8.5.aar" fragment-1.8.5.aar
keep_aar "$GM/androidx/cardview/cardview/1.0.0/cardview-1.0.0.aar" cardview-1.0.0.aar
keep_aar "$GM/androidx/coordinatorlayout/coordinatorlayout/1.2.0/coordinatorlayout-1.2.0.aar" coordinatorlayout-1.2.0.aar
keep_aar "$GM/androidx/drawerlayout/drawerlayout/1.2.0/drawerlayout-1.2.0.aar" drawerlayout-1.2.0.aar
keep_aar "$GM/androidx/dynamicanimation/dynamicanimation/1.1.0/dynamicanimation-1.1.0.aar" dynamicanimation-1.1.0.aar
keep_aar "$GM/androidx/transition/transition/1.5.0/transition-1.5.0.aar" transition-1.5.0.aar
keep_aar "$GM/androidx/vectordrawable/vectordrawable/1.2.0/vectordrawable-1.2.0.aar" vectordrawable-1.2.0.aar
keep_aar "$GM/androidx/vectordrawable/vectordrawable-animated/1.2.0/vectordrawable-animated-1.2.0.aar" vectordrawable-animated-1.2.0.aar
keep_aar "$GM/androidx/cursoradapter/cursoradapter/1.0.0/cursoradapter-1.0.0.aar" cursoradapter-1.0.0.aar
keep_aar "$GM/androidx/customview/customview/1.1.0/customview-1.1.0.aar" customview-1.1.0.aar
keep_aar "$GM/androidx/loader/loader/1.1.0/loader-1.1.0.aar" loader-1.1.0.aar
keep_aar "$GM/androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.aar" viewpager-1.0.0.aar
keep_aar "$GM/androidx/viewpager2/viewpager2/1.1.0/viewpager2-1.1.0.aar" viewpager2-1.1.0.aar
keep_aar "$GM/androidx/constraintlayout/constraintlayout/2.1.4/constraintlayout-2.1.4.aar" constraintlayout-2.1.4.aar
get "$GM/androidx/constraintlayout/constraintlayout-core/1.0.4/constraintlayout-core-1.0.4.jar" constraintlayout-core-1.0.4.jar

echo "==> media3 不下了（读采样率走平台 MediaExtractor，用不着它；详见 tools/lib/SOURCES.md）"

echo "==> 代码编辑器（TTS Server 的插件编辑器用的就是它）"
keep_aar "$GM/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.aar" customview-poolingcontainer-1.0.0.aar
keep_aar "$GM/androidx/emoji2/emoji2/1.3.0/emoji2-1.3.0.aar" emoji2-1.3.0.aar
keep_aar "$GM/androidx/emoji2/emoji2-views-helper/1.2.0/emoji2-views-helper-1.2.0.aar" emoji2-views-helper-1.2.0.aar
keep_aar "$GM/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.aar" interpolator-1.0.0.aar
keep_aar "$GM/androidx/tracing/tracing/1.0.0/tracing-1.0.0.aar" tracing-1.0.0.aar
keep_aar "$GM/androidx/startup/startup-runtime/1.0.0/startup-runtime-1.0.0.aar" startup-runtime-1.0.0.aar
keep_aar "$GM/androidx/profileinstaller/profileinstaller/1.3.1/profileinstaller-1.3.1.aar" profileinstaller-1.3.1.aar
keep_aar "$GM/androidx/documentfile/documentfile/1.0.0/documentfile-1.0.0.aar" documentfile-1.0.0.aar
keep_aar "$GM/androidx/lifecycle/lifecycle-runtime-android/2.8.7/lifecycle-runtime-android-2.8.7.aar" lifecycle-runtime-2.8.7.aar
keep_aar "$GM/androidx/versionedparcelable/versionedparcelable/1.2.1/versionedparcelable-1.2.1.aar" versionedparcelable-1.2.1.aar
keep_aar "$GM/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.aar" annotation-experimental-1.4.1.aar
keep_aar "$GM/androidx/lifecycle/lifecycle-livedata/2.0.0/lifecycle-livedata-2.0.0.aar" lifecycle-livedata-2.0.0.aar
keep_aar "$GM/androidx/lifecycle/lifecycle-livedata-core/2.6.1/lifecycle-livedata-core-2.6.1.aar" lifecycle-livedata-core-2.6.1.aar
keep_aar "$GM/androidx/lifecycle/lifecycle-process/2.4.1/lifecycle-process-2.4.1.aar" lifecycle-process-2.4.1.aar
keep_aar "$GM/androidx/localbroadcastmanager/localbroadcastmanager/1.0.0/localbroadcastmanager-1.0.0.aar" localbroadcastmanager-1.0.0.aar
keep_aar "$GM/androidx/legacy/legacy-support-core-utils/1.0.0/legacy-support-core-utils-1.0.0.aar" legacy-support-core-utils-1.0.0.aar
keep_aar "$GM/androidx/print/print/1.0.0/print-1.0.0.aar" print-1.0.0.aar
get "$GM/androidx/arch/core/core-common/2.0.0/core-common-2.0.0.jar" core-common-2.0.0.jar
get_aar "$GM/androidx/arch/core/core-runtime/2.1.0/core-runtime-2.1.0.aar" core-runtime-2.1.0.jar
get "$GM/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar" concurrent-futures-1.0.0.jar
get "$MC/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar" listenablefuture-1.0.jar
get "$GM/androidx/resourceinspection/resourceinspection-annotation/1.0.1/resourceinspection-annotation-1.0.1.jar" resourceinspection-annotation-1.0.1.jar

keep_aar "$MC/io/github/Rosemoe/sora-editor/editor/0.23.5/editor-0.23.5.aar" sora-editor-0.23.5.aar

echo "==> Kotlin 标准库跟着 kotlinc 走，不在这儿下（版本必须和编译器一致）"
echo "==> 收工："
ls -1 "$lib" | grep -v '^aar$' | sed 's/^/    /'
echo "    aar/: $(ls -1 "$lib/aar" 2>/dev/null | tr '\n' ' ')"
