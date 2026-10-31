package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/** What music is playing, from the system's media sessions (MusicListener lets us read them). */
final class Music {
    private Music() {}

    /** Video apps also hold media sessions: only these count as music. */
    private static final String[] APPS = {"com.spotify.tv.android", "com.spotify.music", "com.amazon.mp3",
            "com.google.android.youtube.tvmusic", "com.apple.android.music", "com.pandora.android.atv", "com.pandora.android",
            "com.clearchannel.iheartradio.controller", "com.sirius", "com.siriusxm.livingroom", "tunein.player",
            "com.soundcloud.android", "com.aspiro.tidal", "deezer.android.tv"};

    /** Our listener is turned on, so we may look. */
    static boolean enabled(Context c) {
        String list = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return list != null && list.contains(c.getPackageName() + "/");
    }

    /** The music app's session that is playing now (with something to show), or null. */
    static MediaController playing(Context c) {
        MediaController any = session(c);
        PlaybackState s = any == null ? null : any.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING ? any : null;
    }

    /** The music app's session, playing or paused (the screen keeps showing a paused song), or null. */
    static MediaController session(Context c) {
        if (!enabled(c)) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, MusicListener.class));
            MediaController paused = null;
            for (MediaController mc : list) {
                if (!music(mc.getPackageName()) || mc.getMetadata() == null) continue;
                MediaMetadata md = mc.getMetadata();
                if (md.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (paused == null) paused = mc;
            }
            return paused;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean music(String pkg) {
        for (String a : APPS) if (a.equals(pkg)) return true;
        return false;
    }
}
