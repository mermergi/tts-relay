#!/data/data/com.termux/files/usr/bin/bash
# 全程在手机上编译签名「朗读中转」APK
#   ./build.sh           编译 → out/ttsrelay.apk
#   ./build.sh --install 编译完交给系统安装器
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$root/out"; gen="$out/gen"; obj="$out/obj"; dex="$out/dex"

# android.jar 有 26MB，不进仓库。优先环境变量，其次几个常见位置。
android_jar="${ANDROID_JAR:-}"
if [ -z "$android_jar" ] || [ ! -f "$android_jar" ]; then
    for c in "$here/android.jar" \
             "$HOME/.local/share/android-sdk/android.jar" \
             "$HOME/android-sdk/android.jar" \
             "$PREFIX/share/android-sdk/android.jar" \
             "$PREFIX/opt/android-sdk/android.jar"; do
        if [ -f "$c" ]; then android_jar="$c"; break; fi
    done
fi

for tool in aapt2 javac kotlinc d8 apksigner keytool; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少 $tool —— 先跑：pkg install aapt2 apksigner d8 openjdk-17 kotlin" >&2; exit 1; }
done

if [ -z "$android_jar" ] || [ ! -f "$android_jar" ]; then
    cat >&2 <<'EOF'
找不到 android.jar（编译要用的 Android SDK 存根）。

  1) 从任意 Android SDK 里拷一份：platforms/android-34/android.jar
  2) 放到 tools/android.jar，或者设环境变量：
         export ANDROID_JAR=/path/to/android.jar
EOF
    exit 1
fi
echo "==> android.jar = $android_jar"

rm -rf "$out"; mkdir -p "$gen" "$obj" "$dex"

echo "==> aapt2 compile"
aapt2 compile --dir "$root/res" -o "$out/res.zip"

echo "==> aapt2 link"
aapt2 link \
    -o "$out/base.apk" \
    -I "$android_jar" \
    --manifest "$root/AndroidManifest.xml" \
    --java "$gen" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    "$out/res.zip"

echo "==> javac"
mapfile -t sources < <(find "$gen" "$root/src" -name '*.java')
# 依赖的 jar（Rhino / OkHttp / 协程…）：tools/lib/ 下的全带上
libs=()
for j in "$root"/tools/lib/*.jar; do
    [ -f "$j" ] && libs+=("$j")
done
if [ "${#libs[@]}" -eq 0 ]; then
    echo "tools/lib/ 里一个 jar 都没有 —— 先跑：bash tools/fetch-deps.sh" >&2
    exit 1
fi
# Kotlin 标准库跟着 kotlinc 走（自己下容易和编译器错版）
kstdlib="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar"
[ -f "$kstdlib" ] || kstdlib="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
[ -f "$kstdlib" ] && libs+=("$kstdlib")

echo "==> kotlinc（TTS Server 的插件运行时 kt/ttsrv-script + 协议层 kt/app）"
cp_kt="$android_jar"
for j in "${libs[@]}"; do cp_kt="$cp_kt:$j"; done
mapfile -t kt_sources < <(find "$root/kt" -name '*.kt')
kotlinc -jvm-target 1.8 -nowarn -classpath "$cp_kt" -d "$out/kt.jar" "${kt_sources[@]}" >"$out/kotlinc.log" 2>&1 || {
    tail -40 "$out/kotlinc.log" >&2; echo "kotlinc 失败（见 $out/kotlinc.log）" >&2; exit 1; }
libs+=("$out/kt.jar")

cp_android="$android_jar"
for j in "${libs[@]:-}"; do [ -n "$j" ] && cp_android="$cp_android:$j"; done
set +e
javac -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$android_jar" -classpath "$cp_android" \
    -d "$obj" -nowarn "${sources[@]}" >"$out/javac.log" 2>&1
st=$?
set -e
grep -vE '^warning: \[|^Note: ' "$out/javac.log" || true
[ "$st" -eq 0 ] || { echo "javac 失败（见 $out/javac.log）" >&2; exit 1; }

echo "==> d8"
mapfile -t classes < <(find "$obj" -name '*.class')
d8 --lib "$android_jar" --min-api 26 --output "$dex" "${classes[@]}" "${libs[@]:-}"

echo "==> 依赖 jar 里的资源"
# d8 只吃 .class，jar 里的资源（Rhino 的 Messages.properties 这类）会被丢掉，
# 于是运行时 ResourceBundle 找不到、抛 MissingResourceException。所以手动捞进 APK。
resdir="$out/jarres"
mkdir -p "$resdir"
for j in "${libs[@]:-}"; do
    [ -n "$j" ] || continue
    unzip -o -q "$j" -x 'META-INF/*' -d "$resdir" || true
done
find "$resdir" -name '*.class' -delete 2>/dev/null || true
find "$resdir" -type d -empty -delete 2>/dev/null || true
echo "    $(find "$resdir" -type f 2>/dev/null | wc -l) 个资源文件"

echo "==> package"
cp "$out/base.apk" "$out/ttsrelay-unsigned.apk"
python3 - "$out/ttsrelay-unsigned.apk" "$dex/classes.dex" "$resdir" <<'PY'
import sys, zipfile, os
apk, dex, resdir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    if os.path.isdir(resdir):
        for root, dirs, files in os.walk(resdir):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, resdir))
PY

echo "==> sign"
ks="$root/keystore.jks"
if [ ! -f "$ks" ]; then
    keytool -genkeypair -v -keystore "$ks" -alias ttsrelay \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass ttsrelay -keypass ttsrelay \
        -dname "CN=TTS Relay, OU=local, O=local, L=local, S=local, C=CN" >/dev/null
fi
apksigner sign --ks "$ks" --ks-key-alias ttsrelay \
    --ks-pass pass:ttsrelay --key-pass pass:ttsrelay \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$out/ttsrelay.apk" "$out/ttsrelay-unsigned.apk"
apksigner verify --print-certs "$out/ttsrelay.apk" | head -3

ls -la "$out/ttsrelay.apk"
echo "OK -> $out/ttsrelay.apk"

[ "${1:-}" = "--install" ] && adb install -r "$out/ttsrelay.apk"
exit 0
