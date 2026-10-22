# M3 配色生成器（从种子色算整套角色）

`res/values/styles.xml` 里那套 M3 角色值不是手调的，是用 **material-1.12.0.aar 里自带的官方
色料工具**（`com.google.android.material.color.utilities`：HCT + `SchemeTonalSpot`，
也就是 Android 12/13 上 Material You 用的那套算法）从种子色算出来的。

换品牌色 / 想核对某个值怎么来的，重跑一遍：

```bash
cd ~/tts-relay-app

# 1) 从 aar 里取出 classes.jar（里面有 color.utilities 包）
mkdir -p "$PREFIX/tmp/m3gen" && cd "$PREFIX/tmp/m3gen"
unzip -o -q ~/tts-relay-app/tools/lib/aar/material-1.12.0.aar classes.jar
javac -cp classes.jar -d . ~/tts-relay-app/tools/m3-scheme/Gen.java
java -cp classes.jar:. Gen
```

输出两段（LIGHT / DARK），每行 `<item name="colorXxx">#AARRGGBB</item>`，
直接粘进 `styles.xml` 对应主题即可。

改种子色：`Gen.java` 里 `int seed = 0xFF4F6BFF;`（现在用的是本应用的品牌蓝）。

注意：

- 这里用的是 **TonalSpot**（Material You 默认方案，偏中性）；想要保留更多品牌彩度可以换
  `SchemeContent` / `SchemeVibrant` / `SchemeExpressive`（同一个包里都有）。
- 颜色随库版本可能微调，升级 `material-*.aar` 后重跑一次对比，别凭记忆改。
- Android 12+ 真机上这些静态值会被 `DynamicColors` 用壁纸色覆盖（见 `TtsApp.java`），
  所以这里算的是"没有壁纸色时"的样子。
