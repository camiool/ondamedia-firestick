package cl.ondamedia.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Respaldo: el reproductor web oficial de Mediastream, abierto solo y a pantalla completa.
 * Como la página del reproductor es la página principal (no un iframe dentro de otra), las
 * teclas del control se aplican directo sobre su {@code <video>}.
 */
public class EmbedPlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_POSITION = "position";

    private WebView web;
    private Progress progress;
    private String mediaId, showId, title, image;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress = new Progress(this);
        mediaId = getIntent().getStringExtra(Ui.EXTRA_MEDIA);
        showId = getIntent().getStringExtra(Ui.EXTRA_SHOW);
        title = getIntent().getStringExtra(Ui.EXTRA_TITLE);
        image = getIntent().getStringExtra(Ui.EXTRA_IMAGE);
        long startAt = getIntent().getLongExtra(EXTRA_POSITION, 0);
        if (startAt <= 0) startAt = progress.resumePosition(mediaId);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        final long seekTo = startAt;
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (seekTo > 0) {
                    js("var t=" + (seekTo / 1000) + ";var f=function(){if(v.readyState>0){v.currentTime=t;}"
                            + "else{v.addEventListener('loadedmetadata',function(){v.currentTime=t;},{once:true});}};f();");
                }
                js("v.play();");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Permite video protegido (DRM) si el reproductor lo necesita.
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        request.grant(new String[]{r});
                        return;
                    }
                }
                request.deny();
            }
        });
        web.loadUrl(getIntent().getStringExtra(EXTRA_URL));
    }

    /** Ejecuta código con {@code v} = el elemento de video de la página. */
    private void js(String body) {
        web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;"
                + body + "})()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) js("if(v.paused){v.play();}else{v.pause();}");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) js("v.currentTime=Math.max(0,v.currentTime-" + step(e) + ");");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) js("v.currentTime=Math.min(v.duration||1e9,v.currentTime+" + step(e) + ");");
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static int step(KeyEvent e) {
        int r = e.getRepeatCount();
        return r < 6 ? 10 : r < 20 ? 30 : 60;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
        web.onResume();
    }

    @Override
    protected void onPause() {
        // Guardamos por dónde iba antes de salir.
        if (showId != null) {
            web.evaluateJavascript("(function(){var v=document.querySelector('video');"
                    + "return v?[v.currentTime,v.duration]:null})()", r -> {
                if (r == null || !r.startsWith("[")) return;
                String[] p = r.substring(1, r.length() - 1).split(",");
                try {
                    long pos = (long) (Double.parseDouble(p[0]) * 1000);
                    long dur = (long) (Double.parseDouble(p[1]) * 1000);
                    progress.save(mediaId, showId, title, image, pos, dur);
                } catch (Exception ignored) {
                }
            });
        }
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
