package cl.ondamedia.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Envoltorio de ondamedia.cl para Fire TV.
 *
 * Por defecto se navega como en una app de TV: las flechas saltan entre películas y botones
 * (con un recuadro blanco que marca la selección) y OK abre lo seleccionado. La lógica de
 * navegación está en assets/tvnav.js, que además fuerza la versión de escritorio del sitio.
 *
 * Controles del control remoto:
 *  - Flechas: mueven la selección. OK: abre lo seleccionado.
 *  - Menú (≡): activa/desactiva el cursor libre, por si algún elemento no se puede seleccionar.
 *  - Play/Pausa, Adelantar, Retroceder: controlan el video.
 *  - Atrás: sale de pantalla completa, vuelve a la página anterior o (dos veces) cierra la app.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://ondamedia.cl/";

    /** Dominios de analítica que no aportan nada en la TV y solo hacen más lenta la carga. */
    private static final String[] BLOCKED_HOSTS = {
            "googletagmanager.com", "google-analytics.com", "analytics.google.com",
            "doubleclick.net", "googlesyndication.com", "facebook.net", "connect.facebook.com",
            "hotjar.com", "clarity.ms",
    };

    private FrameLayout root;
    private WebView webView;
    private CursorView cursor;
    private View splash;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private String navJs = "";
    private boolean cursorMode = false;
    private boolean splashHidden = false;
    private long lastBackPress = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navJs = readAsset("tvnav.js");
        // En la versión debug se puede inspeccionar la página con chrome://inspect.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        root.addView(webView, matchParent());

        cursor = new CursorView(this);
        cursor.hideNow();
        root.addView(cursor, matchParent());

        splash = buildSplash();
        root.addView(splash, matchParent());

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
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Algunos sitios bloquean el WebView: nos presentamos como Chrome normal.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "TVBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                // Todo lo web se abre dentro de la app; otros esquemas (mailto:, intent:, etc.) se ignoran.
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null) {
                    for (String blocked : BLOCKED_HOSTS) {
                        if (host.equals(blocked) || host.endsWith("." + blocked)) {
                            return new WebResourceResponse("text/plain", "utf-8",
                                    new ByteArrayInputStream(new byte[0]));
                        }
                    }
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                injectNav();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectNav();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectNav();
                // Damos tiempo a que el sitio cambie al diseño de escritorio antes de mostrarlo.
                root.postDelayed(MainActivity.this::hideSplash, 900);
                root.postDelayed(() -> webView.evaluateJavascript(
                        "window.__tv&&__tv.focusInitial()", null), 2500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    hideSplash();
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
                root.addView(view, 1, matchParent());
                view.setFocusable(true);
                view.requestFocus();
                webView.setVisibility(View.INVISIBLE);
                cursor.hideNow();
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // En una SPA el contenido cambia sin recargar: reinyectar es barato y seguro.
                if (newProgress >= 30) injectNav();
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
        cursor.post(() -> cursor.center());
        // Por si la página nunca termina de cargar (red lenta), no dejamos la pantalla de carga para siempre.
        root.postDelayed(this::hideSplash, 12000);
    }

    // ---------- Interfaz ----------

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View buildSplash() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.BLACK);
        box.setClickable(true);

        TextView title = new TextView(this);
        title.setText("ONDAMEDIA");
        title.setTextColor(Color.WHITE);
        title.setLetterSpacing(0.25f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (28 * getResources().getDisplayMetrics().density);
        box.addView(spinner, lp);
        return box;
    }

    private void hideSplash() {
        if (splashHidden) return;
        splashHidden = true;
        splash.animate().alpha(0f).setDuration(350).withEndAction(() -> splash.setVisibility(View.GONE));
    }

    private void injectNav() {
        if (!navJs.isEmpty()) webView.evaluateJavascript(navJs, null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    /** Puente que el JS usa para pedir el teclado en pantalla al entrar a un campo de texto. */
    private class Bridge {
        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(() -> {
                webView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            });
        }
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

    // ---------- Teclas ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (down && event.getRepeatCount() == 0) {
                    cursorMode = !cursorMode;
                    if (cursorMode) cursor.showNow(); else cursor.hideNow();
                    Toast.makeText(this, cursorMode
                                    ? "Cursor libre activado (Menú para volver)"
                                    : "Navegación con flechas",
                            Toast.LENGTH_SHORT).show();
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && event.getRepeatCount() == 0) {
                    runVideoJs("if(v.paused){v.play();}else{v.pause();}", KeyEvent.KEYCODE_SPACE);
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) runVideoJs("v.currentTime=Math.min(v.duration||1e9,v.currentTime+15);",
                        KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;

            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) runVideoJs("v.currentTime=Math.max(0,v.currentTime-15);",
                        KeyEvent.KEYCODE_DPAD_LEFT);
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (down && event.getRepeatCount() == 0) handleBack();
                return true;
        }

        // Con video a pantalla completa, las teclas van directo al reproductor
        // (OK se traduce a espacio, que es pausa/reproducir en el reproductor web).
        if (customView != null) return super.dispatchKeyEvent(playerKey(event));

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
            return super.dispatchKeyEvent(event);
        }

        String dir = null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: dir = "enter"; break;
        }
        if (dir == null) return super.dispatchKeyEvent(event);
        if (!down) return true;  // el ACTION_UP se reenvía junto con el DOWN si hace falta
        if (dir.equals("enter") && event.getRepeatCount() > 0) return true;

        final int keyCode = code;
        boolean repeat = event.getRepeatCount() > 0;
        webView.evaluateJavascript("window.__tv?__tv.key('" + dir + "'," + repeat + "):false", result -> {
            if (result != null && result.startsWith("\"tap:")) {
                tapAt(result.substring(5, result.length() - 1));
            } else if ("\"player\"".equals(result)) {
                sendKeyToWebView(isCenter(keyCode) ? KeyEvent.KEYCODE_SPACE : keyCode);
            } else if (!"true".equals(result)) {
                // El JS no la manejó (campo de texto, reproductor en iframe…): se la pasamos al WebView.
                sendKeyToWebView(keyCode);
            }
        });
        return true;
    }

    /** Toque real en una posición dada como "fx,fy" (fracciones del ancho y alto del WebView). */
    private void tapAt(String coords) {
        String[] p = coords.split(",");
        if (p.length != 2) return;
        try {
            float x = Float.parseFloat(p[0]) * webView.getWidth();
            float y = Float.parseFloat(p[1]) * webView.getHeight();
            long t = SystemClock.uptimeMillis();
            MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            webView.dispatchTouchEvent(downEv);
            downEv.recycle();
            MotionEvent upEv = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0);
            upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            webView.dispatchTouchEvent(upEv);
            upEv.recycle();
        } catch (NumberFormatException ignored) {
        }
    }

    private static boolean isCenter(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static KeyEvent playerKey(KeyEvent e) {
        if (!isCenter(e.getKeyCode())) return e;
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_SPACE,
                e.getRepeatCount(), e.getMetaState());
    }

    private void sendKeyToWebView(int keyCode) {
        long t = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        webView.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void handleBack() {
        if (customView != null) {
            // Atrás en el reproductor = dejar de ver: salimos de pantalla completa y lo cerramos,
            // para que no siga sonando en una ventanita flotante.
            exitFullscreen();
            webView.evaluateJavascript("window.__tv&&__tv.closePlayer&&__tv.closePlayer()", null);
            return;
        }
        // Primero le preguntamos a la página (p. ej. para cerrar el reproductor agrandado).
        webView.evaluateJavascript("window.__tv&&__tv.back?__tv.back():false", result -> {
            if (!"true".equals(result)) navigateBack();
        });
    }

    private void navigateBack() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else if (webView.getUrl() != null && !webView.getUrl().equals(HOME_URL)
                && webView.getUrl().startsWith("http")) {
            // Sin historial (p. ej. tras reiniciar la app en otra página): volvemos al inicio.
            webView.loadUrl(HOME_URL);
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

    // ---------- Cursor libre (modo alternativo) ----------

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
            scrollPage(nx / w, ny / h, scrollX * 2.5f / w, scrollY * 2.5f / h);
        }
    }

    /**
     * Desplaza el elemento desplazable más cercano bajo el cursor, o la ventana completa.
     * Recibe fracciones de la pantalla, que el JS convierte a píxeles CSS con innerWidth/innerHeight
     * (así funciona aunque el sitio use un viewport más ancho que la pantalla).
     */
    private void scrollPage(float fx, float fy, float fdx, float fdy) {
        String js = "(function(){var x=" + fx + "*innerWidth,y=" + fy + "*innerHeight,"
                + "dx=" + fdx + "*innerWidth,dy=" + fdy + "*innerHeight;"
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

    /**
     * Ejecuta JS sobre el {@code <video>} de la página. Si no lo encuentra (por ejemplo, porque el
     * reproductor está en un iframe de otro dominio), envía la tecla equivalente al reproductor.
     */
    private void runVideoJs(String body, int fallbackKey) {
        webView.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "if(!v){var f=document.querySelectorAll('iframe');"
                + "for(var i=0;i<f.length&&!v;i++){try{v=f[i].contentDocument.querySelector('video');}catch(e){}}}"
                + "if(v){" + body + "return true;}return false;})()", result -> {
            if (!"true".equals(result)) {
                long t = SystemClock.uptimeMillis();
                View v = target();
                v.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, fallbackKey, 0));
                v.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, fallbackKey, 0));
            }
        });
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

    // ---------- Ciclo de vida ----------

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
