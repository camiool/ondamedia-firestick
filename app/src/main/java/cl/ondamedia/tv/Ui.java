package cl.ondamedia.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Utilidades de interfaz compartidas por las pantallas. */
final class Ui {

    private Ui() {
    }

    static final String EXTRA_ID = "id";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_IMAGE = "image";
    static final String EXTRA_MEDIA = "media";
    static final String EXTRA_SHOW = "show";

    /** Imágenes de tarjetas en RGB_565: la mitad de memoria, importante en el Fire TV. */
    private static final RequestOptions CARD_OPTS = new RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .placeholder(R.drawable.card_bg);

    static void loadCard(ImageView iv, String url) {
        Glide.with(iv).load(Api.img(url, 400)).apply(CARD_OPTS)
                .transition(DrawableTransitionOptions.withCrossFade(120)).into(iv);
    }

    static void loadBackdrop(ImageView iv, String url) {
        if (url == null) {
            Glide.with(iv).clear(iv);
            iv.setImageDrawable(null);
            return;
        }
        Glide.with(iv).load(Api.img(url, 1280))
                .transition(DrawableTransitionOptions.withCrossFade(250)).into(iv);
    }

    static void loadLogo(ImageView iv, String url) {
        Glide.with(iv).load(Api.img(url, 640)).into(iv);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void hideSystemUi(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** "2024 · Acción, Drama · 1 h 18 min" */
    static String meta(Api.Show s) {
        StringBuilder sb = new StringBuilder();
        if (s.year > 0) sb.append(s.year);
        String g = genres(s.genres);
        if (!g.isEmpty()) sb.append(sb.length() > 0 ? "  ·  " : "").append(g);
        if (s.duration > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append(duration(s.duration));
        if (s.isSeries()) sb.append(sb.length() > 0 ? "  ·  " : "").append("Serie");
        return sb.toString();
    }

    static String duration(int seconds) {
        int min = Math.round(seconds / 60f);
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    private static final Map<String, String> GENRES = new HashMap<>();

    static {
        String[][] g = {
                {"action", "Acción"}, {"adventure", "Aventura"}, {"animation", "Animación"},
                {"biography", "Biografía"}, {"comedy", "Comedia"}, {"crime", "Crimen"},
                {"documentary", "Documental"}, {"drama", "Drama"}, {"family", "Familiar"},
                {"fantasy", "Fantasía"}, {"history", "Historia"}, {"horror", "Terror"},
                {"music", "Música"}, {"musical", "Musical"}, {"mystery", "Misterio"},
                {"romance", "Romance"}, {"science_fiction", "Ciencia ficción"},
                {"sci-fi", "Ciencia ficción"}, {"scifi", "Ciencia ficción"}, {"thriller", "Suspenso"},
                {"war", "Bélica"}, {"western", "Western"}, {"sports", "Deportes"},
                {"sport", "Deportes"}, {"football", "Fútbol"}, {"kids", "Infantil"},
                {"children", "Infantil"}, {"short", "Cortometraje"}, {"experimental", "Experimental"},
                {"politics", "Política"}, {"arts", "Artes"}, {"art", "Arte"}, {"lgbt", "LGBTIQ+"}, {"suspense", "Suspenso"},
        };
        for (String[] p : g) GENRES.put(p[0], p[1]);
    }

    static String genres(List<String> list) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String raw : list) {
            if (raw == null || raw.isEmpty() || n == 3) continue;
            String k = raw.toLowerCase(Locale.ROOT).trim();
            String name = GENRES.get(k);
            if (name == null) name = k.substring(0, 1).toUpperCase(Locale.ROOT) + k.substring(1).replace('_', ' ');
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
            n++;
        }
        return sb.toString();
    }

    static Intent detailIntent(Context c, Api.Show s) {
        return new Intent(c, DetailActivity.class)
                .putExtra(EXTRA_ID, s.id)
                .putExtra(EXTRA_TITLE, s.title)
                .putExtra(EXTRA_IMAGE, s.background != null ? s.background : s.landscape);
    }

    /** Abre el reproductor; si no hay sesión, primero la pantalla de inicio de sesión. */
    static void play(Activity a, String mediaId, String title, String showId, String image) {
        Intent player = new Intent(a, PlayerActivity.class)
                .putExtra(EXTRA_MEDIA, mediaId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SHOW, showId)
                .putExtra(EXTRA_IMAGE, image);
        if (Api.get(a).isLoggedIn()) {
            a.startActivity(player);
        } else {
            a.startActivity(new Intent(a, LoginActivity.class).putExtra(LoginActivity.EXTRA_NEXT, player));
        }
    }
}
