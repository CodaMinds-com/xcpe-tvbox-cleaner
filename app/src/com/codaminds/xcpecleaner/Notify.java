package com.codaminds.xcpecleaner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Posts the result of the check after a restart. One channel, one notification id: a newer result
 * replaces the older one. Tapping it opens the main screen.
 */
public final class Notify {
    private Notify() {}

    private static final String TAG = "xcpecleaner";
    public static final String CHANNEL = "xcpe_recheck";
    public static final int ID = 1;

    /** Safe to call often. On Android 13+ the first call from a visible Activity triggers the system's permission question. */
    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.notif_channel_desc));
            nm.createNotificationChannel(ch);
        } catch (Exception e) {
            Log.w(TAG, "notification channel: " + e);
        }
    }

    public static void post(Context c, String title, String text) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi).setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT);
            nm.notify(ID, b.build());
        } catch (Exception e) {
            // SecurityException on Android 13+ when the user refused notifications; nothing else to do.
            Log.w(TAG, "notification: " + e);
        }
    }

    public static void cancel(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Exception ignored) {}
    }
}
