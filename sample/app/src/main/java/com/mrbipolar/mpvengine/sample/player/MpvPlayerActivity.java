package com.mrbipolar.mpvengine.sample.player;


import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;


import com.maxplayer.mpv.LiveStatus;
import com.mrbipolar.mpvengine.sample.databinding.ActivityMpvPlayerBinding;

/**
 * Thin host for {@link MpvVideoPlayer}. Launch it with extras:
 * <ul>
 *   <li>{@link #EXTRA_URL} (+ optional {@link #EXTRA_TITLE}, {@link #EXTRA_LIVE}) for one stream, or</li>
 *   <li>{@link #EXTRA_PLAYLIST_URL} (+ optional {@link #EXTRA_START_INDEX}) for an M3U channel list.</li>
 * </ul>
 */
public class MpvPlayerActivity extends AppCompatActivity implements MpvVideoPlayer.Callbacks {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    /** boolean: true = known live channel, false = known VOD; omit to let the engine detect. */
    public static final String EXTRA_LIVE = "extra_live";
    public static final String EXTRA_PLAYLIST_URL = "extra_playlist_url";
    public static final String EXTRA_START_INDEX = "extra_start_index";

    /** Debug fallback so the screen still plays something when started without extras. */
    private  String DEBUG_DEFAULT_URL = "https://iptv-org.github.io/iptv/languages/mal.m3u"; // playlist url

    private ActivityMpvPlayerBinding binding;
    private MpvVideoPlayer videoPlayer;


    public static Intent playStream(Context c, String m3uUrl) {
        Intent intent = new Intent(c, MpvPlayerActivity.class);

        if (m3uUrl != null && !m3uUrl.trim().isEmpty()) {
            intent.putExtra(EXTRA_PLAYLIST_URL, m3uUrl);
        }

        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMpvPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        videoPlayer = new MpvVideoPlayer(this, binding, this);   // construct in onCreate (registers an ActivityResult launcher)
        videoPlayer.initialize();

        String playlistUrl = getIntent().getStringExtra(EXTRA_PLAYLIST_URL);

        if(playlistUrl != null) {
           DEBUG_DEFAULT_URL = playlistUrl;
        }

        startPlayback(getIntent());
    }




    private void startPlayback(Intent intent) {
        String playlistUrl = intent.getStringExtra(EXTRA_PLAYLIST_URL);
        if (playlistUrl != null) {
            videoPlayer.playPlaylistFromUrl(playlistUrl, intent.getIntExtra(EXTRA_START_INDEX, 0));
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        boolean usingDebugDefault = url == null;
        if (usingDebugDefault) url = DEBUG_DEFAULT_URL;

        LiveStatus hint = null;                                   // null = let the engine detect
        if (intent.hasExtra(EXTRA_LIVE)) hint = intent.getBooleanExtra(EXTRA_LIVE, true) ? LiveStatus.LIVE : LiveStatus.VOD;
        // (the debug fallback is NOT hinted: the engine's own live detection is exercised instead)
        android.util.Log.i("MPV", "Starting: url=" + url + " hint=" + hint + (usingDebugDefault ? "  [DEBUG FALLBACK: EXTRA_URL missing]" : ""));

        videoPlayer.playStream(url, intent.getStringExtra(EXTRA_TITLE), hint);
    }

    @Override public void onCloseRequested() { finish(); }

    /** Only called when the manifest declares android:configChanges (otherwise the Activity is recreated). */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        videoPlayer.onConfigurationChanged(newConfig);
    }

    @Override protected void onStart() { super.onStart(); videoPlayer.onStart(); }

    @Override protected void onStop() { videoPlayer.onStop(); super.onStop(); }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        videoPlayer.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        videoPlayer.onDestroy();
        binding = null;
        super.onDestroy();
    }
}