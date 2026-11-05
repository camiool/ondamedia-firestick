package cl.ondamedia.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

/** Ficha de una película o serie: imagen de fondo, sinopsis y botones para ver. */
public class DetailActivity extends Activity implements Rows.Listener {

    private Api api;
    private Progress progress;
    private Api.Show show;
    private String showId;

    private ImageView bg, logo;
    private TextView title, meta, desc, rating, note;
    private Button playBtn, trailerBtn;
    private View loading;
    private RecyclerView seasons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        api = Api.get(this);
        progress = new Progress(this);

        bg = findViewById(R.id.bg);
        logo = findViewById(R.id.logo);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        desc = findViewById(R.id.desc);
        rating = findViewById(R.id.rating);
        note = findViewById(R.id.note);
        playBtn = findViewById(R.id.btn_play);
        trailerBtn = findViewById(R.id.btn_trailer);
        loading = findViewById(R.id.loading);
        seasons = findViewById(R.id.seasons);

        showId = getIntent().getStringExtra(Ui.EXTRA_ID);
        title.setText(getIntent().getStringExtra(Ui.EXTRA_TITLE));
        Ui.loadBackdrop(bg, getIntent().getStringExtra(Ui.EXTRA_IMAGE));
        playBtn.setVisibility(View.INVISIBLE);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemUi(this);
        if (show != null) updatePlayButton();
        if (seasons.getAdapter() != null) seasons.getAdapter().notifyDataSetChanged();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        api.show(showId, new Api.Callback<Api.Show>() {
            @Override
            public void ok(Api.Show result) {
                loading.setVisibility(View.GONE);
                show = result;
                bind();
            }

            @Override
            public void error(String message) {
                loading.setVisibility(View.GONE);
                Toast.makeText(DetailActivity.this, message, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void bind() {
        Api.Show s = show;
        if (s.background != null) Ui.loadBackdrop(bg, s.background);
        if (s.logo != null) {
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
            Ui.loadLogo(logo, s.logo);
        } else {
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            title.setText(s.title);
        }
        meta.setText(Ui.meta(s));
        desc.setText(s.description);
        if (s.rating != null) {
            rating.setText(s.rating);
            rating.setVisibility(View.VISIBLE);
        }

        if (s.trailerId != null) {
            trailerBtn.setVisibility(View.VISIBLE);
            trailerBtn.setOnClickListener(v ->
                    Ui.play(this, s.trailerId, s.title + " · Tráiler", null, null));
        }

        if (s.isSeries() && !s.seasons.isEmpty()) {
            // Series: los episodios van en filas abajo; el botón principal abre el primero.
            seasons.setVisibility(View.VISIBLE);
            seasons.setLayoutManager(new Rows.AlignedLayoutManager(this, RecyclerView.VERTICAL));
            seasons.setItemAnimator(null);
            seasons.setAdapter(new Rows.RowAdapter(s.seasons, this, progress).withLabels());
            findViewById(R.id.info).setTranslationY(-Ui.dp(this, 24));
            desc.setMaxLines(3);
        }

        playBtn.setVisibility(View.VISIBLE);
        updatePlayButton();
        playBtn.requestFocus();
    }

    /** Media que abre el botón principal: la película, o el primer episodio de una serie. */
    private Api.Show mainItem() {
        if (show.mediaId != null) return show;
        if (!show.seasons.isEmpty() && !show.seasons.get(0).shows.isEmpty()) {
            return show.seasons.get(0).shows.get(0);
        }
        return null;
    }

    private void updatePlayButton() {
        Api.Show item = mainItem();
        if (item == null || item.mediaId == null) {
            playBtn.setText("No disponible");
            playBtn.setOnClickListener(v -> Toast.makeText(this,
                    "Este título no tiene un video disponible.", Toast.LENGTH_SHORT).show());
            return;
        }
        boolean resume = progress.resumePosition(item.mediaId) > 0;
        playBtn.setText(resume ? "▶  Continuar" : "▶  Ver ahora");
        String image = show.landscape;
        String name = item == show ? show.title : show.title + " · " + item.title;
        playBtn.setOnClickListener(v -> Ui.play(this, item.mediaId, name, show.id, image));
        if (!api.isLoggedIn()) {
            note.setText("Para ver películas tienes que iniciar sesión con tu cuenta de Ondamedia.");
            note.setVisibility(View.VISIBLE);
        } else {
            note.setVisibility(View.GONE);
        }
    }

    // ---------- Episodios ----------

    @Override
    public void onFocus(Api.Show episode) {
    }

    @Override
    public void onClick(Api.Show episode) {
        if (episode.mediaId == null) return;
        Ui.play(this, episode.mediaId, show.title + " · " + episode.title, show.id,
                episode.landscape != null ? episode.landscape : show.landscape);
    }

    @Override
    public void onNearEnd(Api.Category row, Runnable inserted) {
    }
}
