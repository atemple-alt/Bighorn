package app.bighorn;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bighorn for Android / Android TV.
 *
 * The whole interface is the Bighorn web page, bundled in assets/web/. It is served to the
 * WebView from a private, made-up address (http://bighorn.app/), so the page behaves like a
 * normal website: it can keep its settings, and it is allowed to talk to a Jellyfin server
 * on the home network over plain http.
 */
public class MainActivity extends Activity {

    private static final String HOST = "bighorn.app";
    private static final String START_URL = "http://" + HOST + "/index.html";
    private static final String ACTION_INSTALL_STATUS = "app.bighorn.INSTALL_STATUS";
    private static final int REQ_PLAY = 41;
    private String pendingPlayerResult;
    private static final int BG = Color.rgb(4, 6, 13); // matches the launch intro

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Lets you inspect the page from desktop Chrome (chrome://inspect) while testing.
            WebView.setWebContentsDebuggingEnabled(true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // The page checks for "BighornApp" to know it is running inside the app.
        s.setUserAgentString(s.getUserAgentString() + " BighornApp/2.1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return null; // everything else goes to the network
                String path = uri.getPath();
                if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = getAssets().open("web" + path);
                    return new WebResourceResponse(mimeType(path), "UTF-8", in);
                } catch (IOException e) {
                    WebResourceResponse notFound = new WebResourceResponse("text/plain", "UTF-8", null);
                    notFound.setStatusCodeAndReasonPhrase(404, "Not Found");
                    return notFound;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                // Links that leave the app (e.g. the server test link) open in a browser if there is one.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "No browser available to open that link.", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Used when the phone's built-in video controls go full screen.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        web.addJavascriptInterface(new Bridge(), "BighornNative");

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(START_URL);
        }
    }

    /** Small set of calls the page can make into the app. */
    public class Bridge {
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(MainActivity.this::finish);
        }

        /** True on Android TV / Google TV boxes, so the page uses its remote-control (TV) layout. */
        @JavascriptInterface
        public boolean isTv() {
            try {
                UiModeManager m = (UiModeManager) getSystemService(UI_MODE_SERVICE);
                if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
                return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens the native player with a JSON config from the page. */
        @JavascriptInterface
        public void playNative(final String configJson) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_CONFIG, configJson);
                startActivityForResult(i, REQ_PLAY);
            });
        }

        /** Which video/audio formats this device can decode (or pass through to the TV/receiver). */
        @JavascriptInterface
        public String getCodecSupport() {
            return codecSupportJson();
        }

        /** Downloads the APK at url and hands it to Android's installer. Progress goes back to the page. */
        @JavascriptInterface
        public void installUpdate(final String url) {
            new Thread(() -> downloadAndInstall(url)).start();
        }
    }

    private void updateStatus(String state, String message) {
        final String js = "window.bighornUpdateStatus&&window.bighornUpdateStatus("
                + JSONObject.quote(state) + "," + JSONObject.quote(message == null ? "" : message) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void downloadAndInstall(String url) {
        try {
            // Android 8+: the user has to allow Bighorn to install apps once.
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                updateStatus("permission", "");
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        updateStatus("permission-manual", "");
                    }
                });
                return;
            }

            updateStatus("downloading", "");
            File apk = new File(getCacheDir(), "bighorn-update.apk");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("download failed (HTTP " + code + ")");
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    copy(in, out);
                }
            } finally {
                conn.disconnect();
            }

            updateStatus("installing", "");
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (OutputStream out = session.openWrite("bighorn.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    copy(in, out);
                    session.fsync(out);
                }
                Intent status = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // the installer adds the result extras
                PendingIntent pending = PendingIntent.getActivity(this, 0, status, flags);
                session.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            updateStatus("error", e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private String codecSupportJson() {
        Set<String> video = new LinkedHashSet<>();
        Set<String> audio = new LinkedHashSet<>();
        boolean hevc10 = false;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    String t = type.toLowerCase(Locale.US);
                    switch (t) {
                        case "video/avc": video.add("h264"); break;
                        case "video/hevc": video.add("hevc");
                            try {
                                for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(type).profileLevels) {
                                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) hevc10 = true;
                                }
                            } catch (Exception ignored) { }
                            break;
                        case "video/x-vnd.on2.vp9": video.add("vp9"); break;
                        case "video/x-vnd.on2.vp8": video.add("vp8"); break;
                        case "video/av01": video.add("av1"); break;
                        case "video/mpeg2": video.add("mpeg2video"); break;
                        case "video/mp4v-es": video.add("mpeg4"); break;
                        case "audio/mp4a-latm": audio.add("aac"); break;
                        case "audio/mpeg": audio.add("mp3"); break;
                        case "audio/ac3": audio.add("ac3"); break;
                        case "audio/eac3": case "audio/eac3-joc": audio.add("eac3"); break;
                        case "audio/vnd.dts": audio.add("dts"); break;
                        case "audio/vnd.dts.hd": audio.add("dts"); break;
                        case "audio/true-hd": audio.add("truehd"); break;
                        case "audio/opus": audio.add("opus"); break;
                        case "audio/flac": audio.add("flac"); break;
                        case "audio/vorbis": audio.add("vorbis"); break;
                        case "audio/raw": audio.add("pcm_s16le"); audio.add("pcm_s24le"); break;
                        default: break;
                    }
                }
            }
        } catch (Exception ignored) { }
        // Surround formats the box can't decode itself may still pass through HDMI to the TV / soundbar.
        try {
            AudioCapabilities caps = AudioCapabilities.getCapabilities(this);
            if (caps.supportsEncoding(C.ENCODING_AC3)) audio.add("ac3");
            if (caps.supportsEncoding(C.ENCODING_E_AC3)) audio.add("eac3");
            if (caps.supportsEncoding(C.ENCODING_DTS)) audio.add("dts");
            if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) audio.add("truehd");
        } catch (Throwable ignored) { }
        video.add("h264");
        audio.add("aac");
        audio.add("mp3");
        try {
            JSONObject o = new JSONObject();
            o.put("video", new JSONArray(video));
            o.put("audio", new JSONArray(audio));
            o.put("hevc10", hevc10);
            return o.toString();
        } catch (Exception e) {
            return "{\"video\":[\"h264\"],\"audio\":[\"aac\",\"mp3\"]}";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLAY) return;
        String r = data != null ? data.getStringExtra(PlayerActivity.EXTRA_RESULT) : null;
        pendingPlayerResult = r != null ? r : "{\"action\":\"back\",\"positionMs\":0}";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Results from Android's installer arrive here. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(confirm);
                } catch (Exception e) {
                    updateStatus("error", e.getMessage());
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            updateStatus("done", "");
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            updateStatus("cancelled", "");
        } else {
            updateStatus("error", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    /** Back on the remote or phone: let the page close the player / details / page first. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return !!(window.bighornBack&&window.bighornBack());}catch(e){return false;}})()",
                handled -> {
                    if (!"true".equals(handled)) finish();
                });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Some remotes send a separate Escape/Back key; route it through the same Back handling.
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause(); // stops video/audio when you leave the app
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (pendingPlayerResult != null) {
            final String js = "window.bighornNativeDone&&window.bighornNativeDone(" + pendingPlayerResult + ")";
            pendingPlayerResult = null;
            web.post(() -> web.evaluateJavascript(js, null));
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    private static String mimeType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
