# Ondamedia para Fire TV

App sencilla que abre **https://ondamedia.cl** a pantalla completa en el Fire TV Stick,
con un cursor que se maneja con el control remoto.

## Controles

| Botón del control | Acción |
|---|---|
| Flechas | Mueven el cursor (mantén presionado para ir más rápido). En los bordes desplazan la página. |
| Botón central (OK) | Clic donde está el cursor |
| Menú (≡) | Activa/desactiva el cursor (sin cursor, las flechas saltan entre enlaces) |
| Play/Pausa | Pausa o reanuda el video |
| ⏩ / ⏪ | Adelanta / retrocede 15 segundos |
| Atrás | Sale de pantalla completa → página anterior → (dos veces) cierra la app |

## 1. Obtener el APK

**Opción A – GitHub (sin instalar nada):**
1. Crea un repositorio en GitHub y sube esta carpeta completa.
2. Ve a la pestaña **Actions** → "Compilar APK". Se ejecuta solo al subir los archivos.
3. Cuando termine (unos 3–5 min), descarga el artefacto **Ondamedia-FireTV** (un .zip con el `.apk`).

**Opción B – Android Studio:** abre la carpeta y usa *Build → Build APK(s)*,
o en terminal: `./gradlew assembleRelease`. El APK queda en `app/build/outputs/apk/release/`.

## 2. Instalar en el Fire TV Stick

1. En el Fire TV: *Configuración → Mi Fire TV → Opciones para desarrolladores* y activa
   **Depuración ADB** e **Instalar apps desconocidas** (para la app *Downloader*).
   Si no ves "Opciones para desarrolladores": *Mi Fire TV → Acerca de* y presiona 7 veces
   el nombre del dispositivo.
2. Instala la app **Downloader** desde la tienda de Amazon.
3. Sube el APK a algún lugar con enlace directo (Google Drive, Dropbox, etc.) y escribe
   ese enlace en Downloader. Se descargará e instalará.
   *(Alternativa con PC: `adb connect IP_DEL_FIRETV` y `adb install Ondamedia-FireTV.apk`.)*
4. La app aparece en *Tus apps y canales* como **Ondamedia**.
