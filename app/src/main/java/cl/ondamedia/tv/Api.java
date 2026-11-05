package cl.ondamedia.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cliente de la API de Ondamedia (plataforma Mediastream): la misma que usa ondamedia.cl.
 *
 * - Catálogo y búsqueda: GraphQL en next.mediastream.co, no requiere sesión.
 * - Sesión: POST /login con correo y contraseña; devuelve un access_token que se manda como
 *   "Authorization: Bearer" en las consultas siguientes.
 * - Video: con sesión, getMedia entrega un token de acceso para el HLS de mdstrm.com.
 */
public final class Api {

    private static final String BASE = "https://next.mediastream.co";
    private static final String CLIENT_ID = "15f8cc57844b0f9d6826a9abeec902c102f74621391fbb672c";

    private static final String SHOW_FIELDS =
            "_id title type genres description "
            + "details { yearRelease shortDescription longSynopsis "
            + "  images { square background customBackground logo cover } } "
            + "media { _id duration }";

    public interface Callback<T> {
        void ok(T result);
        void error(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Api instance;

    private final SharedPreferences prefs;

    private Api(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("sesion", Context.MODE_PRIVATE);
    }

    public static synchronized Api get(Context ctx) {
        if (instance == null) instance = new Api(ctx);
        return instance;
    }

    // ---------- Sesión ----------

    public boolean isLoggedIn() {
        return prefs.getString("token", null) != null;
    }

    public String userEmail() {
        return prefs.getString("email", "");
    }

    public void logout() {
        prefs.edit().remove("token").remove("profile").apply();
    }

    public void login(String email, String password, Callback<Void> cb) {
        run(cb, () -> {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("username", email);
            body.put("password", password);
            body.put("grant_type", "password");
            JSONObject res = request("/login", body.toString(), false);
            String token = null;
            JSONObject data = res.optJSONObject("data");
            if (data != null) token = data.optString("access_token", null);
            if (token == null) token = res.optString("access_token", null);
            if (token == null || token.isEmpty()) throw new ApiException(loginError(res));
            // El perfil puede venir en la respuesta; si no, ensureProfile() lo busca después.
            String profile = null;
            JSONObject inner = data != null ? data.optJSONObject("data") : null;
            if (inner != null) profile = str(inner, "profileId");
            prefs.edit().putString("token", token).putString("email", email)
                    .putString("profile", profile).apply();
            ensureProfile();
            return null;
        });
    }

    /**
     * El sitio manda el perfil del usuario ("x-profile-id") en cada consulta, y sin él la API no
     * entrega el token de los videos. Lo sacamos del token de sesión (un JWT) o, si no viene ahí,
     * de la lista de perfiles de la cuenta (el del titular).
     */
    private void ensureProfile() throws Exception {
        if (prefs.getString("profile", null) != null || !isLoggedIn()) return;
        String profile = profileFromJwt(prefs.getString("token", ""));
        if (profile == null) {
            JSONObject data = graphql("query { getProfiles { _id isOwner } }", null);
            JSONArray list = data.optJSONArray("getProfiles");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    if (profile == null || p.optBoolean("isOwner")) profile = str(p, "_id");
                    if (p.optBoolean("isOwner")) break;
                }
            }
        }
        if (profile != null) prefs.edit().putString("profile", profile).apply();
    }

    /** ¿La API sigue reconociendo la sesión? (pide los datos de la cuenta). */
    private boolean sessionStillValid() {
        try {
            JSONObject data = graphql("query { getCustomer { _id } }", null);
            JSONObject c = data.optJSONObject("getCustomer");
            return c != null && !c.isNull("_id");
        } catch (ApiException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
            return !(m.contains("UNAUTH") || m.contains("TOKEN") || m.contains("JWT") || m.contains("LOGIN"));
        } catch (Exception e) {
            return true;  // sin red u otro problema: no cerramos la sesión por eso
        }
    }

    private static String profileFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String json = new String(android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
            JSONObject payload = new JSONObject(json);
            JSONObject data = payload.optJSONObject("data");
            JSONObject customer = data != null ? data.optJSONObject("customer") : null;
            if (customer == null) customer = payload.optJSONObject("customer");
            return customer != null ? str(customer, "profileId") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String loginError(JSONObject res) {
        String msg = res.optString("message", "");
        JSONObject err = res.optJSONObject("error");
        if (err != null) msg = err.optString("message", msg);
        if (msg.toLowerCase().contains("password") || msg.toLowerCase().contains("credential")
                || msg.toLowerCase().contains("invalid")) {
            return "Correo o contraseña incorrectos.";
        }
        return msg.isEmpty() ? "No se pudo iniciar sesión." : "No se pudo iniciar sesión: " + msg;
    }

    // ---------- Catálogo ----------

    /** Filas de la portada (las mismas categorías que muestra el sitio). */
    public void home(Callback<List<Category>> cb) {
        run(cb, () -> {
            String q = "query { getCategoriesPaginated(filters:[{field:\"custom.content-home\"}] "
                    + "page:{page:1 limit:40}) { data { ...on Category { _id name "
                    + "content(page:{limit:20}) { total data { ...on Show { " + SHOW_FIELDS + " } } } } } } }";
            JSONObject data = graphql(q, null);
            JSONArray cats = data.getJSONObject("getCategoriesPaginated").getJSONArray("data");
            List<Category> out = new ArrayList<>();
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.getJSONObject(i);
                JSONObject content = c.optJSONObject("content");
                if (content == null) continue;
                Category cat = new Category(c.getString("_id"), c.optString("name"),
                        content.optInt("total"));
                addShows(content.optJSONArray("data"), cat.shows);
                if (!cat.shows.isEmpty()) out.add(cat);
            }
            return out;
        });
    }

    /** Más películas de una categoría (para cuando se llega al final de una fila). */
    public void categoryPage(String id, int page, Callback<List<Show>> cb) {
        run(cb, () -> {
            String q = "query($id:String!,$page:Int!){ getCategory(_id:$id) { content(page:{page:$page,limit:20}) "
                    + "{ data { ...on Show { " + SHOW_FIELDS + " } } } } }";
            JSONObject vars = new JSONObject().put("id", id).put("page", page);
            JSONObject data = graphql(q, vars);
            List<Show> out = new ArrayList<>();
            addShows(data.getJSONObject("getCategory").getJSONObject("content").optJSONArray("data"), out);
            return out;
        });
    }

    public void search(String text, Callback<List<Show>> cb) {
        run(cb, () -> {
            String q = "query($q:String!){ search2(q:$q, page:{limit:40,page:1}) { data { "
                    + "...on Show { " + SHOW_FIELDS + " } } } }";
            JSONObject data = graphql(q, new JSONObject().put("q", text));
            List<Show> out = new ArrayList<>();
            addShows(data.getJSONObject("search2").optJSONArray("data"), out);
            return out;
        });
    }

    /** Ficha completa, con el tráiler y (si es serie) las temporadas y episodios. */
    public void show(String id, Callback<Show> cb) {
        run(cb, () -> {
            String q = "query($id:String!){ getShow(_id:$id) { " + SHOW_FIELDS
                    + " details { contentRating { value } } trailer { _id } } "
                    + "getSeasons(show:$id) { _id title order } }";
            JSONObject data = graphql(q, new JSONObject().put("id", id));
            Show s = Show.from(data.getJSONObject("getShow"));
            JSONObject tr = data.getJSONObject("getShow").optJSONObject("trailer");
            if (tr != null) s.trailerId = tr.optString("_id", null);
            JSONArray seasons = data.optJSONArray("getSeasons");
            if (seasons != null) {
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject se = seasons.getJSONObject(i);
                    Category season = new Category(se.getString("_id"), se.optString("title"), 0);
                    season.order = se.optInt("order");
                    season.shows.addAll(episodes(season.id));
                    if (!season.shows.isEmpty()) s.seasons.add(season);
                }
                s.seasons.sort((a, b) -> Integer.compare(a.order, b.order));
            }
            return s;
        });
    }

    private List<Show> episodes(String seasonId) throws Exception {
        String q = "query($id:String!){ getEpisodesPaginated(season:$id, page:{page:1,limit:100}, "
                + "sort:{field:\"order\",dir:\"DESC\"}) { data { ...on ShowEpisode { _id title order images "
                + "description content { ...on Media { _id duration } } } } } }";
        JSONObject data = graphql(q, new JSONObject().put("id", seasonId));
        JSONArray arr = data.getJSONObject("getEpisodesPaginated").optJSONArray("data");
        List<Show> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            Show ep = new Show();
            ep.id = e.getString("_id");
            ep.type = "episode";
            ep.title = e.optString("title");
            ep.description = e.optString("description", "");
            JSONArray imgs = e.optJSONArray("images");
            if (imgs != null && imgs.length() > 0) ep.landscape = imgs.optString(0, null);
            JSONObject c = e.optJSONObject("content");
            if (c != null) {
                ep.mediaId = c.optString("_id", null);
                ep.duration = c.optInt("duration");
            }
            out.add(ep);
        }
        return out;
    }

    /** URL del video lista para reproducir. Requiere sesión iniciada. */
    public void playback(String mediaId, Callback<Playback> cb) {
        run(cb, () -> {
            if (!isLoggedIn()) throw new ApiException(ApiException.LOGIN_REQUIRED);
            ensureProfile();
            // Ojo: accessToken tiene que ir con el argumento maxUse (aunque vaya vacío), como lo pide
            // el sitio. Sin el argumento, la API responde UNAUTHORIZED_GET_ACCESS_TOKEN.
            String q = "query getPlayerMedia($id:String!,$maxUse:Int){ getMedia(_id:$id) "
                    + "{ _id title duration accessToken(maxUse:$maxUse) } }";
            JSONObject data;
            try {
                data = graphql(q, new JSONObject().put("id", mediaId));
            } catch (ApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                // Solo pedimos iniciar sesión de nuevo si la sesión de verdad ya no sirve; si no,
                // mostramos el error (antes esto hacía un ciclo infinito de inicio de sesión).
                if (!sessionStillValid()) {
                    logout();
                    throw new ApiException(ApiException.LOGIN_REQUIRED);
                }
                throw new ApiException("Ondamedia no entregó este video (" + msg + ").");
            }
            JSONObject m = data.getJSONObject("getMedia");
            String token = m.isNull("accessToken") ? "" : m.optString("accessToken", "");
            if (token.isEmpty()) {
                if (!sessionStillValid()) {
                    logout();
                    throw new ApiException(ApiException.LOGIN_REQUIRED);
                }
                throw new ApiException("Ondamedia no entregó este video (sin token de acceso).");
            }
            Playback p = new Playback();
            p.mediaId = mediaId;
            p.title = m.optString("title");
            p.hlsUrl = "https://mdstrm.com/video/" + mediaId + ".m3u8?access_token=" + token;
            p.embedUrl = "https://mdstrm.com/embed/" + mediaId + "?access_token=" + token
                    + "&autoplay=true&controls=true";
            return p;
        });
    }

    // ---------- Modelos ----------

    public static final class Category {
        public final String id;
        public final String name;
        public final int total;
        public int order;
        public int loadedPages = 1;
        public boolean loading;
        public final List<Show> shows = new ArrayList<>();

        Category(String id, String name, int total) {
            this.id = id;
            this.name = name;
            this.total = total;
        }

        public boolean hasMore() {
            return shows.size() < total;
        }
    }

    public static final class Show {
        public String id, title, type, description = "", mediaId, trailerId;
        public String landscape, background, logo, poster, rating;
        public int year, duration;
        public final List<String> genres = new ArrayList<>();
        public final List<Category> seasons = new ArrayList<>();

        public boolean isSeries() {
            return "tvshow".equals(type) || "serie".equals(type) || "series".equals(type);
        }

        static Show from(JSONObject o) {
            Show s = new Show();
            s.id = o.optString("_id");
            s.title = o.optString("title");
            s.type = o.optString("type");
            s.description = o.optString("description", "");
            JSONArray g = o.optJSONArray("genres");
            if (g != null) for (int i = 0; i < g.length(); i++) s.genres.add(g.optString(i));
            JSONObject d = o.optJSONObject("details");
            if (d != null) {
                s.year = d.optInt("yearRelease");
                String longSyn = str(d, "longSynopsis");
                if (longSyn != null && longSyn.length() > s.description.length()) s.description = longSyn;
                JSONObject im = d.optJSONObject("images");
                if (im != null) {
                    s.landscape = str(im, "square");
                    s.background = str(im, "customBackground");
                    if (s.background == null) s.background = str(im, "background");
                    if (s.landscape == null) s.landscape = s.background;
                    s.logo = str(im, "logo");
                    s.poster = str(im, "cover");
                }
                JSONObject cr = d.optJSONObject("contentRating");
                if (cr != null) s.rating = str(cr, "value");
            }
            JSONObject m = o.optJSONObject("media");
            if (m != null) {
                s.mediaId = str(m, "_id");
                s.duration = m.optInt("duration");
            }
            return s;
        }
    }

    public static final class Playback {
        public String mediaId, title, hlsUrl, embedUrl;
    }

    public static final class ApiException extends Exception {
        public static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";

        ApiException(String msg) {
            super(msg);
        }
    }

    // ---------- Utilidades ----------

    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static void addShows(JSONArray arr, List<Show> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.has("_id") || !o.has("title")) continue;  // tipos que no son Show
            out.add(Show.from(o));
        }
    }

    /** Pide una imagen del CDN reducida al ancho indicado (en píxeles). */
    public static String img(String url, int width) {
        if (url == null) return null;
        return url + (url.contains("?") ? "&" : "?") + "w=" + width;
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> void run(Callback<T> cb, Work<T> work) {
        POOL.execute(() -> {
            try {
                T r = work.run();
                MAIN.post(() -> cb.ok(r));
            } catch (ApiException e) {
                MAIN.post(() -> cb.error(e.getMessage()));
            } catch (IOException e) {
                MAIN.post(() -> cb.error("Sin conexión con Ondamedia. Revisa tu internet."));
            } catch (Exception e) {
                MAIN.post(() -> cb.error("Error inesperado: " + e.getMessage()));
            }
        });
    }

    private JSONObject graphql(String query, JSONObject vars) throws Exception {
        JSONObject body = new JSONObject().put("query", query);
        if (vars != null) body.put("variables", vars);
        JSONObject res = request("/graphql", body.toString(), true);
        JSONArray errors = res.optJSONArray("errors");
        JSONObject data = res.optJSONObject("data");
        if (errors != null && errors.length() > 0) {
            String msg = errors.getJSONObject(0).optString("message", "Error de la API");
            // Un error parcial (p. ej. sin token) igual puede traer datos útiles.
            if (data == null || msg.contains("UNAUTHORIZED")) throw new ApiException(msg);
        }
        if (data == null) throw new ApiException("Respuesta vacía de la API");
        return data;
    }

    private JSONObject request(String path, String body, boolean auth) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("x-client-id", CLIENT_ID);
            String token = prefs.getString("token", null);
            if (auth && token != null) {
                c.setRequestProperty("Authorization", "Bearer " + token);
                String profile = prefs.getString("profile", null);
                if (profile != null) c.setRequestProperty("x-profile-id", profile);
            }
            c.setRequestProperty("x-ott-language", "es");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (text.isEmpty()) throw new IOException("HTTP " + code);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
