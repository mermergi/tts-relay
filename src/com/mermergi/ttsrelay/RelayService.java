package com.mermergi.ttsrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：把中转和发声器挂在系统上，划掉任务卡片也不会死。 */
public final class RelayService extends Service {

    public static final String ACTION_START = "com.mermergi.ttsrelay.START";
    public static final String ACTION_STOP = "com.mermergi.ttsrelay.STOP";
    /** 通知栏快捷开关。 */
    public static final String ACTION_PAUSE = "com.mermergi.ttsrelay.PAUSE";
    public static final String ACTION_MUTE = "com.mermergi.ttsrelay.MUTE";
    public static final String ACTION_SKIP = "com.mermergi.ttsrelay.SKIP";

    private static final String CHANNEL = "relay";
    private static final int NID = 4141;

    private static volatile RelayService INSTANCE;

    private HttpServer server;
    private Speaker speaker;
    private final android.os.Handler watch = new android.os.Handler();
    private final Runnable watchdog = new Runnable() {
        public void run() {
            try {
                if (Prefs.wanted(RelayService.this)) {
                    if (speaker == null) {
                    speaker = new Speaker(RelayService.this);
                    speaker.start();
                    // 念到哪句/暂停/队列变了 → 通知立刻跟上，不用等 15 秒看门狗
                    Speaker.addListener(new Speaker.Listener() {
                        public void onSpeakerChanged() { updateNotification(); }
                    });
                }
                    if (server == null) server = new HttpServer(RelayService.this, speaker);
                    if (!server.isRunning()) {
                        LogBus.add("看门狗：中转掉了，自动拉起");
                        server.start(Prefs.port(RelayService.this));
                    }
                    updateNotification();
                }
            } catch (Throwable t) {
                LogBus.add("看门狗异常：" + t.getMessage());
            }
            watch.postDelayed(this, 15000);
        }
    };

    public static RelayService instance() { return INSTANCE; }

    public static boolean isUp() {
        RelayService s = INSTANCE;
        return s != null && s.server != null && s.server.isRunning();
    }

    public static HttpServer serverOrNull() {
        RelayService s = INSTANCE;
        return s == null ? null : s.server;
    }

    public static Speaker speakerOrNull() {
        RelayService s = INSTANCE;
        return s == null ? null : s.speaker;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        App.install(this);
        INSTANCE = this;
        LogBus.init(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopEverything();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(a)) {
            Speaker sp = speaker;
            if (sp != null) { if (sp.isPaused()) sp.resume(); else sp.pause(); }
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_SKIP.equals(a)) {
            Speaker sp = speaker;
            if (sp != null) sp.skipCurrent();   // 这句不想听，后面的照听
            return START_STICKY;
        }
        if (ACTION_MUTE.equals(a)) {
            boolean m = !Prefs.muted(this);
            Prefs.setMuted(this, m);
            Speaker sp = speaker;
            if (m && sp != null) sp.interrupt();
            updateNotification();
            return START_STICKY;
        }
        startEverything();
        return START_STICKY;
    }

    /**
     * Speaker 的唯一出生点。曾经有两个创建点，其中一个忘了挂通知监听 ——
     * onStartCommand 先到就把看门狗的注册跳了，通知从此变聋。收拢成一处。
     */
    private void ensureSpeaker() {
        if (speaker == null) {
            speaker = new Speaker(this);
            speaker.start();
            // 念到哪句/暂停/队列变了 → 通知立刻跟上，不用等 15 秒看门狗
            Speaker.addListener(new Speaker.Listener() {
                public void onSpeakerChanged() { updateNotification(); }
            });
        }
    }

    private void startEverything() {
        startForegroundCompat("正在启动…");
        Prefs.setWanted(this, true);
        ensureSpeaker();
        if (server == null) server = new HttpServer(this, speaker);
        if (!server.isRunning()) {
            try {
                server.start(Prefs.port(this));
                LogBus.add("监听 127.0.0.1:" + server.port() + "  →  " + Prefs.upstream(this));
            } catch (Throwable t) {
                LogBus.add("启动失败：" + t.getMessage()
                        + "（端口被占用？换个端口再试）");
            }
        }
        updateNotification();
        watch.removeCallbacks(watchdog);
        watch.postDelayed(watchdog, 15000);
    }

    private void stopEverything() {
        watch.removeCallbacks(watchdog);
        if (server != null) { server.stop(); server = null; }
        if (speaker != null) { speaker.stop(); speaker = null; }
        Prefs.setWanted(this, false);
        LogBus.add("中转已关闭");
    }

    public void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean up = server != null && server.isRunning();
        StringBuilder sb = new StringBuilder();
        String title, text;
        if (!up) {
            title = "已停止"; text = "";
        } else {
            String now = speaker == null ? "" : speaker.nowSpeaking();
            boolean speaking = now != null && now.length() > 0;
            // 标题位放状态（App 名系统会显示在标题上面，不占我们的字），正文只留一句短的
            if (Prefs.muted(this))      title = "已静音";
            else if (speaker != null && speaker.isPaused()) title = "已暂停";
            else if (speaking)          title = "朗读中";
            else { title = "已念 " + (speaker == null ? 0 : speaker.spokenCount()) + " 句"; }
            text = speaking
                    ? (now.length() > 28 ? now.substring(0, 28) + "…" : now)
                    : "127.0.0.1:" + server.port();
        }
        Notification n = buildNotification(title, text);
        nm.notify(NID, n);
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(getString(R.string.notif_title), text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NID, n);
        }
    }

    private Notification buildNotification(String title, String text) {
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        // 通知栏快捷开关：暂停/继续、跳过、静音、停止 —— 不必回 App 就能捂住嘴
        boolean paused = speaker != null && speaker.isPaused();
        PendingIntent pausePi = PendingIntent.getService(this, 1,
                new Intent(this, RelayService.class).setAction(ACTION_PAUSE), flags);
        PendingIntent mutePi = PendingIntent.getService(this, 2,
                new Intent(this, RelayService.class).setAction(ACTION_MUTE), flags);
        PendingIntent stopPi = PendingIntent.getService(this, 3,
                new Intent(this, RelayService.class).setAction(ACTION_STOP), flags);
        PendingIntent skipPi = PendingIntent.getService(this, 4,
                new Intent(this, RelayService.class).setAction(ACTION_SKIP), flags);
        // 图标一套自己画的 Material 白 glyph（res/drawable/ic_notif_*），风格才是一家的
        b.addAction(action(paused ? "继续" : "暂停",
                paused ? R.drawable.ic_notif_play : R.drawable.ic_notif_pause, pausePi));
        b.addAction(action("跳过", R.drawable.ic_notif_next, skipPi));
        // 图标 = 现状（和左侧小图标同一个逻辑），文案 = 按下后做什么
        b.addAction(action(Prefs.muted(this) ? "取消静音" : "静音",
                Prefs.muted(this) ? R.drawable.ic_notif_mute : R.drawable.ic_notif_sound, mutePi));
        b.addAction(action("停止", R.drawable.ic_notif_close, stopPi));
        // HyperOS / MIUI 折叠态把普通 action 全折进长按 —— MediaStyle 的 compact 行
        // 是唯一会常驻在折叠通知里的按钮排，放最常用的三个（暂停 / 跳过 / 静音）
        Notification.MediaStyle ms = new Notification.MediaStyle();
        ms.setShowActionsInCompactView(0, 1, 2);
        return b.setContentTitle(title)
                .setContentText(text)
                .setStyle(ms)
                .setSmallIcon(Prefs.muted(this) ? R.drawable.ic_notif_mute
                                : R.drawable.ic_notif_sound)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private static Notification.Action action(String title, int icon, PendingIntent pi) {
        return new Notification.Action.Builder(icon, title, pi).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        watch.removeCallbacks(watchdog);
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
