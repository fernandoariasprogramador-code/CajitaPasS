<p align="center">
  <img src="./assets/branding/cajitapaas-logo.jpg" alt="CPS - CajitaPaaS Logo" width="420" />
</p>

<h1 align="center">📦 CPS (CajitaPaaS)</h1>
<p align="center">
  <b>Convierte cualquier teléfono Android ARM64 en tu propio servidor PaaS de Internet gratis, autónomo y sin complicaciones.</b>
</p>

<p align="center">
  <a href="https://github.com/fernandoariasprogramador-code/CajitaPasS/releases/latest/download/CajitaPaaS-CPS-ARM64.apk">
    <img src="https://img.shields.io/badge/📲_DESCARGAR_APK_DIRECTO-CajitaPaaS_Android-00E5A3?style=for-the-badge&logo=android&logoColor=0D131A" alt="Descargar APK Directo" />
  </a>
  &nbsp;
  <a href="https://github.com/fernandoariasprogramador-code/CajitaPasS/releases">
    <img src="https://img.shields.io/github/v/release/fernandoariasprogramador-code/CajitaPasS?style=for-the-badge&color=7928CA&label=Versi%C3%B3n" alt="Versión Release" />
  </a>
  &nbsp;
  <a href="https://github.com/fernandoariasprogramador-code/CajitaPasS/actions/workflows/build-apk.yml">
    <img src="https://img.shields.io/badge/⚙️_Compilaciones-GitHub_Actions-1890FF?style=for-the-badge&logo=githubactions&logoColor=white" alt="GitHub Actions APK" />
  </a>
  &nbsp;
  <a href="./LICENSE">
    <img src="https://img.shields.io/badge/Licencia-Personal_y_No_Comercial-FF9900?style=for-the-badge&logo=shield" alt="Licencia Personal" />
  </a>
</p>

---

## 🤔 ¿Qué es esto? (Explicado fácil y sin palabras raras)

Imagina que creaste una **página web**, una **app con backend**, un **sistema MVC/Razor en .NET** o una **base de datos** para tu negocio o proyecto de estudio. Normalmente, para que otras personas puedan entrar desde cualquier lugar del mundo por Internet, tendrías que **alquilar un servidor en la nube** (AWS, Google Cloud, DigitalOcean, Heroku) y pagar todos los meses en dólares.

**¿Y si pudieras usar ese celular Android que tienes guardado en un cajón como si fuera un servidor profesional en la nube?** 📱⚡

Eso es **CPS (`CajitaPaaS`)**: una plataforma PaaS (Platform as a Service) nativa para Android que instalas como APK y que arma automáticamente una infraestructura completa dentro del dispositivo:

8. 🌐 **Publicar tus proyectos en Internet al instante**: incluye `cloudflared` nativo que entrega un link público HTTPS seguro (`https://xxxx.trycloudflare.com`) sin tocar el router ni abrir puertos, o te permite conectar tu **propio dominio fijo** con token de Cloudflare Zero Trust (`HTTP/2` o `QUIC`).
9. ⚡ **Multi-Runtime Nativo en ARM64 (Sin Root ni PRoot pesado)**:
   - **Node.js v20 (Alpine ARM64)**: ejecuta APIs Backend directamente con 18 módulos populares preinstalados (`express`, `mariadb`, `mysql2`, `cors`, `dotenv`, `bcryptjs`, etc.), almacén compartido de paquetes e instalador NPM bajo demanda.
   - **.NET 10.0 + ASP.NET Core 10 (Alpine ARM64)**: ejecuta Web APIs, aplicaciones MVC, Razor Pages y sistemas Fullstack en `.NET 6`, `.NET 7`, `.NET 8`, `.NET 9` y `.NET 10` mediante _roll-forward_ automático (`DOTNET_ROLL_FORWARD=Major`) y un launcher ELF nativo con soporte seccomp para Android.
   - **Go Nativo ARM64 (`linux/arm64` `CGO_ENABLED=0`)**: ejecuta binarios estáticos de Go en Android 10–15 sin Root mediante cargador ELF en memoria (`libcps_elf_host.so` compatible con SELinux W^X), inyectando `PORT`/`APP_PORT`, `GOMEMLIMIT`, `GOMAXPROCS`, TLS CA bundle y DNS para Android.
   - **Frontends Estáticos y SPAs**: HTML/CSS/JS, React, Vue, Vite o Angular servidos con enrutamiento SPA y puente automático anti-CORS.
10. 🔄 **Actualizar sin caídas (Blue/Green) y Multi-Servicio**: despliega por separado un **Frontend**, un **Backend / API** o un **Sistema Completo (Fullstack)** en puertos dedicados (`:3001`, `:3003`, `:3005`, ...) unificados bajo el Gateway HTTP (`:8080`, y `:80` automático en teléfonos con Root).
11. 🗄️ **Base de Datos MariaDB 10.11 Nativa con InnoDB**: un motor relacional real (no SQLite recortado) con transacciones ACID, múltiples bases dinámicas, **Modo PC (Puente DBeaver/MySQL Workbench)** con control de tiempo e IP en `:3306` / `:13306`, **Exportación e Importación `.SQL`** y **Consultor SQL rápido en el navegador**.
12. 📊 **Monitor y Gestor de Recursos en Vivo**: consulta cada 2 segundos el consumo real de RAM, CPU, hilos y disco (del celular, de MariaDB y de cada aplicación) y permite **agregar o quitar RAM, limitar núcleos de CPU, prioridad Linux (`nice`), worker threads o pausar/reanudar apps**.
13. 🔋 **Funciona 24/7 con Pantalla Apagada**: utiliza `ForegroundService`, exención de optimización de batería (Doze), `PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY` y pulso _keep-alive_ cada 5 segundos.
14. 📡 **Supervivencia de Red Wi-Fi (Opcional en Modo Root)**: si el router Wi-Fi se apaga, el celular puede levantar automáticamente una **Zona Wi-Fi (Hotspot)** de emergencia y sondear periódicamente el retorno del router.

---

## 📋 Requisitos del Teléfono

- **Sistema Operativo**: Android **9.0 o superior** (`minSdk = 28`).
- **Arquitectura de CPU**: **ARM64 (`arm64-v8a`)**.
- **Root (`su`)**: **No obligatorio** — toda la plataforma (Panel Web, Gateway, MariaDB, Node.js, .NET 10 y Túnel Cloudflare) funciona sin Root. En dispositivos rooteados (Magisk, KernelSU, APatch) habilita funciones extra de red y energía.

---

## 📲 Descargar e Instalar la APK en tu Celular

Tienes **dos opciones** para descargar el instalador en tu teléfono Android ARM64:

### Opción 1: Descarga Directa (Recomendada) ⚡

👉 **[Haz clic aquí para descargar `CajitaPaaS-CPS-ARM64.apk`](https://github.com/fernandoariasprogramador-code/CajitaPasS/releases/latest/download/CajitaPaaS-CPS-ARM64.apk)** _(o entra a la sección de [Releases de GitHub](https://github.com/fernandoariasprogramador-code/CajitaPasS/releases))_.

### Opción 2: Descargar desde GitHub Actions 🛠️

1. Entra a **[GitHub Actions — Build CPS Android APK](https://github.com/fernandoariasprogramador-code/CajitaPasS/actions/workflows/build-apk.yml)**.
2. Toca la compilación más reciente con check verde ✅.
3. Baja hasta la sección **Artifacts** y descarga **`CajitaPaaS-CPS-ARM64-APK`**.

---

## 🚀 Guía Rápida de Uso

1. **Instala la APK** en tu teléfono Android (permite la instalación desde fuentes desconocidas si el sistema lo solicita).
6. **Abre la app `CPS`**: acepta la exención de optimización de batería y el permiso de notificaciones para que el servidor corra estable en segundo plano; dentro de la app se abrirá el Panel Web integrado (`127.0.0.1:4000`).
7. **Crea tu contraseña maestra de administrador**: en el primer ingreso, define la contraseña que protegerá el Panel de Control (incluye selector visual para mostrar/ocultar contraseña).
8. **Accede desde tu PC o navegador externo**: entra a `http://<IP_DE_TU_CELULAR>:4000` (el panel te muestra tu IP local y también expone la URL remota bajo `/_control/` si activas el túnel público).
9. **Despliega y administra**: sube tus proyectos `.zip` y gestiona apps, variables de entorno, dependencias, bases de datos, túnel y recursos desde las 4 pestañas del panel.

---


## 📦 Cómo Empaquetar y Desplegar tus Proyectos

En la pestaña **🚀 1. Subir o Actualizar mis Apps** puedes desplegar tantos servicios como necesites eligiendo su identificador, rol, ruta pública en el Gateway (`/` o `/api/*`, `/crm/*`, etc.) y base de datos asociada:

- 🖥️ **Frontend**: sitios HTML/CSS/JS o compilaciones React, Vue, Vite o Angular (`dist/` o `build/`).
- ⚙️ **Backend / API**: APIs REST o microservicios en **Node.js** o **.NET / ASP.NET Core**.
- 🚀 **Sistema Completo (Fullstack)**: aplicaciones que sirven vistas/estáticos y lógica de servidor en un mismo proyecto (por ejemplo, **ASP.NET Core MVC / Razor** o **Node.js + `public/`**).

> [!NOTE]
> El formulario **Subir App o Sistema en `.ZIP`** acepta paquetes de hasta **100 MB** y detecta automáticamente subcarpetas raíz únicas (por ejemplo si comprimiste la carpeta en lugar de solo su contenido). En la **APK Android embebida**, el código real de producción se sube mediante **`.ZIP`** (la tarjeta de GitHub está orientada al Control Plane Node.js PC/Termux con `simple-git`; en Android actúa solo como registro base si no hay archivos cargados).

### 1. Desplegar un Frontend (HTML, React, Vue, Vite, Angular)

- **Qué comprimir**: genera tu build de producción (`npm run build` o `pnpm build`) y comprime los archivos dentro de `dist/` o `build/` (donde reside `index.html`).
- **Cómo subir**: elige rol **Frontend**, nombre (ej. `tienda-front`), ruta `/` y sube el `.zip`.
- **Resultado**: tu frontend queda disponible en `http://<IP_CELULAR>:8080/` (o `http://<IP_CELULAR>/` si tienes Root) y en la raíz `/` del túnel HTTPS. Además incluye respaldo automático SPA (`index.html`) para rutas de React Router / Vue Router.

---

### 2. Desplegar un Backend API (Node.js)

- **Qué comprimir**: comprime tus archivos fuente (`server.js`, `index.js`, `app.js` o el definido en `package.json` → `scripts.start` / `main`), rutas, controladores y tu `.env` (si lo tienes).

> [!WARNING]
>
>
> ### ⚠️ REGLA DE ORO: ¡NUNCA INCLUYAS `node_modules` EN EL `.ZIP`!
>
> 4. **Incompatibilidad de arquitectura**: los binarios nativos compilados en Windows/Mac (`x86_64`) fallarán en el procesador **ARM64** de Android.
> 5. **Peso innecesario**: evita subir cientos de megabytes extra.
> 6. **Módulos integrados y almacén compartido**: CajitaPaaS inyecta su preloader (`cps_node_preload.cjs`) para compartir dependencias globales sin duplicar espacio.

#### 📚 Módulos Node.js incluidos de fábrica en CajitaPaaS (`build_mariadb_alpine_arm64.sh`)

| Categoría                      | Módulos Preinstalados en el Runtime                                                   |
| :----------------------------- | :------------------------------------------------------------------------------------ |
| **Bases de Datos**             | `mariadb`, `mysql2`, `mysql`                                                          |
| **Framework & Middleware Web** | `express`, `cors`, `body-parser`, `cookie-parser`, `morgan`, `helmet`, `compression`  |
| **Validación & HTTP**          | `express-validator`, `http-errors`, `axios`, `multer`, `dotenv`                       |
| **Autenticación & Utilidades** | `jsonwebtoken`, `bcryptjs`, alias `bcrypt` _(apunta a `bcryptjs` en JS puro)_, `uuid` |


#### ➕ ¿Y si tu proyecto usa otras librerías de NPM (`zod`, `socket.io`, `dayjs`, etc.)?


1. Al subir tu `.zip`, el servidor inspecciona `package.json` y detecta qué dependencias faltan respecto a los módulos integrados y al **Almacén Compartido (`cps_shared_node_modules`)**.
2. En el modal **📦 Dependencias**, puedes tocar **"✅ Autorizar e Instalar"** o **instalar paquetes adicionales por nombre** uno a uno directamente desde `registry.npmjs.org`.
3. Al finalizar, tu aplicación se reinicia automáticamente con los nuevos módulos disponibles.

---


### 3. Desplegar un Proyecto .NET / ASP.NET Core (6, 7, 8, 9 o 10)

CajitaPaaS integra el runtime oficial **ASP.NET Core 10.0 (`linux-musl-arm64`)** junto con librerías nativas de Alpine (`icu-libs`, `icu-data-full`, `openssl`, `zlib`, `krb5-libs`, `libstdc++`, `libgcc`). Gracias a `DOTNET_ROLL_FORWARD=Major`, ejecuta aplicaciones compiladas para **.NET 6, 7, 8, 9 y 10**.

1. En tu PC, dentro de la carpeta del `.csproj`, publica en modo _framework-dependent_:
   ```bash
   dotnet publish -c Release -o ./publish
   ```
1. Comprime el contenido de `./publish` (`TuApp.dll`, `TuApp.runtimeconfig.json`, `TuApp.deps.json`, `appsettings.json` y `wwwroot/` si aplica) en un `.zip`.
1. En el Panel Web, elige:
   - **🚀 Sistema Completo (Fullstack)** en ruta `/` (para Razor Pages, MVC o Web API + `wwwroot/`).
   - **⚙️ Backend / API** en ruta `/api/*` (para Web APIs REST).
1. **Variables inyectadas automáticamente en .NET**:
   - `ASPNETCORE_URLS` / `URLS`: apuntando al puerto interno de la instancia (`http://127.0.0.1:<puerto_interno>`).
   - `ASPNETCORE_ENVIRONMENT`: `Production` (sobrescribible vía configuración).
   - `DOTNET_ROLL_FORWARD=Major`, `DOTNET_EnableWriteXorExecute=0`, `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` y `DOTNET_GCServer=0` (ajustados para seguridad W^X y baja memoria en Android).
   - `DOTNET_GCHeapHardLimit` y `DOTNET_PROCESSOR_COUNT`: controlados desde el gestor de recursos / Modo Experto.
   - `ConnectionStrings__DefaultConnection`: `"Server=127.0.0.1;Port=3306;Database=<tu_bd>;Uid=cajita_admin;Pwd=<clave>;"` (listo para **Pomelo.EntityFrameworkCore.MySql** o **MySqlConnector**), además de `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` y `DB_NAME`.

---

### 4. Desplegar un Proyecto Go (`linux/arm64` Estático)

CajitaPaaS ejecuta directamente binarios **Go compilados de forma estática para `linux/arm64`** (`CGO_ENABLED=0`) sin Root ni emuladores, cargando sus segmentos ELF en memoria mediante `libcps_elf_host.so` (respetando SELinux W^X en Android 10–15).

1. En tu PC, compila tu servicio Go para **ARM64 estático** (importante: **no** subas `.exe` de Windows ni binarios `linux/amd64`):
   ```bash
   # Linux / macOS
   GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -tags "purego,nodynamic" -trimpath -ldflags="-s -w" -o mi-servicio ./cmd/server
   ```
   ```powershell
   # PowerShell (Windows)
   $env:GOOS="linux"; $env:GOARCH="arm64"; $env:CGO_ENABLED="0"
   go build -tags "purego,nodynamic" -trimpath -ldflags="-s -w" -o mi-servicio ./cmd/server
   ```
   > **Nota sobre `-tags "purego,nodynamic"`**: si tu proyecto usa librerías como `gen2brain/webp` / `ebitengine/purego`, agregar la etiqueta `nodynamic` evita que el binario requiera `/lib/ld-linux-aarch64.so.1`.
2. Comprime el binario ARM64 (junto con tu `.env` o `.env.production` si usas uno) en un `.zip` y súbelo como **⚙️ Backend / API** o **🚀 Sistema Completo (Fullstack)** (también puedes separar múltiples prefijos de ruta con coma, ej. `/api/v1/imagenes/*,/media/*`).
3. **Adaptaciones automáticas para Go en Android**:
   - **Puertos**: inyecta `PORT`, `APP_PORT`, `SERVER_PORT` y `HTTP_PORT` apuntando al puerto interno del proxy (`<puerto_app + 10000>`).
   - **Límites de RAM y CPU**: configura `GOMEMLIMIT=<maxRamMb>MiB`, `GOMAXPROCS` (si se limita núcleos desde **📊 Recursos**) y prioridad Linux `nice`.
   - **Almacenamiento seguro**: si tu `.env`/`.env.production` apunta `STORAGE_LOCAL_PATH`, `DB_SQLITE_PATH`, `DATA_DIR` o `UPLOAD_DIR` a rutas de VPS Linux no escribibles en Android (como `/var/lib/...` u `/opt/...`), CPS las redirige automáticamente a la carpeta interna escribible `./data/` del proyecto.
   - **TLS y DNS**: inyecta `SSL_CERT_FILE` con el bundle CA de Android y parchea en memoria el fallback DNS `127.0.0.1:53` hacia `1.1.1.1:0053` cuando no existe `/etc/resolv.conf`.

---

## 🎛️ Panel de Control Web: Pestañas y Herramientas en Caliente

El panel administrativo está dividido en **4 pestañas principales** y **modales interactivos** sin necesidad de recompilar ni resubir el `.zip` ante cambios de configuración:

### 1️⃣ Pestaña **🚀 1. Subir o Actualizar mis Apps**

Cada aplicación activa en **🟢 Tus Apps Encendidas** cuenta con controles directos:

- **🌐 Abrir Web / 🔌 Abrir API**: abre la URL principal del servicio según su rol.
- **📜 Consola en Vivo**: terminal en tiempo real (Node.js, .NET 10, mensajes `[CPS-Watchdog]` y `[CPS-Seccomp]`) con barra de estado, contador de reinicios, **🔄 Reiniciar App**, **🧹 Limpiar** y **📋 Copiar** (con copia al portapapeles compatible tanto en HTTPS como en HTTP/WebView).
- **⚙️ Variables `.env` / `appsettings.json`**:
  - Detecta automáticamente si el proyecto usa `.env` (Node.js) o **`appsettings.json` / `appsettings.Production.json` (.NET)**, **soportando formato JSONC** (comentarios `//`, `/* ... */` y comas finales generadas por Visual Studio).
  - Ofrece **Vista Clave = Valor** (con botón **⚡ Cargar Datos de MariaDB Local** y alta de nuevas claves) o **Modo Texto Crudo** (`.env` o JSON). Al guardar, valida sintaxis y reinicia el servicio al instante.
- **📦 Dependencias**: auditoría e instalación bajo demanda de librerías NPM (con insignia de aviso si faltan paquetes tras subir un ZIP).
- **🗄️ Cambiar BD**: cambia en caliente la base de datos de MariaDB a la que se conecta la app y actualiza automáticamente su configuración.
- **📊 Recursos**: abre el modal de consumo y límites individuales de la app (disponible para frontend, backend y fullstack).
- **✏️ Actualizar**: precarga nombre, rol, ruta y base de datos en el formulario superior para desplegar una nueva versión Blue/Green (conservando sus límites de CPU/RAM/Disco previos).
- **🛑 Apagar**: detiene y elimina el proceso de forma limpia.
- **🧠 Modo Experto (Avanzado)**: interruptor opcional al pie de la pestaña para configurar de golpe la **RAM por defecto y por app (`128 MB–1024 MB`)**, el **Buffer Pool de MariaDB InnoDB (`16–128 MB`)**, el **Watchdog de auto-reinicio estilo PM2 (`3/5/10` intentos con retroceso exponencial)** y ver cuántos paquetes hay en el almacén compartido.

---

### 2️⃣ Pestaña **🗄️ 2. Mis Bases de Datos y Modo PC**

- **💻 Puente DBeaver / MySQL Workbench (Modo PC)**:
  - Por defecto protege el puerto externo y permite activarlo temporalmente con dos reglas de seguridad configurables:
    - **Duración**: _Mientras esta pestaña web esté abierta_ (cierre automático por heartbeat) o _TTL automático_ de **15 min**, **30 min** o **1 hora**.
    - **Control de acceso IP**: permitir _cualquier PC de tu red Wi-Fi_ o restringir _únicamente a la IP de tu PC actual_.
  - Expone los puertos TCP **`3306`** (y **`13306`** alternativo) haciendo puente binario transparente hacia el demonio real `mariadbd` (`127.0.0.1:13307`), mostrando contador de **conexiones abiertas en vivo**.
- **➕ Crear Nueva Base de Datos** y **📂 Tus Bases Guardadas**: crea bases y usuarios dedicados desde la web, inspecciona tablas creadas y ejecuta acciones rápidas.
- **📥 Descargar Backup (`.SQL`)** y **📤 Importar Archivo (`.SQL`)**: exporta dumps completos (`CREATE TABLE` + `INSERT INTO`) o restaura archivos `.sql` desde cualquier navegador.
- **🔍 Consultor SQL Rápido**: editor para ejecutar consultas SQL directamente desde la pestaña y visualizar los resultados en tabla HTML.

#### Credenciales de Conexión MariaDB (Modo PC / Apps)

| Parámetro                 | Valor por Defecto / Conexión                                  |
| :------------------------ | :------------------------------------------------------------ |
| **Host / Servidor**       | IP Wi-Fi de tu celular (ej: `192.168.1.145`) o `127.0.0.1`    |
| **Puerto**                | `3306` _(o `13306`)_                                          |
| **Usuario**               | `cajita_admin` _(o el usuario específico creado en el panel)_ |
| **Contraseña**            | `cajitapaas2026`                                              |
| **Base de Datos inicial** | `cajitapaas` _(o la asignada por `.env` / panel)_             |

#### Tres formas de asignar la base de datos a tu Backend:

1. En el campo **Base de Datos asociada** al subir el `.zip`.
2. Definiendo `DB_NAME=...` en `.env` o `ConnectionStrings:DefaultConnection` en `appsettings.json`.
3. En caliente desde la tarjeta de la app con el botón **🗄️ Cambiar BD**.

#### Ejemplo mínimo de Backend API Node.js (`server.js`):

```javascript
const express = require("express");
const mariadb = require("mariadb");
const cors = require("cors");

const app = express();
app.use(cors());
app.use(express.json());

// Variables inyectadas automáticamente por CajitaPaaS:
const pool = mariadb.createPool({
  host: process.env.DB_HOST || "127.0.0.1",
  port: Number(process.env.DB_PORT) || 3306,
  user: process.env.DB_USER || "cajita_admin",
  password: process.env.DB_PASSWORD || "cajitapaas2026",
  database: process.env.DB_NAME || "cajitapaas",
  connectionLimit: 5,
});

app.get("/api/status", (req, res) => {
  res.json({
    status: "online",
    app: process.env.CPS_APP_NAME || "mi-api",
    db: process.env.DB_NAME || "cajitapaas",
    timestamp: new Date().toISOString(),
  });
});

app.get("/api/tareas", async (req, res) => {
  let conn;
  try {
    conn = await pool.getConnection();
    const rows = await conn.query("SELECT * FROM tareas");
    res.json(rows);
  } catch (err) {
    res
      .status(500)
      .json({ error: "Error en base de datos", detalle: err.message });
  } finally {
    if (conn) conn.release();
  }
});

const PORT = process.env.PORT || 3001;
app.listen(PORT, "0.0.0.0", () => {
  console.log(`Backend API escuchando en el puerto ${PORT}`);
});
```

---

### 3️⃣ Pestaña **🌐 3. Red, Túnel y Supervivencia Wi-Fi**

#### 🌐 Túnel Cloudflare (`cloudflared` nativo)

Permite sortear CGNAT de redes móviles o Wi-Fi sin abrir puertos del router:


- **Interruptor Túnel Público / Solo Red Local**: apaga la exposición pública en 1 clic si prefieres trabajar solo dentro de tu LAN.
- **⚡ Opción 1 — Túnel Rápido Gratis (`*.trycloudflare.com`)**: genera automáticamente una URL HTTPS pública al iniciar (con botón para regenerar un nuevo enlace en 1 clic).
- **🔒 Opción 2 — Dominio Fijo con Cloudflare Zero Trust**:
  1. Delega los DNS de tu dominio propio a **Cloudflare** y crea un túnel en _Networks → Tunnels_.
  2. Configura el **Public Hostname** apuntando a `HTTP://127.0.0.1:8080` _(puedes copiarlo directo con el botón 📋 Copiar del panel)_.
  3. Pega tu dominio y Token (`eyJhIjoi...`), selecciona el protocolo (**`HTTP/2` recomendado en Android/móviles** o **`QUIC`**) y guarda.
- **🩺 Diagnóstico y Logs de Cloudflared en Vivo**: muestra el estado real de conexión (`Registered tunnel connection`), errores detallados si los hubiera y botón **📋 Copiar Logs**.


#### 📡 Supervivencia de Red Wi-Fi (Failover a Hotspot / Failback Root)


Pensado para teléfonos que funcionan como mini-servidores 24/7 en comercios u hogares:


- **Detección reactiva**: escucha cambios de conectividad Wi-Fi (`ConnectivityManager.NetworkCallback`) con ventana anti-rebote de 5 segundos.
- **Failover automático (vía Root)**: si el router se apaga o cae la señal Wi-Fi, el celular activa automáticamente su **Zona Wi-Fi (Hotspot)** de emergencia para mantener vivos los servicios en la red local.
- **Failback en Modo Ciego (Single-Radio)**: en dispositivos de una sola antena Wi-Fi (como Moto G8) que no pueden emitir Hotspot y buscar router a la vez, puedes configurar intervalos de sondeo (**5, 10, 15 o 30 minutos**): apaga el Hotspot durante una ventana de **30 segundos**, comprueba si volvió el router y se reconecta automáticamente.
- **Badge en tiempo real**: indica en vivo si la radio está en modo normal (`STA`), en modo emergencia (`SoftAP`) o sondeando router.


### 4️⃣ Pestaña **📊 4. Recursos (CPU / RAM / Disco) y Modal por App**


Tanto desde esta pestaña (actualizada en vivo cada 2 segundos) como desde el botón **📊 Recursos** de cualquier app, puedes inspeccionar y modificar el uso de hardware en tiempo real:

#### Telemetría en Vivo (Consumo Real vs. Libre)


| Ámbito              | Métricas Medibles en Tiempo Real                                                                                                                                       |
| :------------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **📱 Celular Host** | RAM total, RAM usada, RAM libre (`ActivityManager`), alerta `lowMemory`, RAM asignada en cuotas, disponible para asignar, CPU cores, almacenamiento (`StatFs`) y CPS.  |
| **🗄️ MariaDB**      | Estado del demonio `mariadbd`, PID, RAM RSS y pico (`VmHWM`), Buffer InnoDB, % CPU, hilos, espacio real de datos en disco y conexiones activas.                        |
| **📦 Cada App**     | Estado/uptime, PID, **RAM consumida vs. RAM libre dentro de su cuota**, pico histórico, **% de CPU e hilos (`/proc/<pid>`)** y espacio en disco (sin contar symlinks). |

#### Controles para Agregar o Quitar Recursos por App (`#modalAppResources`)

- **🧠 RAM Máxima (`maxRamMb`)**: slider y botones rápidos **`-64 MB` / `+64 MB`** (`64 MB` a `2048 MB`; aplicado a `--max-old-space-size` en Node.js y `DOTNET_GCHeapHardLimit` en .NET 10).
- **⚡ Límite de Núcleos CPU (`cpuCoresLimit`)**: limita `DOTNET_PROCESSOR_COUNT` desde `1` núcleo hasta todos los núcleos disponibles (`Auto`).
- **🔋 Prioridad CPU Linux (`cpuPriorityNice`)**: ajusta la prioridad del proceso mediante `/system/bin/nice -n <0..15>` (`Normal 0`, `Moderada 5`, `Baja 10`, `Mínima 15`).
- **🧵 Hilos Worker I/O (`workerThreads`)**: ajusta `UV_THREADPOOL_SIZE` en Node.js (`2`, `4`, `8` o `16` hilos).
- **💾 Cuota Máxima de Disco (`maxDiskMb`)**: cuota de referencia (`Sin tope fijo`, `100 MB`, `250 MB`, `500 MB`, `1024 MB`, `2048 MB`) para visualizar espacio libre en cuota.
- **⏸️ Pausar / ▶️ Reanudar Proceso**: detiene temporalmente una aplicación sin borrarla para liberar toda su RAM y CPU al instante, y permite reanudarla cuando quieras.

---

## 🔌 Puertos Expuestos y Conexión Frontend ↔ API

Cuando tus aplicaciones están encendidas, **CPS** organiza los puertos en tu red local Wi-Fi y en el Túnel Público de Cloudflare:

| Servicio / Rol                 | Puerto en Red Local (Wi-Fi)                                  | Ruta en Túnel Público (`https://...`)                 | Descripción                                                                                             |
| :----------------------------- | :----------------------------------------------------------  | :------------------------------------                 | :-----------------------------------------------------------------------------------------------        |
| **🎨 Frontend / Fullstack**    | **`:8080`** _(y **`:80`** automático en Android con Root)_   | `/` _(Raíz del enlace)_                               | Sirve tu interfaz web, SPA compilada o sistema Fullstack.                                               |
| **⚙️ Backend / API**           | **`:3001`**, **`:3003`**, etc. _(y proxy en `:8080/api/`)_   | `/api/*` _(o ruta configurada)_                       | Cada backend escucha en un puerto dedicado en la LAN y queda unificado bajo `/api/` en `:8080`.         |
| **🛠️ Panel de Control CPS**    | **`:4000`**                                                  | `/_control/`                                          | Panel administrativo protegido por contraseña maestra y token de sesión.                                |
| **🗄️ Base de Datos MariaDB**   | **`:3306`** y **`:13306`** _(puente hacia `:13307` interno)_ | _Solo Red Local (LAN)_                                | Acceso directo SQL para DBeaver/MySQL Workbench (cuando Modo PC está activo) y backends locales.        |

### 🔗 ¿Cómo conectar tu Frontend con tu Backend sin problemas de CORS?

1. **Rutas relativas `/api/...` (Recomendada ⭐)**: el Gateway interno (`:8080`) y Cloudflare Tunnel enrutan ambos servicios bajo el mismo origen, evitando errores de CORS y Mixed Content (`fetch("/api/tareas")`).
2. **Puente automático de `localhost` (Sin modificar código)**: al servir `index.html`, CajitaPaaS inyecta un interceptor inteligente que redirige llamadas compiladas contra `http://localhost:3001` o `127.0.0.1` hacia la IP real de la LAN o hacia `/api/` por HTTPS cuando se entra desde Internet.
3. **Variables globales en navegador**: expone `window.CPS_API_URL` (dirección directa del backend) y `window.CPS_GATEWAY_API_URL` (proxy unificado `/api`).

---

## 🛠️ Arquitectura Técnica y Funcionamiento Interno

Para ingenieros y desarrolladores que quieran entender o extender el funcionamiento a bajo nivel:

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                         DISPOSITIVO ANDROID (ARM64)                           │
│                                                                               │
│  ┌─────────────────────────────┐      ┌────────────────────────────────────┐  │
│  │      Cloudflare Tunnel      │      │        PaaSRuntimeService          │  │
│  │     (libcloudflared.so)     │      │  - Foreground Service (specialUse) │  │
│  │  - DNS 1.1.1.1 parcheado    │      │  - WakeLock CPU + WiFi LowLatency  │  │
│  │  - Bundle CA Android        │      │  - Root iptables :80 -> :8080      │  │
│  └──────────────┬──────────────┘      │  - NetworkFailoverManager (Wi-Fi)  │  │
│                 │ HTTPS Público       └─────────────────┬──────────────────┘  │
│                 ▼                                       ▼                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │         EmbeddedControlPlaneServer (Kotlin HTTP Nativo Multi-Hilo)      │  │
│  │   - Panel Web (:4000) · Auth & Env/JSONC · Watchdog & Dependencias NPM  │  │
│  │   - Gateway Reverse Proxy (:8080) · Enrutamiento SPA & Blue/Green       │  │
│  │   - Telemetría (/proc/<pid>, ActivityManager, StatFs) & Límites         │  │
│  └──────┬──────────────────────┬───────────────────────┬───────────────────┘  │
│         │ /api/*               │ / o /api/*            │ / o Estáticos        │
│         ▼                      ▼                       ▼                      │
│  ┌──────────────────┐  ┌──────────────────────┐  ┌─────────────────────────┐  │
│  │ Node.js v20      │  │ .NET 10 / ASP.NET    │  │ Go Nativo ARM64         │  │
│  │ (libnode.so)     │  │ (libcps_dotnet_host) │  │ (libcps_elf_host.so)    │  │
│  │ - --max-old-space│  │ - hostfxr_main_start │  │ - Userland ELF Loader   │  │
│  │ - UV_THREADPOOL  │  │ - SIGSYS -> -ENOSYS  │  │ - GOMEMLIMIT / DNS / CA │  │
│  └────────┬─────────┘  └──────────┬───────────┘  └────────────┬────────────┘  │
│           │ TCP :3306             │ TCP :3306                 │ TCP :3306     │
│           └───────────────────────┴───────────────────────────┘               │
│                                   ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │     EmbeddedDatabaseEngine: MariaDB 10.11 ARM64 (InnoDB Nativo)         │  │
│  │   - Ejecución ELF nativa vía libc musl (libalpine_musl.so)              │  │
│  │   - Demonio mariadbd en 127.0.0.1:13307 + Puente TCP :3306 / :13306     │  │
│  │   - Compatible con SELinux W^X (Android 9 - 15) · Multi-Database        │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **Ejecución ELF Nativa sin Root ni PRoot Pesado**: tanto MariaDB (`libmariadbd.so` / `libmariadb_cli.so`), Node.js (`libnode.so`), el host `.NET` (`libcps_dotnet_host.so`) como el cargador ELF para Go (`libcps_elf_host.so`) residen en `jniLibs/arm64-v8a` con `extractNativeLibs="true"` para cumplir las políticas SELinux `W^X` en Android 10–15.
- **Host Nativo .NET 10 (`cps_dotnet_host.c` → `libcps_dotnet_host.so`)**: en Android musl no permite ejecutar un binario si `/proc/self/exe` no se llama literalmente `dotnet` (`cannot execute dotnet when renamed to libalpine_musl.so`). Para resolverlo limpiamente y respetar `W^X`, un launcher C PIE carga `libhostfxr.so` con `dlopen`, invoca directamente `hostfxr_main_startupinfo` e instala un manejador de señal `SIGSYS` (`SYS_SECCOMP`) que responde `-ENOSYS` en llamadas restringidas por seccomp-bpf (`membarrier`, `clone3`, `rseq`, etc.) para que CoreCLR active su ruta segura de respaldo.
- **Cargador ELF en Memoria para Go (`cps_elf_host.c` → `libcps_elf_host.so`)**: dado que Android 10+ bloquea `execve` sobre binarios subidos a `filesDir`, este cargador C mapea en memoria anónima (`PROT_READ|PROT_WRITE` → `mprotect` estricto sin W+X simultáneo) los segmentos `PT_LOAD` de binarios estáticos `linux/arm64` (`ET_EXEC`/`ET_DYN`), construye el stack y vector auxiliar (`AT_PHDR`, `AT_PAGESZ`, `AT_HWCAP`, `AT_RANDOM`), instala `SIGSYS -> -ENOSYS`, adapta el fallback DNS y transfiere control al entrypoint `_start`.
- **Parcheo de DNS y TLS en `cloudflared`**: el workflow de compilación parchea `libcloudflared.so` reemplazando el fallback DNS `127.0.0.1:53` por `1.1.1.1:0053`, y `PaaSRuntimeService` genera un bundle `cacerts.pem` a partir de los certificados raíz del sistema Android para validar TLS sin errores.
- **Preloader Node (`cps_node_preload.cjs`)**: parchea `Module._resolveFilename` y `Module.globalPaths` para compartir módulos globales y las dependencias instaladas bajo `cps_shared_node_modules`.
- **Pipeline de Compilación Automática (`.github/workflows/build-apk.yml`)**: compila en `ubuntu-24.04` usando contenedores `alpine:3.20` + QEMU ARM64 para construir los paquetes nativos, compilar `libcps_dotnet_host.so` y `libcps_elf_host.so` (con prueba de ejecución ARM64 en QEMU), generar la APK con Gradle 8.9 (AGP 8.5 / JDK 17) y publicarla en Artifacts y Releases.

---

## 📁 Estructura del Código Fuente


### Aplicación Android Nativa (`android/`)

- [`android/app/build.gradle.kts`](android/app/build.gradle.kts): configuración de SDK (`compileSdk 34`, `minSdk 28`, `targetSdk 34`), filtro `arm64-v8a` y `useLegacyPackaging` para librerías nativas.
- [`android/app/src/main/AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml): permisos de red, Wi-Fi, ubicación, WakeLock, `FOREGROUND_SERVICE_SPECIAL_USE`, `extractNativeLibs="true"` y tráfico cleartext local.
- [`android/app/src/main/java/com/cajitapaas/ui/MainActivity.kt`](android/app/src/main/java/com/cajitapaas/ui/MainActivity.kt): pantalla principal con WebView del panel en `:4000`, selector de archivos `.ZIP`/`.SQL` y solicitud de exención de batería y notificaciones.
- [`android/app/src/main/java/com/cajitapaas/core/PaaSRuntimeService.kt`](android/app/src/main/java/com/cajitapaas/core/PaaSRuntimeService.kt): servicio persistente en primer plano con WakeLock/WifiLock, pulso keep-alive, ciclo de `cloudflared`, bundle TLS (`cacerts.pem`) y redirección Root de `:80 -> :8080`.
- [`android/app/src/main/java/com/cajitapaas/core/EmbeddedControlPlaneServer.kt`](android/app/src/main/java/com/cajitapaas/core/EmbeddedControlPlaneServer.kt): servidor HTTP Kotlin nativo multipuerto (`:4000` Control Plane, `:8080` Gateway, `:3001+` por app), proxy inverso, gestión de procesos Node.js/.NET 10, Watchdog, editor `.env`/`appsettings.json` (JSONC), instalador NPM, export/import `.SQL` y telemetría/control de recursos (`/proc/<pid>`, RAM, CPU, disco).
- [`android/app/src/main/java/com/cajitapaas/core/EmbeddedDatabaseEngine.kt`](android/app/src/main/java/com/cajitapaas/core/EmbeddedDatabaseEngine.kt): ciclo de vida del motor MariaDB 10.11 Alpine ARM64, puente TCP `:3306`/`:13306`, extracción rápida de bundles (`.alpine_mariadb_*` / `.alpine_dotnet10_*`) y métricas de PID/tamaño de datos.
- [`android/app/src/main/java/com/cajitapaas/core/NetworkFailoverManager.kt`](android/app/src/main/java/com/cajitapaas/core/NetworkFailoverManager.kt): máquina de estados para Supervivencia Wi-Fi (Failover a Hotspot AP y Failback con sondeo en Modo Ciego para hardware single-radio).
- [`android/app/src/main/java/com/cajitapaas/core/RootDetector.kt`](android/app/src/main/java/com/cajitapaas/core/RootDetector.kt) y [`RootShellUtils.kt`](android/app/src/main/java/com/cajitapaas/core/RootShellUtils.kt): detección de `su`/cgroups y utilidad segura para ejecutar comandos `su -c` con timeout y lectura concurrente de streams.

### Panel de Control Web (`control-plane/`)

- [`control-plane/public/index.html`](control-plane/public/index.html) y [`control-plane/public/app.js`](control-plane/public/app.js): interfaz completa (login/setup, 4 pestañas, consola en vivo, editor `.env`/`appsettings.json`, gestor NPM, DBeaver/SQL, Cloudflare Tunnel, Supervivencia Wi-Fi, Monitor de Recursos y modal por app).
- [`control-plane/server.js`](control-plane/server.js) y [`control-plane/preview-server.js`](control-plane/preview-server.js): servidor Control Plane en Node.js (con `pm2`, `simple-git`, `express`, `mysql2`) y servidor local de vista previa UI en PC.

### Scripts de Empaquetado Nativo y CI/CD (`scripts/` y `.github/`)

- [`scripts/cps_dotnet_host.c`](scripts/cps_dotnet_host.c): host nativo C (`libcps_dotnet_host.so`) para arrancar CoreCLR/.NET 10 en Android sin errores de `/proc/self/exe` ni `SIGSYS`.
- [`scripts/cps_elf_host.c`](scripts/cps_elf_host.c): cargador ELF en memoria (`libcps_elf_host.so`) para ejecutar binarios Go estáticos `linux/arm64` en Android respetando SELinux W^X.
- [`scripts/build_dotnet_alpine_arm64.sh`](scripts/build_dotnet_alpine_arm64.sh): script Alpine 3.20 ARM64 que descarga ASP.NET Core 10.0 (`linux-musl-arm64`), mapea librerías nativas y genera `dotnet_alpine_bundle.zip`.
- [`scripts/build_mariadb_alpine_arm64.sh`](scripts/build_mariadb_alpine_arm64.sh): script Alpine 3.20 ARM64 que empaqueta `mariadbd`, `mariadb`, `node`, módulos preinstalados y datadir inicial de MariaDB.
- [`scripts/install_on_phone_termux.sh`](scripts/install_on_phone_termux.sh), [`scripts/boot_nonroot_proot.sh`](scripts/boot_nonroot_proot.sh) y [`scripts/boot_root_native.sh`](scripts/boot_root_native.sh): scripts complementarios de entorno Termux, PRoot Debian y arranque nativo con cgroups/Docker en Root.
- [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml): workflow de GitHub Actions que construye los runtimes ARM64, compila `libcps_dotnet_host.so` vía QEMU y genera/publica la APK automáticamente.

---

## 📄 Licencia y Copyright

Copyright (c) 2026 **Fernando Arias**. Todos los derechos reservados.

Este proyecto se distribuye bajo una **Licencia de Uso Personal, Educativo y No Comercial** (_Source-Available / Non-Commercial_).

- ✅ **Permitido**: Eres libre de descargar, compilar, modificar y utilizar este software de forma totalmente gratuita para tus proyectos personales, familiares, domésticos, educativos, de estudio o investigación sin fines de lucro.
- ❌ **Prohibido sin autorización**: Queda expresamente prohibida la comercialización, reventa, sublicenciamiento o utilización de este software (total o parcial) en plataformas, empresas o productos lucrativos, así como ofrecerlo como servicio de hosting o infraestructura PaaS/SaaS de pago a terceros sin el consentimiento previo y por escrito del autor.

Para leer los términos legales completos, consulta el archivo [LICENSE](./LICENSE).
