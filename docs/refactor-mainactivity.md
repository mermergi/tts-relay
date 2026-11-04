# MainActivity 拆分设计方案

> 目标：把 `MainActivity.java`（3105 行）拆成「外壳 + 五个 Tab 协调器 + 共享控件库」，
> 拆完 MainActivity 壳 ≤ 400 行，每个文件 ≤ 600 行，**行为零变化**。
> 本方案只描述拆分，不夹带任何功能改动。

---

## 一、现状盘点（为什么要这么拆）

### 1.1 实际行数分布（不是均匀的五等分）

| 区段 | 行号 | 行数 | 归属 |
|---|---|---|---|
| 生命周期 | 142–184 | ~43 | 壳 |
| 外壳（tab 栏 / switchTab / clearRefs） | 185–315 | ~131 | 壳 |
| 首页 pageHome | 316–453 | ~138 | 首页 Tab |
| 朗读 pageSpeak + Sl 滑杆助手 | 454–676 | ~222 | 朗读 Tab |
| 设置 pageSettings + 预设 chip + 主题 | 676–910 | ~235 | 设置 Tab |
| **引擎**（页面+导入导出+编辑器+插件面板+登录） | 911–2108 | **~1198** | 引擎 Tab |
| 日志 pageLog | 2109–2152 | ~44 | 日志 Tab |
| 全局刷新 refresh() | 2153–2234 | ~82 | 跨 Tab（要拆） |
| 行为 toggle/pause/mute/skip | 2235–2289 | ~55 | 首页 Tab |
| 读写设置 collect/save/sync | 2290–2367 | ~78 | 设置 Tab |
| 断句预览 renderPreview | 2368–2390 | ~23 | 朗读 Tab |
| **音色/试听/绑定表/自检** | 2391–3069 | **~679** | 朗读 Tab |
| 杂项 copy/lanIp/toast | 3070–3105 | ~36 | 共享 |

关键结论：
1. **不是五等分**。引擎区 1198 行、朗读合计 ~900 行，这两个是大头，必须二次拆分。
2. **`refresh()` 是全局分发器**：一个函数摸所有 tab 的 view 引用，全靠 `!= null` 判空。
   拆分后必须变成「每个 Tab 自己的 refresh()」。
3. **`clearRefs()` 手工列了 ~40 个字段**置空。新增字段忘记同步这行 = 残留引用 bug。
   这类错必须靠机制消灭，不能靠记性。

### 1.2 必须保持的既有语义

- 页面视图**每次进 tab 重新构建**，退出时引用置空（`clearRefs` + `switchTab`）。
  拆分**不引入** view 缓存/复用，避免新状态 bug。
- 所有 UI 构建是纯代码（无 XML layout），继续如此。
- 后台线程只做 IO，UI 更新一律回主线程——现状如此，拆分后由机制强制。

---

## 二、目标结构

```
src/com/mermergi/ttsrelay/
├── MainActivity.java        ~300 行：生命周期、tab 栏、Tab[] 装配、Host 实现
├── ui/
│   ├── Widgets.java         ~250：scroll/topGap/kvRow/paramSlider/ChoiceSheet 接线/toast 封装
│   ├── Form.java            ~150：Fld 表单模型 + buildForm/applyEditor（引擎编辑表单用）
│   └── SliderRow.java       ~80：现内部类 Sl 原样搬出
├── tab/
│   ├── Tab.java             ~30：Tab 契约接口 + TabHost 接口
│   ├── HomeTab.java         ~300：首页大卡 + 状态统计 + 启停/暂停/静音 + 局域网开关
│   ├── SpeakTab.java        ~450：音色/语速音调音量/断句方案/预览
│   ├── VoicePicker.java     ~500：音色对话框、分角色绑定表、bind/newBinding、试听、自检
│   ├── SettingsTab.java     ~350：上游预设 chips、端口、skip 规则、主题
│   ├── EngineTab.java       ~450：引擎列表、增删、菜单、导入导出
│   ├── EngineEditor.java    ~450：editEngine/buildForm/editCookie/addCfgKey/showEngineVoices
│   ├── PluginSheet.java     ~300：pluginSheet/bindLangRow/bindVoiceRow/插件界面
│   └── LogTab.java          ~120：日志轮询渲染
└── （其余类不动）
```

拆分原则：
- **按「归属」不按「行号」**：行为区（2235+）里的 toggle 属于首页、读写设置属于设置、
  音色区（2391+）属于朗读——它们只是历史上被排在文件尾部。
- **引擎二次拆**：EngineTab（列表与导入导出）/ EngineEditor（单个引擎的编辑）
  / PluginSheet（插件面板）是三个不同的屏幕级关注点，各 ≤ 500 行。
- **VoicePicker 独立**：音色选择 + 分角色绑定是朗读 tab 里最独立、最复杂的状态机，
  单独成类后 SpeakTab 才能瘦身到可维护。

---

## 三、Tab 契约（规范化的核心）

```java
// tab/Tab.java
public interface Tab {
    /** 进 tab 时构建整页视图（幂等，每次重建，与现状一致） */
    android.view.View build(android.content.Context ctx);
    /** 运行态变化时刷新（view 引用可能为 null —— 不在当前页） */
    void refresh();
    /** 离开 tab 时置空全部 view 引用 */
    void clearRefs();
}
```

```java
// 协调器对外的全部能力都走这个接口 —— 不许持有 Activity 引用
public interface TabHost {
    void toast(String s);
    int dialogTheme();                 // pickTheme/dialogTheme 依赖 Activity 主题
    void runOnUi(Runnable r);          // 后台线程回主线程的唯一通道
    void requestRefresh();             // 某个 Tab 改了共享状态，请壳分发刷新
    android.app.Activity activity();   // 仅限 startActivity / 落盘 SAF 这类必须用 Activity 的场景
}
```

### 规则（写进 code review 清单）

1. **协调器零 Activity 字段**。只允许 `Context`（构建视图）+ `TabHost`（能力）+
   三个数据源：`Prefs` / `EngineStore` / `RelayService.instance()`。
2. **View 引用统一登记**：协调器里所有跨方法使用的 view 一律
   `refs.add(v)` 注册进一个 `List<View>`，`clearRefs()` 就一行
   `for (View v : refs) v 里的字段置空`。
   **废除手工字段清单**——这一条直接消灭 clearRefs 漏项这个 bug 类。
3. **跨 Tab 联动只走 `requestRefresh()`**：例如引擎页改了音色 → 壳调用所有 tab 的
   `refresh()`，不在当前页的 tab 靠 null 判空自然跳过。禁止协调器互相调用。
4. **后台线程规则**：协调器里可以 `new Thread` 做 IO（拉音色列表、导出文件），
   但**碰 view 必须先 `host.runOnUi(...)`**。review 时 grep `coord/|tab/` 下的
   `new Thread`，逐个确认包裹了 runOnUi。
5. **对话框归属**：哪个 tab 弹的对话框归哪个协调器，dismiss 时若涉及共享状态
   改动，结尾必须 `host.requestRefresh()`。

---

## 四、迁移步骤（每步一个 commit，可编译可安装可回滚）

| # | commit | 内容 | 拆出后 MainActivity 行数 |
|---|---|---|---|
| 1 | `ui: 抽共享控件` | Sl→ui/SliderRow、Fld→ui/Form、kvRow/paramSlider/scroll/topGap/toast→ui/Widgets。MainActivity 改为调用，行为不变 | ~2600 |
| 2 | `tab: 契约与日志页(试点)` | 建 tab/Tab.java + TabHost；拆 LogTab（44 行，最小，验证契约闭环） | ~2500 |
| 3 | `tab: 首页` | pageHome + toggle/pause/mute/skip/lanIp → HomeTab；refresh() 中首页部分移入 | ~2250 |
| 4 | `tab: 设置` | pageSettings + 预设 chips + 主题 + collect/saveInputs/syncInputs → SettingsTab | ~1900 |
| 5 | `tab: 朗读` | pageSpeak + 断句预览 → SpeakTab；音色/试听/绑定/自检 → VoicePicker（本次最大，注意 refs 登记） | ~1000 |
| 6 | `tab: 引擎-列表` | pageEngine + 增删/菜单/导入导出 → EngineTab | ~500 |
| 7 | `tab: 引擎-编辑器与插件` | editEngine/buildForm/editCookie/addCfgKey/showEngineVoices → EngineEditor；pluginSheet/bindXxxRow → PluginSheet。MainActivity 收官 | ~300 |

### 每步的固定流程

1. 纯搬家：方法体不改逻辑、不改文案、不改线程边界；
2. `./tools/build.sh` 编过；
3. `./tools/build.sh --install` 装机，按附录冒烟清单过一遍该 tab；
4. commit（信息格式见上表），**不与任何功能改动混合提交**。

---

## 五、验收标准

- [ ] MainActivity ≤ 400 行，且不再含任何页面构建代码；
- [ ] `tab/` 下每个文件 ≤ 600 行；`ui/` 下每个文件 ≤ 300 行；
- [ ] `grep -n "MainActivity" tab/*.java` 只在构造参数处出现 0 次（协调器不持有 Activity）；
- [ ] `grep -n "new Thread" tab/*.java` 的每一处，20 行内有 `runOnUi` 包裹 UI 写入；
- [ ] `clearRefs()` 不再出现手工字段清单（全部走 refs 登记表）；
- [ ] 冒烟清单五页全过（见附录）；
- [ ] `refresh()` 全局函数消失，等价逻辑分散到各 Tab.refresh()。

### 行为不变的判定方法（不靠肉眼）

- `curl 127.0.0.1:8788/_status`：拆分前后字段一致；
- `curl 127.0.0.1:8788/_split?mode=1`：输出与拆分前 diff 为空；
- `curl 127.0.0.1:8788/_selftest`：通过；
- 导出 `/_export` 的 JSON，拆分前后 diff 为空。

---

## 六、风险与对策

| 风险 | 对策 |
|---|---|
| 匿名内部类隐式捕获 `MainActivity.this` 的字段（toast/dialogTheme/palette p） | 搬家时逐个改成 `host.xxx` / 构造参数。步骤 2 的试点页就是干这个的，契约没跑顺不往下走 |
| `p`（Palette 取色结果）被 30+ 处引用 | 作为 `build(ctx)` 的参数或协调器构造参数传入，不放静态（现有 Palette 本身按 Activity 取色） |
| 拆到一半停工，留下半拆状态 | 步骤表按 commit 排，每个 commit 都是完整可编译状态；最差停在任意一步，收益已到手 |
| 引擎区 1198 行一次性搬风险大 | 拆成步骤 6/7 两个 commit；编辑器里的网络调用（showEngineVoices）保持原有线程边界 |
| `LAST_TAB` 静态记忆当前页 | 留在壳里，协调器无感知 |

---

## 附录：冒烟清单（每步装完跑）

**首页**：启动/停止中转；暂停/继续；静音/恢复；状态卡文字与 `/_status` 一致；局域网开关联动地址显示。
**朗读**：换音色并试听；语速/音调/音量滑杆即时生效；断句五档切换 + `/_split` 干跑结果一致；分角色绑定表增删改；自检（/_selftest）出声。
**设置**：上游预设 chip 点击/长按删除/自定义；端口修改后重启中转生效；skip 开关保存后 `/_check` 判定变化；主题切换四档。
**引擎**：新增/删除引擎；编辑表单保存后列表副标题变化；导入（文件/剪贴板）/导出 round-trip；插件面板能画出插件界面并保存变量。
**日志**：进入自动加载；`/_log` 内容一致；滚动到底不卡。

---

*版本：v1 · 基于 MainActivity.java @ 728530c（2.9.1，3105 行）*
