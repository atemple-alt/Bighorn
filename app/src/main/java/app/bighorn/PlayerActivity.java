package app.bighorn;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen native video player (Android's ExoPlayer / Media3).
 *
 * The web page decides what to play and passes a JSON config; this screen plays it, reports
 * progress to Jellyfin, shows Skip Intro / Skip Credits / Next Episode, and hands a result
 * (ended / next / back / error + position) back to the page when it closes.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_RESULT = "result";

    private ExoPlayer player;
    private PlayerView view;
    private TextView titleView;
    private Button skipBtn;
    private Button nextBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private JSONObject cfg = new JSONObject();
    private JSONArray segments = new JSONArray();
    private JSONObject activeSeg;
    private boolean hasNext;
    private boolean finished;
    private boolean startReported;
    private int tick;
    private int consumedKey = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            cfg = new JSONObject(getIntent().getStringExtra(EXTRA_CONFIG));
        } catch (Exception e) {
            finishWith("error", "Bad player settings");
            return;
        }
        JSONArray segs = cfg.optJSONArray("segments");
        if (segs != null) segments = segs;
        hasNext = cfg.optBoolean("hasNext", false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        String title = cfg.optString("title", "");
        String sub = cfg.optString("subtitle", "");
        titleView.setText(sub.isEmpty() ? title : title + "   ·   " + sub);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tlp.setMargins(dp(40), dp(28), dp(40), 0);
        root.addView(titleView, tlp);

        skipBtn = makeButton("Skip Intro");
        skipBtn.setVisibility(View.GONE);
        skipBtn.setOnClickListener(v -> onSkip());
        root.addView(skipBtn, cornerParams());

        nextBtn = makeButton("Next Episode ▶");
        nextBtn.setVisibility(View.GONE);
        nextBtn.setOnClickListener(v -> finishWith("next", null));
        root.addView(nextBtn, cornerParams());

        setContentView(root);
        enterImmersive();

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);

        view.setPlayer(player);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setControllerShowTimeoutMs(4000);
        view.setKeepScreenOn(true);
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                titleView.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && !startReported) {
                    startReported = true;
                    report("start", null);
                } else if (state == Player.STATE_ENDED) {
                    finishWith("ended", null);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (startReported && !finished) report("progress", isPlaying ? "Unpause" : "Pause");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                finishWith("error", error.getErrorCodeName() + (error.getMessage() != null ? ": " + error.getMessage() : ""));
            }
        });

        MediaItem.Builder item = new MediaItem.Builder().setUri(cfg.optString("url"));
        if (cfg.optBoolean("isHls", false)) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build(), Math.max(0L, cfg.optLong("startMs", 0L)));
        player.prepare();
        player.setPlayWhenReady(true);

        handler.post(ticker);
    }

    /** Runs twice a second: skip/next buttons, and a progress report to Jellyfin every 10 s. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (finished || player == null) return;
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            updateSegment(pos, dur);
            boolean outro = activeSeg != null && "Outro".equals(activeSeg.optString("type"));
            boolean showNext = hasNext && !outro && dur != C.TIME_UNSET && dur > 0 && dur - pos < 45_000;
            if (showNext && nextBtn.getVisibility() != View.VISIBLE) {
                nextBtn.setVisibility(View.VISIBLE);
                nextBtn.requestFocus();
            } else if (!showNext && nextBtn.getVisibility() == View.VISIBLE) {
                nextBtn.setVisibility(View.GONE);
            }
            if (++tick % 20 == 0 && startReported) report("progress", "TimeUpdate");
            handler.postDelayed(this, 500);
        }
    };

    private void updateSegment(long pos, long dur) {
        JSONObject found = null;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject s = segments.optJSONObject(i);
            if (s != null && pos >= s.optLong("startMs") && pos < s.optLong("endMs") - 1000) {
                found = s;
                break;
            }
        }
        if (found == activeSeg) return;
        activeSeg = found;
        if (found == null) {
            skipBtn.setVisibility(View.GONE);
            return;
        }
        String type = found.optString("type");
        boolean toEnd = dur != C.TIME_UNSET && dur > 0 && dur - found.optLong("endMs") < 15_000;
        String label;
        if ("Intro".equals(type)) label = "Skip Intro";
        else if ("Recap".equals(type)) label = "Skip Recap";
        else label = (hasNext && toEnd) ? "Next Episode ▶" : "Skip Credits";
        skipBtn.setText(label);
        skipBtn.setVisibility(View.VISIBLE);
        skipBtn.requestFocus(); // so OK on the remote skips
    }

    private void onSkip() {
        JSONObject g = activeSeg;
        if (g == null || player == null) return;
        long end = g.optLong("endMs");
        long dur = player.getDuration();
        boolean nearEnd = dur != C.TIME_UNSET && dur > 0 && dur - end < 15_000;
        if ("Outro".equals(g.optString("type")) && (hasNext || nearEnd)) {
            finishWith(hasNext ? "next" : "ended", null);
            return;
        }
        player.seekTo(dur != C.TIME_UNSET && dur > 0 ? Math.min(end, dur - 500) : end);
        activeSeg = null;
        skipBtn.setVisibility(View.GONE);
    }

    // ---------- remote control ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && k == consumedKey) {
            consumedKey = -1;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && player != null && view != null) {
            boolean buttonFocused = (skipBtn.getVisibility() == View.VISIBLE && skipBtn.hasFocus())
                    || (nextBtn.getVisibility() == View.VISIBLE && nextBtn.hasFocus());
            if (!buttonFocused && !view.isControllerFullyVisible()) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        view.showController();
                        consumedKey = k;
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        player.seekBack();
                        view.showController();
                        consumedKey = k;
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        player.seekForward();
                        view.showController();
                        consumedKey = k;
                        return true;
                    default:
                        break;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (view != null && view.isControllerFullyVisible()) {
            view.hideController();
            return;
        }
        finishWith("back", null);
    }

    // ---------- closing + reporting ----------

    private void finishWith(String action, String message) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        long pos = player != null ? player.getCurrentPosition() : 0L;
        if (player != null && startReported) {
            report("stop", null);
            if (!"DirectPlay".equals(cfg.optString("playMethod"))) {
                final String q = "?deviceId=" + enc(cfg.optString("deviceId")) + "&playSessionId=" + enc(cfg.optString("playSessionId"));
                net.execute(() -> {
                    try {
                        http("DELETE", "/Videos/ActiveEncodings" + q, null);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        try {
            JSONObject r = new JSONObject();
            r.put("action", action);
            r.put("positionMs", pos);
            if (message != null) r.put("message", message);
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, r.toString()));
        } catch (Exception ignored) {
        }
        finish();
    }

    private void report(final String kind, final String eventName) {
        if (player == null) return;
        final long ticks = player.getCurrentPosition() * 10_000L;
        final boolean paused = !player.getPlayWhenReady();
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("ItemId", cfg.optString("itemId"));
                b.put("MediaSourceId", cfg.optString("mediaSourceId"));
                b.put("PlaySessionId", cfg.optString("playSessionId"));
                b.put("PlayMethod", cfg.optString("playMethod"));
                b.put("CanSeek", true);
                b.put("PositionTicks", ticks);
                b.put("IsPaused", paused);
                if (eventName != null) b.put("EventName", eventName);
                String path = "start".equals(kind) ? "/Sessions/Playing"
                        : "progress".equals(kind) ? "/Sessions/Playing/Progress"
                        : "/Sessions/Playing/Stopped";
                http("POST", path, b.toString());
            } catch (Exception ignored) {
            }
        });
    }

    private void http(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.optString("server") + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            String auth = cfg.optString("authHeader");
            c.setRequestProperty("Authorization", auth);
            c.setRequestProperty("X-Emby-Authorization", auth);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the player (Home button, input switch): save progress and close.
        if (!finished && !isChangingConfigurations()) finishWith("back", null);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        net.shutdown(); // queued reports still finish
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    // ---------- small UI helpers ----------

    private FrameLayout.LayoutParams cornerParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(48), dp(110));
        return lp;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xCC141414);
        normal.setStroke(dp(2), 0xDDFFFFFF);
        normal.setCornerRadius(dp(4));
        GradientDrawable lit = new GradientDrawable();
        lit.setColor(Color.WHITE);
        lit.setCornerRadius(dp(4));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, lit);
        bg.addState(new int[]{android.R.attr.state_pressed}, lit);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{Color.BLACK, Color.BLACK, Color.WHITE}));
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setFocusable(true);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }
}
