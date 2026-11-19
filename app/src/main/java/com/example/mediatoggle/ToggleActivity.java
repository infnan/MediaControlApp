package com.example.mediatoggle;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;

public final class ToggleActivity extends Activity {
    private static final String TAG = "MediaToggle";
    private static final long CONNECTION_TIMEOUT_MS = 1800;
    private static final long RESPONSE_TIMEOUT_MS = 4000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ComponentName listenerComponent;
    private boolean running;
    private boolean completed;
    private long connectionDeadline;
    private MediaController targetSession;
    private RadioControl targetRadio;
    private boolean pauseRequested;
    private long responseDeadline;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        listenerComponent = new ComponentName(this, MediaListenerService.class);
        if (savedInstanceState != null) {
            finish();
            return;
        }
        startToggle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!running) {
            startToggle();
        }
    }

    private void startToggle() {
        running = true;
        completed = false;
        if (!hasAccess()) {
            AudioManager audioManager = getSystemService(AudioManager.class);
            if (audioManager != null && !audioManager.isMusicActive()) {
                complete(R.string.no_player);
                return;
            }
            Toast.makeText(this, R.string.grant_access, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot open notification access settings", exception);
                Toast.makeText(this, R.string.access_failed, Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        connectionDeadline = SystemClock.uptimeMillis() + CONNECTION_TIMEOUT_MS;
        if (MediaListenerService.getConnected() == null) {
            NotificationListenerService.requestRebind(listenerComponent);
        }
        awaitConnection();
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            return manager.isNotificationListenerAccessGranted(listenerComponent);
        }
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled != null) {
            for (String entry : enabled.split(":")) {
                if (listenerComponent.equals(ComponentName.unflattenFromString(entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void awaitConnection() {
        if (MediaListenerService.getConnected() != null) {
            toggle();
        } else if (SystemClock.uptimeMillis() < connectionDeadline) {
            handler.postDelayed(this::awaitConnection, 100);
        } else {
            complete(R.string.access_failed);
        }
    }

    private void toggle() {
        try {
            MediaSessionManager manager = getSystemService(MediaSessionManager.class);
            List<MediaController> sessions = manager.getActiveSessions(listenerComponent);
            MediaController pausedSession = null;
            for (MediaController session : sessions) {
                PlaybackState state = session.getPlaybackState();
                if (state == null) {
                    continue;
                }
                if (isPlaying(state.getState())) {
                    targetSession = session;
                    pauseRequested = true;
                    break;
                }
                if (state.getState() == PlaybackState.STATE_PAUSED && pausedSession == null) {
                    pausedSession = session;
                }
            }
            if (targetSession == null) {
                RadioControl radio = RadioControl.find(this);
                if (radio != null && radio.playing) {
                    targetRadio = radio;
                    pauseRequested = true;
                } else if (pausedSession != null) {
                    targetSession = pausedSession;
                    pauseRequested = false;
                } else if (radio != null) {
                    targetRadio = radio;
                    pauseRequested = false;
                }
            }
            if (targetSession == null && targetRadio == null) {
                complete(R.string.no_player);
                return;
            }
            if (targetSession != null) {
                sendSystemCommand();
            } else if (!targetRadio.toggle()) {
                complete(R.string.control_failed);
                return;
            }
            responseDeadline = SystemClock.uptimeMillis() + RESPONSE_TIMEOUT_MS;
            handler.postDelayed(this::awaitResult, 150);
        } catch (SecurityException exception) {
            Log.w(TAG, "Notification access unavailable", exception);
            complete(R.string.access_failed);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot control media", exception);
            complete(R.string.control_failed);
        }
    }

    private void sendSystemCommand() {
        PlaybackState state = targetSession.getPlaybackState();
        long actions = state == null ? 0 : state.getActions();
        long desired = pauseRequested ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY;
        if ((actions & desired) != 0) {
            if (pauseRequested) {
                targetSession.getTransportControls().pause();
            } else {
                targetSession.getTransportControls().play();
            }
        } else {
            int key = (actions & PlaybackState.ACTION_PLAY_PAUSE) != 0
                    ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    : pauseRequested ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
            long now = SystemClock.uptimeMillis();
            targetSession.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
            targetSession.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        }
    }

    private void awaitResult() {
        try {
            boolean changed = false;
            if (targetSession != null) {
                PlaybackState state = targetSession.getPlaybackState();
                if (state != null) {
                    int current = state.getState();
                    changed = pauseRequested
                            ? current == PlaybackState.STATE_PAUSED || current == PlaybackState.STATE_STOPPED
                            : current == PlaybackState.STATE_PLAYING;
                }
            } else {
                RadioControl current = RadioControl.find(this);
                changed = current != null
                        && targetRadio.notificationKey.equals(current.notificationKey)
                        && current.playing != targetRadio.playing;
            }
            if (changed) {
                complete(pauseRequested ? R.string.paused : R.string.resumed);
            } else if (SystemClock.uptimeMillis() < responseDeadline) {
                handler.postDelayed(this::awaitResult, 200);
            } else {
                complete(R.string.control_failed);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot verify media state", exception);
            complete(R.string.control_failed);
        }
    }

    private static boolean isPlaying(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }

    private void complete(int message) {
        if (completed || isFinishing() || isDestroyed()) {
            return;
        }
        completed = true;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
