# Echoes

Reproductor de música local para Android. Lee la música que tienes en el móvil, iguala el
volumen entre canciones, recorta silencios y tiene una radio (Flow) que aprende qué pones
después. También descarga canciones y playlists desde enlaces de YouTube y Spotify.

Requiere Android 11 o superior.

## Instalar

1. En el móvil, descarga el APK:
   **[Echoes.apk](https://github.com/Kolossus03/echoes/releases/latest/download/Echoes.apk)**.
   Desde el PC, escanea este código con la cámara del móvil:

   <img src="docs/qr.png" width="180" alt="Código QR para descargar Echoes.apk">

2. Abre el archivo descargado. Android pedirá permiso para instalar apps de esa fuente
   (el navegador o la app de archivos). Actívalo y pulsa **Instalar**.
3. Abre Echoes y permite el acceso a la música.

La primera vez, la app explica cómo meter música. Esa explicación sigue disponible en el botón ⓘ
de la pantalla Descargar.

## Actualizar

Cuando hay una versión nueva, aparece un aviso en Inicio. Tócalo, descarga el APK e instálalo
encima. No se pierde nada.

## Compilar

Necesitas JDK 17 y el Android SDK 36.

```sh
./gradlew assembleBaseRelease
```

El APK queda en `app/build/outputs/apk/base/release/`.

## Licencia

GPL-3.0. Usa [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) y la
fuente Space Grotesk (SIL Open Font License).

Descarga solo contenido que tengas derecho a guardar.
