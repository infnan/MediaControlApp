package com.example.mediatoggle;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews;

final class RadioControl {
    static final String PACKAGE = "com.gongjin.cradio";
    private static final String TAG = "MediaToggle";

    final String notificationKey;
    final boolean playing;
    private final View button;

    private RadioControl(String notificationKey, boolean playing, View button) {
        this.notificationKey = notificationKey;
        this.playing = playing;
        this.button = button;
    }

    static RadioControl find(Context context) {
        MediaListenerService listener = MediaListenerService.getConnected();
        if (listener == null) {
            return null;
        }
        StatusBarNotification[] notifications = listener.getActiveNotifications();
        if (notifications == null) {
            return null;
        }
        for (StatusBarNotification notification : notifications) {
            if (!PACKAGE.equals(notification.getPackageName())) {
                continue;
            }
            RadioControl control = read(context, notification);
            if (control != null) {
                return control;
            }
        }
        return null;
    }

    private static RadioControl read(Context context, StatusBarNotification entry) {
        try {
            Context radioContext = context.createPackageContext(PACKAGE, 0);
            Resources resources = radioContext.getResources();
            int buttonId = resources.getIdentifier("btnPlay_Notification", "id", PACKAGE);
            if (buttonId == 0) {
                return null;
            }
            Notification notification = entry.getNotification();
            RemoteViews[] layouts = {notification.contentView, notification.bigContentView};
            for (int index = 0; index < layouts.length; index++) {
                RemoteViews layout = layouts[index];
                if (layout == null || !PACKAGE.equals(layout.getPackage())) {
                    continue;
                }
                try {
                    View root = layout.apply(context, null);
                    View candidate = root.findViewById(buttonId);
                    if (!(candidate instanceof ImageView) || !candidate.hasOnClickListeners()) {
                        continue;
                    }
                    String suffix = index == 0 ? "" : "_big";
                    Drawable actual = ((ImageView) candidate).getDrawable();
                    if (matches(actual, resources, radioContext, "ic_pause_notification" + suffix)) {
                        return new RadioControl(entry.getKey(), true, candidate);
                    }
                    if (matches(actual, resources, radioContext, "ic_play_notification" + suffix)) {
                        return new RadioControl(entry.getKey(), false, candidate);
                    }
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Cannot read radio notification layout", exception);
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            Log.w(TAG, "Cannot inspect radio notification", exception);
        }
        return null;
    }

    private static boolean matches(Drawable actual, Resources resources,
            Context radioContext, String resourceName) {
        int resourceId = resources.getIdentifier(resourceName, "drawable", PACKAGE);
        if (actual == null || resourceId == 0) {
            return false;
        }
        Drawable expected = resources.getDrawable(resourceId, radioContext.getTheme());
        Bitmap actualBitmap = render(actual);
        Bitmap expectedBitmap = render(expected);
        boolean equal = actualBitmap.sameAs(expectedBitmap);
        actualBitmap.recycle();
        expectedBitmap.recycle();
        return equal;
    }

    private static Bitmap render(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Rect previousBounds = new Rect(drawable.getBounds());
        int[] previousState = drawable.getState();
        drawable.setState(new int[] {android.R.attr.state_enabled});
        drawable.setBounds(0, 0, 48, 48);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(previousBounds);
        drawable.setState(previousState);
        return bitmap;
    }

    boolean toggle() {
        return button.performClick();
    }
}
