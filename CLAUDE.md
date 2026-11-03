# Ondamedia para Fire TV: contexto para continuar el trabajo

## Objetivo
App Android para el **Amazon Fire TV Stick** que muestra **https://ondamedia.cl** (cine chileno
gratuito) a pantalla completa y se maneja con el control remoto. Se instala por sideload, no por
la Amazon Appstore. El usuario habla español: responde y escribe los textos de la app en español.

## Estado actual
- El proyecto está en https://github.com/camiool/ondamedia-firestick (rama `main`).
- `./gradlew assembleRelease` **compila sin errores en GitHub Actions** y genera el APK como
  artefacto `Ondamedia-FireTV`.
- **Todavía no se ha probado en un Fire TV**, ni contra el sitio real (el entorno donde se creó
  no tenía acceso a ondamedia.cl).
- Los PNG del ícono y del banner se generaron con un script y son provisionales.

## Estructura
- `app/src/main/java/cl/ondamedia/tv/MainActivity.java`: WebView a pantalla completa. Se encarga de:
  - el modo cursor y el manejo de teclas en `dispatchKeyEvent`;
  - el video a pantalla completa con `WebChromeClient.onShowCustomView`;
  - los botones multimedia, que inyectan JS sobre el `<video>`;
  - el botón Atrás: sale de pantalla completa, luego `goBack()` y luego pide confirmar para salir;
  - una página de error si no hay conexión.
- `app/src/main/java/cl/ondamedia/tv/CursorView.java`: dibuja el puntero y lo oculta tras 5 s sin
  moverse.
- `app/src/main/AndroidManifest.xml`: categorías `LEANBACK_LAUNCHER` y `LAUNCHER`, `android:banner`,
  touchscreen y leanback con `required=false`.
- `app/build.gradle`: AGP 8.7.3, Gradle 8.14.3 (wrapper incluido), compileSdk y targetSdk 34,
  minSdk 22, Java 17. El build `release` se firma con la **clave debug** para poder instalarlo
  directo. No usa dependencias externas, ni siquiera AndroidX.
- `.github/workflows/build.yml`: compila `assembleRelease` en GitHub Actions y sube el APK como
  artefacto.
- `LEEME.md`: instrucciones para el usuario final (controles e instalación con Downloader o adb).

## Controles implementados
| Tecla | Acción |
|---|---|
| Flechas | Mueven el cursor, con aceleración si se mantienen presionadas. En el borde desplazan la página. |
| OK / Enter | Envía un `ACTION_DOWN`/`ACTION_UP` táctil en la posición del cursor. |
| Menú | Activa o desactiva el modo cursor. Sin cursor, el WebView usa su navegación nativa con el D-pad. |
| Play/Pausa, FF, REW | JS sobre el primer `<video>` de la página o de un iframe del mismo origen. |
| Atrás | Sale de pantalla completa → `goBack()` → doble pulsación para salir. |

## Próximos pasos
1. **Compilar localmente** (en CI ya funciona): `./gradlew assembleRelease`. Requiere el Android SDK (API 34) y JDK 17, con
   `ANDROID_HOME` definido o un `local.properties` con `sdk.dir=...`. Corrige lo que falle (lint,
   recursos, etc.).
2. **Probar.** Si el usuario tiene el Fire TV en la misma red, usa
   `adb connect <IP>:5555` y luego `adb install -r app/build/outputs/apk/release/app-release.apk`.
   Si no, usa un emulador de Android TV.
3. **Verificar con el sitio real:**
   - que ondamedia.cl cargue, que se pueda iniciar sesión (si aplica) y que los videos se
     reproduzcan;
   - si usa DRM (Widevine), revisar si hace falta `onPermissionRequest` en el WebChromeClient para
     conceder `RESOURCE_PROTECTED_MEDIA_ID`. Es probable que haga falta;
   - si el reproductor está en un iframe de otro dominio, los botones multimedia por JS no
     funcionarán. Habría que evaluar una alternativa, por ejemplo enviar teclas como espacio o
     flechas al WebView.
4. **Puntos dudosos del código:**
   - `scrollPage()` convierte píxeles a coordenadas CSS dividiendo solo por `density`. Puede fallar
     si la página no usa un viewport `width=device-width`.
   - Revisar si el sitio responde bien a los eventos táctiles sintéticos y si los campos de texto
     abren el teclado de Fire TV.
   - El `User-Agent` quita `"; wv"` para que el sitio no detecte que es un WebView. Comprobar que
     no rompa nada.
5. Si el usuario lo pide, mejorar el ícono y el banner (banner de 320x180 en `drawable`).

## Restricciones
- Debe funcionar sin Google Play Services, porque Fire OS no los tiene.
- Mantenerlo simple y sin dependencias, salvo que sean necesarias.
