package cl.ondamedia.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Guarda en el dispositivo por dónde iba cada video, para "Seguir viendo" y para retomar
 * la reproducción donde quedó.
 */
final class Progress {

    private static final int MAX = 20;

    private final SharedPreferences prefs;

    Progress(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("progreso", Context.MODE_PRIVATE);
    }

    /** Posición guardada en ms, o 0 si conviene empezar desde el principio. */
    long resumePosition(String mediaId) {
        JSONObject e = find(mediaId);
        if (e == null) return 0;
        long pos = e.optLong("pos"), dur = e.optLong("dur");
        if (pos < 30_000 || (dur > 0 && pos > dur - 90_000)) return 0;
        return pos;
    }

    /** Fracción vista (0..1) para la barra de progreso de la tarjeta. */
    float fraction(String mediaId) {
        JSONObject e = find(mediaId);
        if (e == null) return 0;
        long dur = e.optLong("dur");
        return dur > 0 ? Math.min(1f, e.optLong("pos") / (float) dur) : 0;
    }

    void save(String mediaId, String showId, String title, String image, long pos, long dur) {
        if (mediaId == null || pos <= 0) return;
        JSONArray arr = all();
        JSONArray out = new JSONArray();
        boolean finished = dur > 0 && pos > dur - 90_000;
        try {
            if (!finished) {
                out.put(new JSONObject().put("media", mediaId).put("show", showId).put("title", title)
                        .put("image", image).put("pos", pos).put("dur", dur));
            }
            for (int i = 0; i < arr.length() && out.length() < MAX; i++) {
                JSONObject e = arr.getJSONObject(i);
                if (!mediaId.equals(e.optString("media"))) out.put(e);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("items", out.toString()).apply();
    }

    /** Lo último que se estaba viendo, más reciente primero, como tarjetas. */
    List<Api.Show> continueWatching() {
        List<Api.Show> list = new ArrayList<>();
        JSONArray arr = all();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            Api.Show s = new Api.Show();
            s.id = e.optString("show", null);
            s.mediaId = e.optString("media");
            s.title = e.optString("title");
            s.landscape = e.optString("image", null);
            s.background = s.landscape;
            s.type = "continue";
            list.add(s);
        }
        return list;
    }

    private JSONObject find(String mediaId) {
        JSONArray arr = all();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && mediaId.equals(e.optString("media"))) return e;
        }
        return null;
    }

    private JSONArray all() {
        try {
            return new JSONArray(prefs.getString("items", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
