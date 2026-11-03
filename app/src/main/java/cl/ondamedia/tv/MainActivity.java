package cl.ondamedia.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Envoltorio de ondamedia.cl para Fire TV.
 *
 * Controles del control remoto:
 *  - Flechas: mueven el cursor (mantener presionado para acelerar). En los bordes, desplazan la página.
 *  - Botón central (OK): hace clic donde está el cursor.
 *  - Botón Menú (≡): activa/desactiva el cursor (sin cursor, las flechas navegan entre enlaces).
 *  - Play/Pausa, Adelantar, Retroceder: controlan el video que se está reproduciendo.
 *  - Atrás: sale de pantalla completa, vuelve a la página anterior o (dos veces) cierra la app.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://ondamedia.cl/";

    private FrameLayout root;
    private WebView webView;
    private CursorView cursor;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean cursorMode = true;
    private long lastBackPress = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        hideSystemUi();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Algunos sitios bloquean el WebView: nos presentamos como Chrome normal.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                // Todo lo web se abre dentro de la app; otros esquemas (mailto:, intent:, etc.) se ignoran.
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null,
                            "<html><body style='background:#000;color:#fff;font-family:sans-serif;"
                                    + "display:flex;align-items:center;justify-content:center;height:100vh;"
                                    + "text-align:center'><div><h1>Sin conexión</h1>"
                                    + "<p>No se pudo cargar Ondamedia. Revisa tu conexión a internet.</p>"
                                    + "<p><a href='" + HOME_URL + "' style='color:#4fc3f7;font-size:2em'>"
                                    + "Reintentar</a></p></div></body></html>",
                            "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.INVISIBLE);
                cursor.hideNow();
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
        cursor.post(() -> cursor.center());
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        webView.setVisibility(View.VISIBLE);
        hideSystemUi();
    }

    private void hideSystemUi() {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Vista que recibe los clics: el video a pantalla completa si existe, si no la página. */
    private View target() {
        return customView != null ? customView : webView;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (down && event.getRepeatCount() == 0) {
                    cursorMode = !cursorMode;
                    if (cursorMode) cursor.showNow(); else cursor.hideNow();
                    Toast.makeText(this, cursorMode ? "Cursor activado" : "Cursor desactivado",
                            Toast.LENGTH_SHORT).show();
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && event.getRepeatCount() == 0) {
                    runVideoJs("if(v.paused){v.play();}else{v.pause();}");
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) runVideoJs("v.currentTime=Math.min(v.duration||1e9,v.currentTime+15);");
                return true;

            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) runVideoJs("v.currentTime=Math.max(0,v.currentTime-15);");
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (down && event.getRepeatCount() == 0) handleBack();
                return true;
        }

        if (cursorMode) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) moveCursor(code, event.getRepeatCount());
                    return true;

                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down && event.getRepeatCount() == 0) click();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        if (customView != null) {
            exitFullscreen();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            long now = SystemClock.uptimeMillis();
            if (now - lastBackPress < 2500) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Presiona Atrás otra vez para salir", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void moveCursor(int code, int repeat) {
        float density = getResources().getDisplayMetrics().density;
        float step = density * (8f + Math.min(repeat, 25) * 2.2f);
        float dx = 0, dy = 0;
        if (code == KeyEvent.KEYCODE_DPAD_UP) dy = -step;
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) dy = step;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) dx = -step;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) dx = step;

        float margin = 6 * density;
        float nx = cursor.getCursorX() + dx;
        float ny = cursor.getCursorY() + dy;
        int w = root.getWidth(), h = root.getHeight();

        // Si el cursor choca con un borde, desplazamos la página en esa dirección.
        float scrollX = 0, scrollY = 0;
        if (nx < margin) { scrollX = nx - margin; nx = margin; }
        if (nx > w - margin) { scrollX = nx - (w - margin); nx = w - margin; }
        if (ny < margin) { scrollY = ny - margin; ny = margin; }
        if (ny > h - margin) { scrollY = ny - (h - margin); ny = h - margin; }

        cursor.moveTo(nx, ny);
        sendHover(nx, ny);

        if ((scrollX != 0 || scrollY != 0) && customView == null) {
            scrollPage(nx, ny, scrollX * 2.5f / density, scrollY * 2.5f / density);
        }
    }

    /** Desplaza el elemento desplazable más cercano bajo el cursor, o la ventana completa. */
    private void scrollPage(float x, float y, float dx, float dy) {
        float d = getResources().getDisplayMetrics().density;
        // Las coordenadas CSS del WebView equivalen a píxeles / densidad (con viewport móvil).
        String js = "(function(){var x=" + (x / d) + ",y=" + (y / d) + ",dx=" + dx + ",dy=" + dy + ";"
                + "var el=document.elementFromPoint(x,y);"
                + "while(el&&el!==document.body&&el!==document.documentElement){"
                + " var cs=getComputedStyle(el);"
                + " var canY=dy&&/(auto|scroll)/.test(cs.overflowY)&&el.scrollHeight>el.clientHeight;"
                + " var canX=dx&&/(auto|scroll)/.test(cs.overflowX)&&el.scrollWidth>el.clientWidth;"
                + " if(canX||canY){el.scrollBy(dx,dy);return;}"
                + " el=el.parentElement;}"
                + "window.scrollBy(dx,dy);})();";
        webView.evaluateJavascript(js, null);
    }

    private void runVideoJs(String body) {
        webView.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "if(!v){var f=document.querySelectorAll('iframe');"
                + "for(var i=0;i<f.length&&!v;i++){try{v=f[i].contentDocument.querySelector('video');}catch(e){}}}"
                + "if(v){" + body + "}})();", null);
    }

    private void sendHover(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        e.setSource(InputDevice.SOURCE_MOUSE);
        target().dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private void click() {
        float x = cursor.getCursorX(), y = cursor.getCursorY();
        View v = target();
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(downEv);
        downEv.recycle();
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(upEv);
        upEv.recycle();
        cursor.pulse();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }
}
