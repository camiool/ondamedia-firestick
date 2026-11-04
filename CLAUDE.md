# Ondamedia para Fire TV: contexto para continuar el trabajo

## Objetivo
App Android para el **Amazon Fire TV Stick** que muestra **https://ondamedia.cl** (cine chileno
gratuito) a pantalla completa y se maneja con el control remoto como una app de TV (estilo
Netflix): las flechas saltan entre películas y botones, sin cursor. Se instala por sideload, no
por la Amazon Appstore. El usuario habla español: responde y escribe los textos de la app en español.

## Decisión de diseño
Se evaluó hacer una app 100 % nativa leyendo el catálogo de la API GraphQL de Mediastream
(`next.mediastream.co/graphql`, la plataforma sobre la que corre ondamedia.cl). Se descartó:
es una API privada sin documentar y habría que hacerse pasar por el sitio web. Por eso la app
sigue usando el sitio normal en un WebView y le agrega navegación de TV con JS (`tvnav.js`).
No volver a esa idea sin que el usuario lo pida explícitamente.

## Estado actual
- Repo: https://github.com/camiool/ondamedia-firestick.
- Compila local (`./gradlew assembleRelease`, JDK 17 de Homebrew en
  `/opt/homebrew/opt/openjdk@17`) y en GitHub Actions.
- Probado en un emulador Android 15 a 1920×1080 / 320 dpi (AVD `TV_1080p`, imagen de teléfono),
  contra el sitio real: inicio, filas, carruseles, menú Explorar, ficha de película, login
  (teclado), tráiler a pantalla completa y Atrás.
- **No probado:** reproducir una película completa (requiere iniciar sesión) ni el rendimiento en
  el Fire TV real.
- Los PNG del ícono y del banner son provisionales.

## Estructura
- `app/src/main/assets/tvnav.js`: se inyecta en cada carga. Hace:
  - viewport fijo de 1280 px de ancho CSS, para que el sitio use su diseño de escritorio (el
    móvil tiene barra inferior y no se ve como TV);
  - navegación espacial: `__tv.key(dir)` elige el siguiente elemento (izq/der: misma fila;
    arriba/abajo: distancia vertical + 2× desvío horizontal, saltando elementos bajitos como
    los títulos de fila). Los carruseles Swiper avanzan con `swiper.slideNext()` al llegar al borde,
    y el autoplay del banner se detiene al seleccionar algo en él;
  - un marco de selección propio (`#tv-ring`, `position:fixed`) que sigue al elemento en cada
    frame. No se usa solo una clase CSS porque React la borra al volver a renderizar;
  - OK devuelve `"tap:fx,fy"` y la app hace un toque real ahí. Así el sitio lo recibe como un
    gesto del usuario, necesario para el historial (Atrás), el teclado y la pantalla completa;
  - tras un OK, si aparece el iframe del reproductor (`mdstrm.com/embed`), lo pone a pantalla
    completa (Fullscreen API, o CSS como alternativa). En el reproductor, `key()` devuelve
    `"player"` y la app le manda las teclas (OK → espacio);
  - recuerda la selección de cada página para restaurarla al volver con Atrás;
  - `__tv.back()` / `__tv.closePlayer()` cierran el reproductor con el botón × del sitio.
- `app/src/main/java/cl/ondamedia/tv/MainActivity.java`: WebView, pantalla de carga, inyección
  del JS (`onPageStarted`, `onPageCommitVisible`, `onPageFinished`, `onProgressChanged`),
  bloqueo de dominios de analítica, `dispatchKeyEvent`, pantalla completa
  (`onShowCustomView`), puente `TVBridge.showKeyboard()` y modo cursor alternativo (tecla Menú).
  En builds debug activa `WebView.setWebContentsDebuggingEnabled`.
- `CursorView.java`: puntero del modo cursor alternativo.
- `app/build.gradle`: AGP 8.7.3, Gradle 8.14.3, compileSdk/targetSdk 34, minSdk 22, Java 17.
  `release` se firma con la clave debug. Sin dependencias externas.

## Cómo depurar el JS en el emulador
1. `./gradlew assembleDebug` e instala con `adb -s emulator-5554 install -r ...`.
   **Usa siempre `-s` o `ANDROID_SERIAL`**: el Fire TV del usuario puede estar conectado por adb
   en la red (192.168.1.82:5555) y un `adb install` sin serial le cae a él.
2. `adb shell cat /proc/net/unix | grep webview_devtools_remote` y
   `adb forward tcp:9222 localabstract:<socket>`.
3. Evalúa JS con el protocolo de Chrome DevTools (`http://127.0.0.1:9222/json` → WebSocket,
   `Runtime.evaluate`). `__tv.current()` devuelve el elemento seleccionado.
4. Envía teclas con `adb shell input keyevent DPAD_DOWN|DPAD_CENTER|BACK...`.

## Próximos pasos posibles
1. Probar en el Fire TV real, con sesión iniciada, que "Ver ahora" reproduzca la película a
   pantalla completa y que el rendimiento sea bueno.
2. Si el sitio cambia sus clases (`preview-container-inner`, `next-player`, `button-chevron`,
   `modal-action-button-icon`), ajustar `tvnav.js`.
3. Mejorar el ícono y el banner (banner de 320×180 en `drawable`).

## Restricciones
- Debe funcionar sin Google Play Services, porque Fire OS no los tiene.
- Mantenerlo simple y sin dependencias, salvo que sean necesarias.
