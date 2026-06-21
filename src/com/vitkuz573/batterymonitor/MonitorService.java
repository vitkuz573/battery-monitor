package com.vitkuz573.batterymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class MonitorService extends Service {

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable notifier;
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "battery_monitor";

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        notifier = () -> {
            android.content.Intent bat;
            if (Build.VERSION.SDK_INT >= 33) {
                bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            if (bat == null) return;

            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = level >= 0 ? level * 100 / Math.max(scale, 1) : 0;
            int tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);

            String statusStr;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING: statusStr = "\u26A1 Charging"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING: statusStr = "Discharging"; break;
                case BatteryManager.BATTERY_STATUS_FULL: statusStr = "Full"; break;
                default: statusStr = "Idle";
            }

            String content = pct + "% \u00B7 " + tempC + "\u00B0C \u00B7 " + statusStr;

            Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Battery Monitor")
                .setContentText(content)
                .setOngoing(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            }

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, nb.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, nb.build());
            }
            handler.postDelayed(notifier, 10000);
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(notifier);
        return START_STICKY;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDestroy() {
        handler.removeCallbacks(notifier);
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ongoing battery monitoring");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
