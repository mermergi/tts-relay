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
                    if (speaker == null) { speaker = new Speaker(RelayService.this); speaker.start(); }
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
        startEverything();
        return START_STICKY;
    }

    private void startEverything() {
        startForegroundCompat("正在启动…");
        Prefs.setWanted(this, true);
        if (speaker == null) speaker = new Speaker(this);
        speaker.start();
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
        String text = up
                ? "127.0.0.1:" + server.port()
                + " · 已念 " + (speaker == null ? 0 : speaker.spokenCount()) + " 句"
                + (Prefs.muted(this) ? " · 已静音" : "")
                : "未运行";
        Notification n = buildNotification(text);
        nm.notify(NID, n);
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NID, n);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
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
