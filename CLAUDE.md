# Ondamedia para Fire TV: contexto para continuar el trabajo

## Objetivo
App para el **Amazon Fire TV Stick** para ver **Ondamedia** (cine chileno gratuito,
ondamedia.cl) con el control remoto, con una interfaz estilo Netflix. Se instala por sideload.
El usuario habla español: responde y escribe los textos de la app en español.

## Ramas
- `tv-navegacion`: versión WebView (el sitio web con navegación por flechas inyectada,
  `assets/tvnav.js`). Funciona, pero es lenta y en el reproductor las teclas no llegan al iframe.
  applicationId `cl.ondamedia.tv`.
- `app-nativa` (esta): app nativa que usa la API del sitio. applicationId
  `cl.ondamedia.tv.nativa`, para tener las dos instaladas y compararlas.
  Si la nativa da problemas, el usuario prefiere volver a la WebView: no borrar esa rama.

## Decisión sobre la API
ondamedia.cl corre sobre la plataforma OTT de Mediastream. La app usa su API GraphQL
(`https://next.mediastream.co/graphql`, header `x-client-id` del sitio). Es una API privada sin
documentar: el usuario lo sabe y autorizó usarla explícitamente el 2026-11-04. Puede cambiar
sin aviso. Límite que respetamos: **no** falsificar `Referer`/origen ni saltarse los tokens; la
reproducción usa el token que la API entrega a un usuario con sesión iniciada, igual que el sitio.

- Catálogo (`getCategoriesPaginated` con `custom.content-home`, `getCategory`, `getShow`,
  `getSeasons`, `getEpisodesPaginated`, `search2`): sin sesión.
- Login: `POST https://next.mediastream.co/login` con JSON `{email, username, password,
  grant_type: "password"}` → `data.access_token`. Se manda como `Authorization: Bearer`.
  Probado con la cuenta del usuario: funciona.
- Las consultas con sesión mandan también `x-profile-id` (el `data.customer.profileId` del JWT
  de sesión), como el sitio.
- Video: `getMedia(_id) { accessToken(maxUse: $maxUse) }` (requiere sesión). **El argumento
  `maxUse` es obligatorio aunque vaya vacío**: sin él la API responde
  `UNAUTHORIZED_GET_ACCESS_TOKEN` incluso con sesión válida (verificado el 2026-11-04 comparando
  con el sitio). El HLS es H.264/AAC sin DRM →
  `https://mdstrm.com/video/{id}.m3u8?access_token=...` (HLS en ExoPlayer). Si ExoPlayer falla,
  `EmbedPlayerActivity` abre `https://mdstrm.com/embed/{id}?access_token=...` en un WebView como
  página principal y controla su `<video>` con JS.
- Imágenes: el CDN acepta `?w=ANCHO` (ver `Api.img`).

## Estructura (app/src/main/java/cl/ondamedia/tv)
- `Api.java`: cliente HTTP/GraphQL, sesión (SharedPreferences "sesion"), modelos.
- `HomeActivity`: panel de la película seleccionada + filas (`Rows`). Fila "Seguir viendo" local.
- `Rows.java`: RecyclerView de filas y tarjetas; `AlignedLayoutManager` deja la selección
  alineada a la izquierda/arriba como en las apps de TV.
- `DetailActivity`, `SearchActivity`, `LoginActivity`.
- `PlayerActivity`: ExoPlayer (media3 1.4.1) con teclas propias; guarda el progreso (`Progress`).
- `EmbedPlayerActivity`: reproductor web de respaldo.
- Dependencias: media3 (exoplayer, hls, ui), recyclerview, glide. compileSdk 34, minSdk 22.

## Estado
- Probado en emulador (AVD `TV_1080p`, 1920×1080): inicio, filas, carga de más títulos,
  ficha, series con capítulos, búsqueda, pantalla de login.
- **Sin probar:** login real y reproducción (requieren la cuenta del usuario), y el rendimiento en
  el Fire TV real.

## Cómo probar
- Compilar: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`.
- **Usa siempre `adb -s emulator-5554` o `ANDROID_SERIAL`**: el Fire TV del usuario suele estar
  conectado por adb (192.168.1.82:5555) y un comando sin serial le cae a él.
- `adb shell input keyevent DPAD_DOWN|DPAD_CENTER|BACK` y `adb exec-out screencap -p`.

## Restricciones
- Debe funcionar sin Google Play Services (Fire OS no los tiene).
- No guardar contraseñas; solo el token de sesión.
