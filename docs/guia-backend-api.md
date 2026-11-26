# ⚙️ Guía de Despliegue: Backend / API (Node.js, .NET 10 y Go ARM64)

> [⬅️ Volver al README principal](../README.md) · [🖥️ Guía Frontend](./guia-frontend.md) · [🚀 Guía Sistema Completo (Fullstack)](./guia-sistema-completo.md)

Usa el rol **⚙️ Backend / API** cuando tu aplicación escucha tráfico HTTP en un puerto de servidor para responder JSON, archivos, webhooks o endpoints REST en **Node.js**, **.NET / ASP.NET Core** o **Go (`linux/arm64`)**.

---

## 1. Particularidades por Runtime

### 🟢 A. Node.js v20 (Alpine ARM64)

- **Qué comprimir**: tu `package.json`, archivo de arranque (`server.js`, `index.js`, `app.js`, `main.js` o el definido en `scripts.start` / `main`), rutas, controladores y tu `.env` (opcional).
- **⚠️ REGLA DE ORO — Nunca subas `node_modules/` en el `.zip`**:
  - Los módulos con binarios nativos compilados en Windows/Mac (`x86_64`) no funcionan en Android **ARM64**.
  - CajitaPaaS ya trae preinstalados 18 paquetes clave (`mariadb`, `mysql2`, `mysql`, `express`, `cors`, `dotenv`, `body-parser`, `cookie-parser`, `morgan`, `helmet`, `jsonwebtoken`, `bcryptjs` + alias `bcrypt` puro JS, `uuid`, `axios`, `multer`, `express-validator`, `compression`, `http-errors`).
  - Si `package.json` pide librerías adicionales (`zod`, `socket.io`, `dayjs`, etc.), el panel avisa en **📦 Dependencias** y permite descargarlas desde `registry.npmjs.org` hacia el **Almacén Compartido (`cps_shared_node_modules`)** sin duplicar espacio entre proyectos.
- **Puertos y RAM**: escucha en `process.env.PORT` (`0.0.0.0`); CajitaPaaS le inyecta el puerto interno (`<puerto_app + 10000>`), aplica `--max-old-space-size=<maxRamMb>` y `UV_THREADPOOL_SIZE=<workerThreads>`.

---

### 🟣 B. .NET / ASP.NET Core (6, 7, 8, 9 y 10)

- **Cómo compilar antes de comprimir**:
  ```bash
  dotnet publish -c Release -o ./publish
  ```
- **Qué comprimir**: el contenido **compilado** dentro de `./publish/` (`TuApp.dll`, `TuApp.runtimeconfig.json`, `TuApp.deps.json`, `appsettings.json` y demás `.dll` de dependencias). **No subas solo `.csproj` sin publicar**.
- **Compatibilidad y variables automáticas**:
  - Corre sobre el runtime oficial **ASP.NET Core 10.0 (`linux-musl-arm64`)** con `DOTNET_ROLL_FORWARD=Major` (compatible con `.NET 6/7/8/9/10`).
  - Ejecutado por el launcher nativo `libcps_dotnet_host.so` + `libhostfxr.so` con manejador `SIGSYS -> -ENOSYS` para seccomp en Android.
  - Inyecta `ASPNETCORE_URLS=http://127.0.0.1:<puerto_interno>`, `ASPNETCORE_ENVIRONMENT=Production`, `DOTNET_GCHeapHardLimit`, `DOTNET_PROCESSOR_COUNT`, `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` y `ConnectionStrings__DefaultConnection` apuntando a MariaDB local (`127.0.0.1:3306`).
- **Configuración en caliente**: el botón **⚙️ Variables .env** detecta automáticamente `appsettings.json` / `appsettings.Production.json` (con soporte completo de comentarios **JSONC** `//`, `/* ... */` y comas finales).

---

### 🔵 C. Go Nativo (`linux/arm64` Estático)

- **Cómo compilar en tu PC** _(el celular es ARM64 Linux — nunca subas `.exe` de Windows ni binarios `linux/amd64`)_:
  ```powershell
  # Windows PowerShell
  $env:GOOS="linux"; $env:GOARCH="arm64"; $env:CGO_ENABLED="0"
  go build -tags "purego,nodynamic" -trimpath -ldflags="-s -w" -o mi-api ./cmd/server
  ```
  ```bash
  # Linux / macOS
  GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -tags "purego,nodynamic" -trimpath -ldflags="-s -w" -o mi-api ./cmd/server
  ```
- **⚠️ Detalle clave sobre `-tags "purego,nodynamic"`**:
  - Si tu proyecto Go usa dependencias como `gen2brain/webp` / `ebitengine/purego`, compilar sin `nodynamic` genera una dependencia al linker dinámico `/lib/ld-linux-aarch64.so.1` que **no existe en Android**. Con `CGO_ENABLED=0` y `-tags "purego,nodynamic"` se genera un binario ELF64 ARM64 100% estático sin `PT_INTERP`.
  - Si subes código fuente `.go`/`go.mod` o binarios `amd64`/`.exe`, CajitaPaaS no arrancará un estado falso: mostrará en la **📜 Consola en Vivo** la arquitectura detectada y el comando exacto de compilación.
- **Qué comprimir**: el binario `linux/arm64` compilado junto con tu archivo `.env` o `.env.production` (si tu proyecto usa uno).
- **Adaptaciones automáticas de CajitaPaaS para Go**:
  - Inyecta **`PORT`**, **`APP_PORT`**, **`SERVER_PORT`** y **`HTTP_PORT`** apuntando al puerto interno del proxy (`<puerto_app + 10000>`).
  - Configura **`GOMEMLIMIT=<maxRamMb>MiB`**, **`GOMAXPROCS`** (si limitas núcleos en **📊 Recursos**) y prioridad **`nice`**.
  - Si tu `.env` / `.env.production` trae rutas de servidor VPS Linux no escribibles en Android (`/var/lib/...`, `/opt/...`, `/srv/...`) en claves como `STORAGE_LOCAL_PATH`, `DB_SQLITE_PATH`, `DATA_DIR`, `UPLOAD_DIR`, `SQLITE_PATH` o `LOG_DIR`, las redirige automáticamente a la carpeta escribible `./data/` dentro del directorio de tu app.
  - Carga el bundle CA del sistema Android (`SSL_CERT_FILE`) y parchea en memoria el fallback DNS `127.0.0.1:53` → `1.1.1.1:0053` cuando falta `/etc/resolv.conf`.

---

## 2. ⚠️ Muy Importante: Puerto Directo vs Gateway `:8080` / Cloudflare Tunnel

Uno de los errores más frecuentes al probar una API detrás de Cloudflare es que **funcione por IP + puerto directo**, pero **falle al abrir imágenes, Swagger o rutas especiales desde Cloudflare**. Esto ocurre por cómo enruta cada puerto:

| Forma de acceso                                                                       | Qué atiende                                                                  | Cómo enruta las peticiones                                                                                   |
| :------------------------------------------------------------------------------------ | :--------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------- |
| **Puerto directo en Wi-Fi**<br/>`http://<IP_CELULAR>:3003/...`                        | Servidor dedicado solo para esa app (`handleDedicatedAppClient`)             | Envía **cualquier ruta** (`/api/...`, `/media/...`, `/docs/...`, `/swagger/...`) directo a tu backend.       |
| **Gateway LAN (`:8080`) o Túnel Cloudflare**<br/>`https://xxxx.trycloudflare.com/...` | Proxy unificado multi-app (`handleGatewayClient` → `findMatchingAppForPath`) | Solo envía a tu API las rutas que coincidan con la **Ruta Web Pública** configurada en la tarjeta de la app. |

### Cómo exponer rutas extra como `/media/*` o Swagger `/docs/*` por Cloudflare

Si dejaste la ruta por defecto **`/api/*`**, las peticiones a `/media/productos/123/hd.webp` o `/docs/` **no empiezan por `/api/`**, así que el Gateway `:8080` las mandará al frontend en `/` (o devolverá `404`).

- **Solución 1 — Múltiples rutas separadas por coma (si convive con un Frontend/Fullstack en `/`)**:
  En el selector **Ruta Web Pública** al subir la app (o en caliente con el botón **🛣️ Ruta** de su tarjeta), elige el preset correspondiente (como `/api/*,/media/*,/docs/*`) o selecciona **✏️ Otro** para escribir todos los prefijos que atiende tu API:
  ```text
  /api/v1/imagenes/*,/api/v1/backup/*,/media/*,/docs/*
  ```
  El router prioriza siempre los prefijos más específicos antes que `/`.
- **Solución 2 — Ruta raíz `/`**:
  Si esa API es el único servicio en el celular (sin otro frontend en `/`), puedes poner `/` como ruta web pública y responderá todas las rutas por Cloudflare.

### Enlaces públicos generados por la API (`PUBLIC_BASE_URL` / `MEDIA_PUBLIC_BASE_URL`)

Si tu API devuelve URLs completas en JSON (por ejemplo links de imágenes descargables), verifica en **⚙️ Variables .env**:

- No dejes valores de ejemplo (`https://media.tudominio.com`) ni `http://192.168...` si los clientes consumirán el servicio por Internet: usa la URL HTTPS de tu túnel Cloudflare (`https://xxxx.trycloudflare.com` o tu dominio fijo Zero Trust).

---

## 3. Reglas sobre rutas de salida, errores HTTP y estado (`/health`)

- **Swagger / raíz `/`**: muchas APIs (como microservicios Go/ASP.NET Web API) no definen una ruta `GET /`; por eso el botón **🔌 Abrir API (:3003)** puede mostrar `404` en la raíz hasta que entres al endpoint real (ej. `/docs/`, `/swagger/`, `/health`).
- **Errores reales**: CajitaPaaS respeta los códigos HTTP `404` y `500` reales de tu backend (si una petición falla con `404`, primero reintenta quitando/agregando `/api` por compatibilidad, y si tampoco existe, reenvía el `404` original de tu API).
- **Si el proceso está detenido o falló al arrancar**: el endpoint `/health` o `/api/status` devolverá HTTP **`503 Service Unavailable`** con `"healthy": false` y el último log de consola.

---

## 4. Base de datos, Logs y Recursos

- **Base de Datos MariaDB**: asigna la base al subir el ZIP, en `.env`/`appsettings.json`, o cámbiala en caliente con **🗄️ Cambiar BD**.
- **Consola y Watchdog**: revisa errores y reinicios automáticos en **📜 Consola** (hasta 300 líneas en vivo, con botón **📋 Copiar** y **🔄 Reiniciar App**). Si un binario Go o .NET sale con error fatal de arquitectura/empaquetado (código `126`), el Watchdog se detiene sin entrar en bucle infinito.
- **Límites**: ajusta RAM máxima (`--max-old-space-size` / `DOTNET_GCHeapHardLimit` / `GOMEMLIMIT`), núcleos CPU (`DOTNET_PROCESSOR_COUNT` / `GOMAXPROCS`), prioridad `nice` y pausa/reanudación en **📊 Recursos**.
