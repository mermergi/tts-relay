package com.mermergi.ttsrelay;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉面板的「中转开关」磁贴：点一下开中转，再点一下关。
 * 状态跟服务真实状态走 —— 服务起停时主动 requestListeningState 让磁贴刷新，
 * 不靠磁贴自己猜（App 里开关中转，磁贴也要跟着变色）。
 */
public class RelayTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();   // 下拉面板每次可见都会来一趟
    }

    @Override
    public void onClick() {
        boolean on = serverUp();
        Intent i = new Intent(this, RelayService.class);
        i.setAction(on ? RelayService.ACTION_STOP : RelayService.ACTION_START);
        // 磁贴点击自带短暂前台资格，startForegroundService 两条路都稳
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        refresh();   // 乐观翻面；服务的 requestListeningState 随后纠偏
    }

    /** 服务起停时喊一声，磁贴状态立刻跟上（配 RelayService.notifyTile()）。 */
    static void requestRefresh(android.content.Context c) {
        TileService.requestListeningState(c,
                new android.content.ComponentName(c, RelayTile.class));
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = serverUp();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(on ? "端口 " + Prefs.port(this) : "未运行");
        t.updateTile();
    }

    private boolean serverUp() {
        RelayService s = RelayService.instance();
        return s != null && s.serverUp();
    }
}
