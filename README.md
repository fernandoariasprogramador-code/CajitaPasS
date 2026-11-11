<p align="center">
  <img src="assets/branding/cps-logo.jpg" alt="CPS - CajitaPaaS Logo" width="420" />
</p>

# CPS (`CajitaPaaS`) — Mini OpenShift de Bolsillo para Android (ARM64)

**CPS** (**C**ajita**P**aa**S**) es una plataforma PaaS autoalojada sobre Android ARM64 con arquitectura dual (**Non-Root PRoot + PM2** / **Root cgroups + Dockerd**), expuesta de forma segura mediante **Caddy Server** y **Cloudflare Tunnels**, y administrada desde un **Control Plane Web con despliegues Blue/Green (Zero-Downtime)**.

## Identidad Visual y Branding (`CPS`)

- **Logo Oficial**: [`assets/branding/cps-logo.jpg`](assets/branding/cps-logo.jpg)
- **Abreviación del Sistema**: `CPS`
- **Paleta Oficial**:
  - Fondo Principal: `#0D131A`
  - Azul Eléctrico: `#1890FF`
  - Verde Menta Neón: `#00E5A3`

## Estructura del Repositorio

- [`android/app/src/main/AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml): Configuración de `ForegroundService`, `WAKE_LOCK`, icono `@drawable/cps_logo` y `extractNativeLibs="true"`.
- [`android/app/src/main/java/com/cajitapaas/ui/MainActivity.kt`](android/app/src/main/java/com/cajitapaas/ui/MainActivity.kt): Interfaz nativa minimalista con el logo oficial de **CPS**, paleta oscura `#0D131A` e indicador de arquitectura detectada.
- [`android/app/src/main/res/drawable/ic_cps_notification.xml`](android/app/src/main/res/drawable/ic_cps_notification.xml): Vector Drawable isométrico inspirado en la caja/cubo del logo **CPS** para la barra de estado de Android.
- [`android/app/src/main/java/com/cajitapaas/core/RootDetector.kt`](android/app/src/main/java/com/cajitapaas/core/RootDetector.kt): Detección de binarios `su`, validación de `UID 0` y soporte de `cgroups` en el kernel.
- [`android/app/src/main/java/com/cajitapaas/core/PaaSRuntimeService.kt`](android/app/src/main/java/com/cajitapaas/core/PaaSRuntimeService.kt): `ForegroundService` con `PARTIAL_WAKE_LOCK` y `WIFI_MODE_FULL_LOW_LATENCY`.
- [`scripts/boot_nonroot_proot.sh`](scripts/boot_nonroot_proot.sh): Inicialización Modo 1 (PRoot + Debian ARM64 + MariaDB + PM2 + Caddy + Cloudflared).
- [`scripts/boot_root_native.sh`](scripts/boot_root_native.sh): Inicialización Modo 2 (Montaje de `cgroups`, IP forwarding, `dockerd` estático + límites de CPU/RAM).
- [`control-plane/public/index.html`](control-plane/public/index.html): Panel Web **CPS** con el logo integrado y consola de despliegue Blue/Green (Git y `.zip`).
- [`control-plane/server.js`](control-plane/server.js): API en Node.js con `helmet`, `express-rate-limit`, autenticación JWT/API Key en tiempo constante, mitigación Zip-Slip y motor Blue/Green.
