# 🚀 Guía de Despliegue: Sistema Completo (Fullstack)

> [⬅️ Volver al README principal](../README.md) · [🖥️ Guía Frontend](./guia-frontend.md) · [⚙️ Guía Backend / API](./guia-backend-api.md)

Usa el rol **🚀 Sistema Completo (Fullstack)** cuando tu proyecto combina en **un solo servidor** la interfaz web (HTML/CSS/JS, vistas Razor/MVC o carpeta `wwwroot/`/`public/`) junto con sus endpoints de negocio/API y conexión a base de datos.

---

## 1. Cómo empaquetar según tecnología

### 🟣 A. ASP.NET Core MVC / Razor Pages / Blazor Server / API + `wwwroot`
1. Publica desde tu PC dentro de la carpeta del `.csproj`:
   ```bash
   dotnet publish -c Release -o ./publish
   ```
2. Comprime el contenido dentro de `./publish/` en un `.zip` (debe contener `TuApp.dll`, `TuApp.runtimeconfig.json`, `TuApp.deps.json`, `appsettings.json` y tu carpeta `wwwroot/` o vistas compiladas).
3. Sube el `.zip` con rol **🚀 Sistema Completo (Fullstack)** y ruta **`/`**.
4. **Particularidades automáticas de CajitaPaaS para .NET Fullstack**:
   - Configura `contentRoot` donde detecta `wwwroot/`, `Views/` o `appsettings.json`.
   - Ejecuta sobre **.NET 10 ARM64** (`DOTNET_ROLL_FORWARD=Major`, compatible con `.NET 6, 7, 8, 9 y 10`).
   - Inyecta `ASPNETCORE_URLS=http://127.0.0.1:<puerto_interno>`, `ASPNETCORE_ENVIRONMENT=Production`, `DOTNET_GCHeapHardLimit`, `DOTNET_PROCESSOR_COUNT` y `ConnectionStrings__DefaultConnection` apuntando a MariaDB local (`127.0.0.1:3306`).
   - Desde **⚙️ Variables .env** puedes editar visualmente o en JSON crudo tu `appsettings.json` / `appsettings.Production.json` (incluyendo comentarios **JSONC** `//`, `/* ... */` y comas finales de Visual Studio).

### 🟢 B. Node.js Fullstack (Express + `public/`, EJS, Pug, Handlebars o build React dentro de Express)
1. Asegúrate de que tu `server.js` / `app.js` sirva los estáticos (ej. `app.use(express.static("public"))`) y tus rutas `/api/...` en el mismo `process.env.PORT`.
2. Comprime tu código, `package.json`, carpeta de vistas/estáticos y `.env` — **nunca incluyas `node_modules/`**.
3. Súbelo con rol **🚀 Sistema Completo (Fullstack)** y ruta **`/`**.

### 🔵 C. Go Fullstack (`embed.FS` o carpeta `public/` junto al binario)
1. Compila en tu PC para **`linux/arm64` estático** (`CGO_ENABLED=0` y `-tags "purego,nodynamic"` si aplica):
   ```powershell
   $env:GOOS="linux"; $env:GOARCH="arm64"; $env:CGO_ENABLED="0"
   go build -tags "purego,nodynamic" -trimpath -ldflags="-s -w" -o mi-sistema ./cmd/server
   ```
2. Comprime el binario ARM64 junto a tu carpeta `public/`/`templates/` (si no van embebidos con `//go:embed`) y `.env`.
3. Súbelo con rol **🚀 Sistema Completo (Fullstack)** y ruta **`/`**.

---

## 2. Configuración recomendada en el Panel Web

| Campo del formulario | Valor recomendado | Notas |
| :--- | :--- | :--- |
| **Identificador** | `mi-sistema` *(sin espacios)* | Nombre interno del servicio. |
| **Rol** | `🚀 Sistema Completo (Fullstack)` | Levanta el proceso del runtime y además muestra Consola, Variables, BD y Dependencias. |
| **Ruta Web Pública** | `/` | Atiende todo el sitio y sus endpoints bajo la raíz de `:8080` y Cloudflare. |
| **Base de Datos** | `nombre_bd` *(ej. `epica_pos`)* | Opcional; si la indicas, CPS la crea si no existe y sincroniza la conexión. |

---

## 3. Cómo convive un Sistema Completo en `/` con Microservicios API en el mismo teléfono

En escenarios reales (por ejemplo, un **Sistema POS / Web Fullstack en `/`** + un **microservicio independiente en Go/Node en otra ruta**), CajitaPaaS ordena las reglas del Gateway `:8080` (`findMatchingAppForPath`) **por longitud de prefijo descendente**:

1. **Sistema Principal Fullstack (`sistema-pos`)**: Ruta `/` (puerto interno ej. `:3001`).
2. **Microservicio de Imágenes Go (`media-engine`)**: Ruta `/api/v1/imagenes/*,/api/v1/backup/*,/media/*,/docs/*` (puerto interno ej. `:3003`).

Resultado en Cloudflare (`https://xxxx.trycloudflare.com`) y en `:8080`:
- `GET /`, `/Login`, `/Productos`, `/api/ventas` → entran a **`sistema-pos`** (`/`).
- `POST /api/v1/imagenes/ingestar`, `GET /media/productos/.../hd.webp`, `GET /docs/` → entran a **`media-engine`** porque sus prefijos son más específicos que `/`.

> **Importante**: no asignes exactamente el mismo prefijo (por ejemplo `/api/*` en dos apps distintas), ya que la coincidencia exacta enviará todas las rutas `/api/...` al servicio que quede primero con ese prefijo.

---

## 4. Particularidades y buenas prácticas Fullstack

### 🌐 Acceso por Cloudflare HTTPS (`:8080`) vs IP local
- Un servicio Fullstack abre su URL principal en **`http://<IP_CELULAR>:8080/`** (y `:80` automático en teléfonos con Root) y en **`https://<tu-tunel>.trycloudflare.com/`**.
- También conserva su puerto dedicado (`:3001`, `:3003`...), accesible en la red Wi-Fi local.

### 🔐 Sesiones, Cookies y Antiforgery en ASP.NET Core detrás de Cloudflare Tunnel
- Cloudflare termina TLS (`HTTPS`) hacia Internet y envía tráfico HTTP local a `127.0.0.1:8080` dentro del celular.
- Si tu app ASP.NET Core emite cookies con `CookieSecurePolicy.Always` o valida HTTPS estricto sin `ForwardedHeaders` (`X-Forwarded-Proto`), los inicios de sesión por formulario (`__RequestVerificationToken`) o cookies de autenticación pueden fallar o devolver HTTP `500` si no aceptan el proxy inverso local. Configura `UseForwardedHeaders` o `CookieSecurePolicy.SameAsRequest` para que funcione igual en `http://<IP>:8080` y en `https://*.trycloudflare.com`.

### 🗄️ Base de datos y monitoreo
- Si tu sistema usa **MariaDB**, puedes gestionar tablas desde **🗄️ 2. Mis Bases de Datos y Modo PC** (DBeaver en `:3306`/`:13306`, importar `.sql` inicial o descargar backups `.sql`).
- Si notas consumo elevado de memoria en proyectos pesados (ej. ASP.NET Core + EF Core), abre **📊 Recursos** en la tarjeta de tu sistema para subir su cuota de RAM (hasta `2048 MB`), limitar núcleos CPU o ajustar su prioridad `nice`.
