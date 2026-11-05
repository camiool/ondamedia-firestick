# Ondamedia para Fire TV (app nativa)

App nativa para ver **Ondamedia** (cine chileno gratuito) en el Fire TV Stick, con una interfaz
de TV estilo Netflix: filas de películas, ficha de cada título y reproductor de video nativo.
Usa el catálogo y las cuentas de ondamedia.cl.

> Esta rama (`app-nativa`) es la versión nativa. La versión que muestra el sitio web en un
> WebView está en la rama `tv-navegacion`. Las dos se pueden tener instaladas a la vez:
> esta aparece como **Ondamedia Nativa**.

## Qué tiene

- **Inicio:** arriba, la imagen y los datos de la película seleccionada; abajo, las filas de
  categorías del sitio. Las filas cargan más títulos al llegar al final.
- **Seguir viendo:** la primera fila muestra lo que dejaste a medias en este Fire TV.
- **Ficha:** sinopsis, año, géneros, duración, botones *Ver ahora* / *Continuar* y *Tráiler*.
  Las series muestran sus capítulos.
- **Búsqueda** con el teclado del control.
- **Inicio de sesión** con tu cuenta de Ondamedia (hace falta para ver videos, igual que en el
  sitio). Se guarda solo el token de sesión, no la contraseña.

## Controles

| Botón del control | Acción |
|---|---|
| Flechas | Mueven la selección entre películas y filas |
| OK | Abre lo seleccionado |
| Atrás | Vuelve a la pantalla anterior; en el inicio, dos veces para salir |
| **En el video:** OK o Play/Pausa | Pausa o reanuda |
| **En el video:** ← / → (o ⏪ / ⏩) | Retrocede / adelanta 10 s; manteniendo presionado, 30 s y luego 60 s |
| **En el video:** ↑ / ↓ | Muestran la barra de progreso |

## 1. Obtener el APK

**Opción A – GitHub:** en la pestaña **Actions** → "Compilar APK", abre la ejecución de la rama
`app-nativa` y descarga el artefacto **Ondamedia-FireTV** (un .zip con el `.apk`).

**Opción B – en tu computador:** `./gradlew assembleRelease` (requiere JDK 17 y el Android SDK).
El APK queda en `app/build/outputs/apk/release/`.

## 2. Instalar en el Fire TV Stick

1. En el Fire TV: *Configuración → Mi Fire TV → Opciones para desarrolladores* y activa
   **Depuración ADB** e **Instalar apps desconocidas** (para la app *Downloader*).
   Si no ves "Opciones para desarrolladores": *Mi Fire TV → Acerca de* y presiona 7 veces
   el nombre del dispositivo.
2. Instala la app **Downloader** desde la tienda de Amazon.
3. Sube el APK a algún lugar con enlace directo (Google Drive, Dropbox, etc.) y escribe
   ese enlace en Downloader. Se descargará e instalará.
   *(Alternativa con PC: `adb connect IP_DEL_FIRETV` y `adb install app-release.apk`.)*
4. La app aparece en *Tus apps y canales* como **Ondamedia Nativa**.
