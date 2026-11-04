package com.mermergi.ttsrelay.tab;

import android.app.Activity;
import android.content.Context;
import android.view.View;

/**
 * 一个标签页 = 构建 + 刷新 + 松手。
 * 视图每次进页重建、离开置空引用 —— 与旧 MainActivity 单体时的语义完全一致。
 */
public interface Tab {

    /** 进 tab 时构建整页视图（幂等，每次重建）。 */
    View build(Context ctx);

    /** 运行态变化时刷新；不在当前页时 view 引用为 null，自行判空跳过。 */
    void refresh();

    /** 离开 tab 时置空全部 view 引用（防残留引用）。 */
    void clearRefs();

    /**
     * 协调器对外的全部能力都走这个接口 —— 协调器不许持有 Activity 引用。
     * 数据只从三个单一来源读：Prefs / EngineStore / RelayService。
     */
    interface Host {
        void toast(String s);
        void copy(String s);
        int dialogTheme();
        /** 后台线程碰 UI 的唯一通道。 */
        void runOnUi(Runnable r);
        /** 本页改了共享状态，请壳分发刷新（含其它页）。 */
        void requestRefresh();
        /** 仅限 startActivity / SAF 落盘这类非 Activity 不可的场景。 */
        Activity activity();
    }
}
