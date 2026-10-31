package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Only there so Android lets us see which app is playing music and what (MediaSessionManager.getActiveSessions asks
 * for an enabled notification listener): it reads no notifications. Turned on over adb, never asked for:
 * adb shell cmd notification allow_listener io.freemytv/io.freemytv.MusicListener (tools/firetv-install.sh does it).
 */
public class MusicListener extends NotificationListenerService {}
