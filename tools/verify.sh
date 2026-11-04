#!/data/data/com.termux/files/usr/bin/bash
# 一键回归：静态断言 + 装机 + HTTP 基线 + UI 冒烟。
#   ./tools/verify.sh             # 静态断言 + 基线（app 已在跑的情形）
#   ./tools/verify.sh --install   # 先装 out/ttsrelay.apk、拉起 app，再做全套
#   ./tools/verify.sh --build     # 先编译（含 --install 则编译并安装）
# 任何一步失败都非零退出；基线目录 .ref-baseline/ 由首次基线采集生成。
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$root/out"
base="$root/.ref-baseline"
PKG=com.mermergi.ttsrelay
ACT=$PKG/.MainActivity
URL=http://127.0.0.1:8788
fail=0

say()  { printf '%s\n' "$*"; }
bad()  { printf '  ✗ %s\n' "$*"; fail=1; }
good() { printf '  ✓ %s\n' "$*"; }

# ── 编译 / 安装 ──────────────────────────────────────────────
case "${1:-}" in
  --build)  bash "$here/build.sh" | tail -1 ;;
  --build-install|--install)  # --build-install = 编译并安装
            [ "${1:-}" = "--build-install" ] && bash "$here/build.sh" | tail -1
            adb install -r "$out/ttsrelay.apk" >/dev/null || { bad "安装失败"; exit 1; }
            adb shell am start -n "$ACT" >/dev/null
            ;;
esac

# ── 1. 静态断言（不依赖设备）────────────────────────────────
say "── 静态断言 ──"
n=$(grep -c "tabs\[[0-4]\] = " "$root/src/com/mermergi/ttsrelay/MainActivity.java")
[ "$n" -eq 5 ] && good "五页装配齐全（tabs[i] = … 共 $n 处）" || bad "装配行只有 $n 处（应 5）—— 装配缺失会点页崩溃"
for f in Tab LogTab HomeTab SpeakTab SettingsTab VoicePicker EngineTab EngineEditor; do
    p="$root/src/com/mermergi/ttsrelay/tab/$f.java"
    [ -f "$p" ] || { bad "缺 tab/$f.java"; continue; }
    if grep -q "MainActivity" "$p"; then
        # 只允许出现在注释里
        grep "MainActivity" "$p" | grep -vq "^\s*\*\|^\s*//" && bad "tab/$f.java 引用了壳类" || true
    fi
done
good "tab/ 协调器齐备且不反向依赖壳"
if [ -d "$base" ]; then
    for want in status-v291.json split-m0.json split-m1.json split-m2.json split-m3.json split-m4.json voices.json; do
        [ -f "$base/$want" ] || bad "基线缺 $base/$want"
    done
    [ $fail -eq 0 ] && good "基线文件齐全"
else
    say "  （无 $base —— 基线未采集，跳过 HTTP 对比；首次采集见文末说明）"
fi

# ── 2. 设备在不在 ────────────────────────────────────────────
if ! adb get-state >/dev/null 2>&1; then
    say "── 设备 ──"
    say "  （无 adb 设备 —— 跳过装机与冒烟；静态断言已跑完）"
    exit $fail
fi

# ── 3. 等服务上线 ────────────────────────────────────────────
say "── 服务上线 ──"
up=""
for i in $(seq 1 15); do
    if curl -sS --max-time 2 "$URL/_status" >/dev/null 2>&1; then up=1; break; fi
    sleep 1
done
if [ -z "$up" ]; then
    adb shell am start-foreground-service -n "$PKG/.RelayService" >/dev/null 2>&1 || true
    for i in $(seq 1 10); do
        curl -sS --max-time 2 "$URL/_status" >/dev/null 2>&1 && up=1 && break
        sleep 1
    done
fi
[ -n "$up" ] && good "中转在线（$URL）" || { bad "中转没起来 —— HTTP 基线没法跑"; exit 1; }

# ── 4. HTTP 基线 ─────────────────────────────────────────────
if [ -d "$base" ]; then
    say "── HTTP 基线 ──"
    for m in 0 1 2 3 4; do
        if curl -sS --max-time 5 "$URL/_split?mode=$m" | diff -q - "$base/split-m$m.json" >/dev/null; then
            good "split-m$m"
        else bad "split-m$m 与基线不一致"; fi
    done
    new=$(mktemp)
    curl -sS --max-time 5 "$URL/_status" | python3 -c "
import json,sys
d1=json.load(sys.stdin); d2=json.load(open('$base/status-v291.json'))
skip=('spoken','chars','live','queue','last','ttsInflight','ttsInflightMax')
k1={k:v for k,v in d1.items() if k not in skip}; k2={k:v for k,v in d2.items() if k not in skip}
print('same' if k1==k2 else 'DIFF '+str({k:(k2.get(k),k1.get(k)) for k in set(k1)|set(k2) if k1.get(k)!=k2.get(k)}))" > "$new" 2>&1
    if grep -q "^same$" "$new"; then good "status"; else bad "status：$(cat "$new")"; fi
    if curl -sS --max-time 8 "$URL/_voices" | diff -q - "$base/voices.json" >/dev/null; then
        good "voices"
    else bad "voices 与基线不一致"; fi
    rm -f "$new"
fi

# ── 5. UI 冒烟：五页逐一点穿 ─────────────────────────────────
say "── UI 冒烟 ──"
pid0=$(adb shell pidof $PKG | tr -d '\r')
[ -n "$pid0" ] || { bad "app 进程不在 —— 先 am start"; exit 1; }
adb shell am start -n "$ACT" >/dev/null   # 拉到前台：dump 抓的必须是本 app 的窗口
sleep 1
adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1 || { bad "uiautomator dump 失败"; exit 1; }
adb pull /sdcard/uidump.xml "$out/uidump.xml" >/dev/null || { bad "拉取 uidump 失败"; exit 1; }
python3 - "$out/uidump.xml" <<'EOF' > "$out/tabs.txt" || { bad "解析 tab 坐标失败"; exit 1; }
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
ok = True
for name in ["首页", "朗读", "设置", "引擎", "日志"]:
    # text 在前、package/bounds 在后是 uiautomator 的固定属性序；限定本 app，防抓到别的窗口
    m = re.search(r'text="%s"[^>]*package="com\.mermergi\.ttsrelay"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % name, s)
    if not m: ok = False; continue
    print(name, (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
sys.exit(0 if ok else 1)
EOF
mapfile -t TABS < "$out/tabs.txt"
for line in "${TABS[@]}"; do
    name=${line%% *}; rest=${line#* }; x=${rest%% *}; y=${rest##* }
    adb shell input tap "$x" "$y" </dev/null
    sleep 2
    pid=$(adb shell pidof $PKG </dev/null | tr -d '\r')
    if [ "$pid" = "$pid0" ]; then good "切「$name」进程存活"; else bad "切「$name」后进程异常（$pid0 → ${pid:-无}）"; fi
done
newfatal=$(adb logcat -d -t 300 2>/dev/null | grep "FATAL EXCEPTION" | wc -l)
[ "$newfatal" -eq 0 ] && good "无 FATAL EXCEPTION" || bad "logcat 有 $newfatal 条 FATAL（截取近 300 行）"
adb shell rm -f /sdcard/uidump.xml

# ── 收尾 ─────────────────────────────────────────────────────
say "════════════════════════"
if [ $fail -eq 0 ]; then say "全部通过 ✓"; else say "有失败项 ✗（见上）"; fi
exit $fail

# 首次基线采集（只做一次，之后永远比对它）：
#   1. git worktree add ~/tts-relay-vXXX v2.9.1 && cd ~/tts-relay-vXXX
#   2. 拷 tools/lib + tools/android.jar 过去（tag 的 fetch-deps 依赖不全）后 build --install
#   3. 拉起 app，等中转在线，然后：
#      mkdir -p .ref-baseline
#      curl -sS http://127.0.0.1:8788/_status        > .ref-baseline/status-v291.json
#      for m in 0 1 2 3 4; do curl -sS "http://127.0.0.1:8788/_split?mode=$m" > .ref-baseline/split-m$m.json; done
#      curl -sS http://127.0.0.1:8788/_voices       > .ref-baseline/voices.json
