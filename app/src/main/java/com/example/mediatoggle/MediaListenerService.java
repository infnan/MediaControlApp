package com.example.mediatoggle;

import android.service.notification.NotificationListenerService;

public final class MediaListenerService extends NotificationListenerService {
    private static MediaListenerService connected;

    public static MediaListenerService getConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        connected = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) {
            connected = null;
        }
    }

    @Override
    public void onDestroy() {
        if (connected == this) {
            connected = null;
        }
        super.onDestroy();
    }
}
