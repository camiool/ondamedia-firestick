package cl.ondamedia.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Inicio: datos de la película seleccionada arriba y filas de categorías abajo. */
public class HomeActivity extends Activity implements Rows.Listener {

    private final List<Api.Category> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Api api;
    private Progress progress;
    private RecyclerView list;
    private Rows.RowAdapter adapter;
    private ImageView heroBg, heroLogo;
    private TextView heroTitle, heroMeta, heroDesc;
    private Button accountBtn;
    private View loading, errorBox;
    private TextView errorText;

    private Api.Show heroShow;
    private Runnable pendingHero;
    private long lastBack;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        api = Api.get(this);
        progress = new Progress(this);

        heroBg = findViewById(R.id.hero_bg);
        heroLogo = findViewById(R.id.hero_logo);
        heroTitle = findViewById(R.id.hero_title);
        heroMeta = findViewById(R.id.hero_meta);
        heroDesc = findViewById(R.id.hero_desc);
        loading = findViewById(R.id.loading);
        errorBox = findViewById(R.id.error_box);
        errorText = findViewById(R.id.error_text);
        accountBtn = findViewById(R.id.btn_account);

        list = findViewById(R.id.rows);
        list.setLayoutManager(new Rows.AlignedLayoutManager(this, RecyclerView.VERTICAL));
        list.setItemAnimator(null);
        list.setItemViewCacheSize(4);
        adapter = new Rows.RowAdapter(rows, this, progress);
        list.setAdapter(adapter);

        findViewById(R.id.btn_search).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));
        accountBtn.setOnClickListener(v -> onAccount());
        findViewById(R.id.btn_retry).setOnClickListener(v -> load());

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
        accountBtn.setText(api.isLoggedIn() ? "Cerrar sesión" : "Iniciar sesión");
        if (loaded) refreshContinueRow();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        errorBox.setVisibility(View.GONE);
        api.home(new Api.Callback<List<Api.Category>>() {
            @Override
            public void ok(List<Api.Category> result) {
                loading.setVisibility(View.GONE);
                rows.clear();
                rows.addAll(result);
                loaded = true;
                refreshContinueRow();
                adapter.notifyDataSetChanged();
                if (!rows.isEmpty() && !rows.get(0).shows.isEmpty()) showHero(rows.get(0).shows.get(0));
                list.post(() -> list.requestFocus());
            }

            @Override
            public void error(String message) {
                loading.setVisibility(View.GONE);
                errorText.setText(message);
                errorBox.setVisibility(View.VISIBLE);
                findViewById(R.id.btn_retry).requestFocus();
            }
        });
    }

    /** Fila "Seguir viendo" al comienzo, con lo que quedó a medias en este dispositivo. */
    private void refreshContinueRow() {
        boolean had = !rows.isEmpty() && "continue".equals(rows.get(0).id);
        List<Api.Show> items = progress.continueWatching();
        if (had) rows.remove(0);
        if (!items.isEmpty()) {
            Api.Category c = new Api.Category("continue", "Seguir viendo", items.size());
            c.shows.addAll(items);
            rows.add(0, c);
        }
        if (had && !items.isEmpty()) adapter.notifyItemChanged(0);
        else if (had) adapter.notifyItemRemoved(0);
        else if (!items.isEmpty()) adapter.notifyItemInserted(0);
    }

    // ---------- Panel de arriba ----------

    @Override
    public void onFocus(Api.Show show) {
        // Pequeña espera: si se recorre la fila rápido, no cargamos cada imagen grande.
        if (pendingHero != null) handler.removeCallbacks(pendingHero);
        pendingHero = () -> showHero(show);
        handler.postDelayed(pendingHero, 160);
    }

    private void showHero(Api.Show s) {
        if (s == heroShow) return;
        heroShow = s;
        Ui.loadBackdrop(heroBg, s.background != null ? s.background : s.landscape);
        if (s.logo != null) {
            heroLogo.setVisibility(View.VISIBLE);
            heroTitle.setVisibility(View.GONE);
            Ui.loadLogo(heroLogo, s.logo);
        } else {
            heroLogo.setVisibility(View.GONE);
            heroTitle.setVisibility(View.VISIBLE);
            heroTitle.setText(s.title);
        }
        heroMeta.setText(Ui.meta(s));
        heroDesc.setText(s.description);
    }

    @Override
    public void onClick(Api.Show show) {
        if ("continue".equals(show.type)) {
            Ui.play(this, show.mediaId, show.title, show.id, show.landscape);
        } else {
            startActivity(Ui.detailIntent(this, show));
        }
    }

    @Override
    public void onNearEnd(Api.Category row, Runnable inserted) {
        if ("continue".equals(row.id)) return;
        row.loading = true;
        api.categoryPage(row.id, row.loadedPages + 1, new Api.Callback<List<Api.Show>>() {
            @Override
            public void ok(List<Api.Show> result) {
                row.loading = false;
                row.loadedPages++;
                if (result.isEmpty()) return;
                row.shows.addAll(result);
                list.post(inserted);
            }

            @Override
            public void error(String message) {
                row.loading = false;
            }
        });
    }

    // ---------- Cuenta y salida ----------

    private void onAccount() {
        if (!api.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("¿Cerrar la sesión de " + api.userEmail() + " en este dispositivo?")
                .setPositiveButton("Cerrar sesión", (d, w) -> {
                    api.logout();
                    accountBtn.setText("Iniciar sesión");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            long now = SystemClock.uptimeMillis();
            if (now - lastBack < 2500) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, "Presiona Atrás otra vez para salir", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }
}
