package com.anand.gamenetdoctor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

/**
 * "Game Mode": a foreground service that holds a Wi-Fi lock so the phone's Wi-Fi radio does not
 * drop into power-save mode between packets. Wi-Fi power-save is a common cause of random
 * 100-300 ms lag spikes in online games even when streaming video works fine (video buffers, games can't).
 *
 * Note: Android 14+ ignores the high-perf lock from background apps, so the effect is strongest on Android 13 and older.
 */
public class GameModeService extends Service {

    public static final String CHANNEL = "game_mode";
    public static volatile boolean running = false;

    private WifiManager.WifiLock highPerf;
    private WifiManager.WifiLock lowLatency;

    @Override
    public void onCreate() {
        super.onCreate();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            //noinspection deprecation
            highPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameNetDoctor:highperf");
            highPerf.setReferenceCounted(false);
            highPerf.acquire();
            if (Build.VERSION.SDK_INT >= 29) {
                lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameNetDoctor:lowlat");
                lowLatency.setReferenceCounted(false);
                lowLatency.acquire();
            }
        }
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game Mode",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Game Mode is ON")
                .setContentText("Wi-Fi power saving disabled for smoother online play. Tap to turn off.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (highPerf != null && highPerf.isHeld()) highPerf.release();
        if (lowLatency != null && lowLatency.isHeld()) lowLatency.release();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
