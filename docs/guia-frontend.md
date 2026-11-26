# 🖥️ Guía de Despliegue: Frontend (HTML, React, Vue, Vite, Angular)

> [⬅️ Volver al README principal](../README.md) · [⚙️ Guía Backend / API](./guia-backend-api.md) · [🚀 Guía Sistema Completo (Fullstack)](./guia-sistema-completo.md)

Usa el rol **🖥️ Frontend** cuando quieras publicar una página estática o una SPA (Single Page Application) compilada en HTML, CSS y JavaScript que corre directamente en el navegador del usuario y consume (opcionalmente) una o varias APIs aparte.

---

## 1. Qué comprimir (y qué NO subir)

1. Genera en tu PC la compilación de producción de tu proyecto:
   ```bash
   npm run build
   # o con pnpm:
   pnpm build
   ```
2. Comprime en un archivo **`.zip`** (máximo **100 MB**) el contenido compilado donde está **`index.html`** (normalmente el contenido dentro de `dist/`, `build/`, `www/` o `out/`).

> [!WARNING]
> **Qué NO debes incluir en el `.zip` de un Frontend:**
> - ❌ **Nunca incluyas `node_modules/`**: el navegador no usa `node_modules` y solo infla el archivo.
> - ❌ **No subas código fuente crudo sin compilar** (archivos `.tsx`, `.jsx`, `.vue`, `vite.config.ts` sin `index.html` compilado), porque CajitaPaaS sirve los archivos estáticos listos para navegador.

### Detección automática de carpeta
Si al comprimir metiste la carpeta entera en lugar de solo su contenido, CajitaPaaS (`resolveEffectiveWebRoot`) busca automáticamente `index.html`:
1. En la raíz extraída.
2. En subcarpetas comunes: `dist/`, `build/`, `public/`, `www/`, `out/`.
3. Desempaquetando una carpeta raíz única envolvente si hubiera solo una.

---

## 2. Configuración recomendada en el Panel Web

| Campo del formulario | Valor recomendado | Notas |
| :--- | :--- | :--- |
| **Identificador** | `tienda-front` *(sin espacios)* | Nombre interno del servicio. |
| **Rol** | `🖥️ Frontend (Web / React / Vue / HTML)` | Sirve archivos estáticos sin levantar proceso Node/.NET/Go. |
| **Ruta Web Pública** | `/` | Lo expone en la raíz de `:8080` y del túnel HTTPS de Cloudflare. |
| **Base de Datos** | *(vacío)* | Un frontend estático no se conecta directo a MariaDB. |

> Si deseas alojar más de un frontend en el mismo celular, puedes asignar subrutas distintas al segundo, por ejemplo `/admin/*` o `/landing/*`.

---

## 3. Particularidades importantes del Frontend en CajitaPaaS

### 🔁 Respaldo automático para SPAs (React Router / Vue Router / Angular)
Si el usuario recarga una ruta como `/productos/42` (una ruta sin extensión de archivo), CajitaPaaS detecta que no existe un archivo físico con ese nombre y sirve automáticamente tu `index.html` para que el router del cliente maneje la navegación sin error `404`.

### 🔗 Cómo conectar el Frontend con tu Backend sin errores de CORS
Cuando despliegas el Frontend en `/` y un Backend en `/api/*` dentro del mismo CajitaPaaS, el Gateway `:8080` y Cloudflare Tunnel los unifican bajo el mismo dominio/origen:

1. **Rutas relativas `/api/...` (Recomendado ⭐)**:
   ```javascript
   // Funciona igual dentro de tu Wi-Fi (:8080) y desde Internet por Cloudflare (https://...)
   const res = await fetch("/api/productos");
   ```
2. **Puente automático para URLs hardcodeadas a `localhost`**:
   Si tu build ya fue compilado apuntando a `http://localhost:3001` o `http://127.0.0.1:3000`, CajitaPaaS inyecta automáticamente un script al servir `index.html` (`injectCpsApiBridgeIntoHtml`) que intercepta `fetch` y `XMLHttpRequest` para redirigirlos:
   - En **Wi-Fi LAN**: hacia `http://<IP_DEL_CELULAR>:<PUERTO_BACKEND>/...`.
   - En **Túnel HTTPS (Cloudflare)**: hacia `/api/...` dentro del mismo origen HTTPS (evitando bloqueos de *Mixed Content*).
3. **Variables globales disponibles en el navegador**:
   - `window.CPS_API_URL`
   - `window.CPS_GATEWAY_API_URL`
   - `window.CPS_API_PORT`

### 🌐 Diferencia entre Puerto `:8080` y Puerto Directo (`:3001`, `:3003`...)
- **Acceso principal**: `http://<IP_CELULAR>:8080/` (o `http://<IP_CELULAR>/` si el teléfono tiene Root con redirección automática `:80 -> :8080`) y `https://<tu-tunel>.trycloudflare.com/`.
- **Puerto asignado**: también queda accesible en su puerto dedicado (ej. `:3001`), aunque si quieres que las llamadas `/api/...` lleguen al backend bajo el mismo origen usa siempre el **Gateway `:8080`** o la **URL HTTPS de Cloudflare**.

---

## 4. Actualizaciones y Recursos
- **✏️ Actualizar (Blue/Green)**: al actualizar tu frontend con un nuevo `.zip`, CajitaPaaS alterna automáticamente el color de slot (`BLUE` / `GREEN`) manteniendo puerto y ruta.
- **📊 Recursos**: aunque los frontends estáticos consumen `0 MB` de RAM de runtime propio (los sirve el servidor integrado de CajitaPaaS), puedes ver cuánto disco ocupan, definir una cuota de almacenamiento de referencia o pausarlos desde el botón **📊 Recursos**.
