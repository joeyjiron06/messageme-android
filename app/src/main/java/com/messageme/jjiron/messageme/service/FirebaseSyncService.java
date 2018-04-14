package com.messageme.jjiron.messageme.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.messageme.jjiron.messageme.R;
import com.messageme.jjiron.messageme.activity.MainActivity;
import com.messageme.jjiron.messageme.firebase.FirebaseManager;

public class FirebaseSyncService  extends Service {
    private static final String TAG = "FirebaseSyncService";
    public static final String START_SERVICE = "START_SERVICE";
    public static int FOREGROUND_SERVICE_ID = 101;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction().equals(START_SERVICE)) {
            Log.d(TAG, "onStartCommand - start service requested");
            startForeground(FOREGROUND_SERVICE_ID, createNotification());
            FirebaseManager.getInstance().initialize(this);
        }


        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private Notification createNotification() {
        Intent startIntent = new Intent(getApplicationContext(), MainActivity.class);
        startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, startIntent, 0);
        String channelId = "com.message.me";
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MessageMe ")
                .setContentText("Sync service running...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)
                .build();
    }
}
