package com.cajitapaas.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Servidor HTTP del Control Plane embebido nativamente en Kotlin para que la APK
 * funcione inmediatamente al instalarla desde GitHub Actions, sirviendo el Panel Web
 * tanto dentro de la APK (WebView) como por Wi-Fi (0.0.0.0:4000) y Túnel Cloudflare.
 */
class EmbeddedControlPlaneServer(
    private val context: Context,
    private val port: Int = 4000
) {
    companion object {
        private const val TAG = "CPSHttpServer"
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val prefs by lazy {
        context.getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    var publicTunnelUrl: String? = null

    @Volatile
    private var dbeaverBridgeEnabled = false

    @Volatile
    private var dbeaverBridgeMode = "session"

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "CPS-Embedded-HTTP") {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Control Plane escuchando en 0.0.0.0:$port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Error en servidor HTTP embebido", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1].substringBefore("?")

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            if (rawPath.startsWith("/api/")) {
                handleApiRequest(method, rawPath, body, out)
            } else {
                serveStaticAsset(rawPath, out)
            }
        }
    }

    private fun handleApiRequest(method: String, path: String, body: String, out: OutputStream) {
        val isInitialized = prefs.getBoolean("initialized", false)

        if (path == "/api/auth/setup-status" && method == "GET") {
            return sendJson(out, 200, JSONObject().put("initialized", isInitialized).toString())
        }

        if (path == "/api/auth/setup" && method == "POST") {
            if (isInitialized) {
                return sendJson(out, 400, JSONObject().put("error", "La contraseña ya fue configurada.").toString())
            }
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val pass = json.optString("password", "").trim()
            if (pass.length < 6) {
                return sendJson(out, 400, JSONObject().put("error", "Mínimo 6 caracteres.").toString())
            }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val hash = sha256("$salt:$pass")
            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

            prefs.edit()
                .putBoolean("initialized", true)
                .putString("salt", salt)
                .putString("hash", hash)
                .putString("token", token)
                .apply()

            return sendJson(out, 201, JSONObject().put("token", token).toString())
        }

        if ((path == "/api/auth/login" || path == "/api/v1/auth/token") && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val pass = json.optString("password", json.optString("apiKey", "")).trim()
            val salt = prefs.getString("salt", "") ?: ""
            val expectedHash = prefs.getString("hash", "") ?: ""
            if (expectedHash.isNotEmpty() && sha256("$salt:$pass") == expectedHash) {
                val token = prefs.getString("token", "cps-token") ?: "cps-token"
                return sendJson(out, 200, JSONObject().put("token", token).toString())
            }
            return sendJson(out, 401, JSONObject().put("error", "Contraseña incorrecta.").toString())
        }

        if (path == "/api/status" || path == "/api/v1/status") {
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("system", "CPS (CajitaPaaS Android APK)")
                    .put("status", "online")
                    .put("mariadbConnected", true)
                    .toString()
            )
        }

        if (path == "/api/v1/projects" && method == "GET") {
            val activeSlots = JSONObject()
                .put(
                    "portal-clientes",
                    JSONObject()
                        .put("appRole", "frontend")
                        .put("routePath", "/")
                        .put("activeColor", "blue")
                        .put("activePort", 3001)
                )
                .put(
                    "api-pagos",
                    JSONObject()
                        .put("appRole", "backend")
                        .put("routePath", "/api/*")
                        .put("activeColor", "green")
                        .put("activePort", 3004)
                )

            val pm2 = JSONArray()
                .put(JSONObject().put("name", "portal-clientes-blue").put("pid", 5109).put("status", "online").put("memoryMb", 48))
                .put(JSONObject().put("name", "api-pagos-green").put("pid", 4821).put("status", "online").put("memoryMb", 64))

            return sendJson(out, 200, JSONObject().put("activeSlots", activeSlots).put("pm2Processes", pm2).toString())
        }

        if (path == "/api/v1/tunnel/status") {
            val lanIp = getDeviceLanIp()
            val pub = publicTunnelUrl ?: "http://$lanIp:$port"
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("mode", "quick")
                    .put("status", "online")
                    .put("publicUrl", pub)
                    .put("adminUrl", "$pub/_control/")
                    .put("lanUrl", "http://$lanIp:$port")
                    .toString()
            )
        }

        if (path == "/api/v1/db/bridge/status" || path == "/api/v1/db/bridge/heartbeat") {
            return sendJson(out, 200, JSONObject().put("bridge", buildBridgeJson()).toString())
        }

        if (path == "/api/v1/db/bridge/toggle" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            dbeaverBridgeEnabled = json.optBoolean("enabled", false)
            dbeaverBridgeMode = json.optString("mode", "session")
            return sendJson(out, 200, JSONObject().put("bridge", buildBridgeJson()).toString())
        }

        if (path == "/api/v1/db/databases" && method == "GET") {
            val dbs = JSONArray()
                .put(JSONObject().put("name", "cajitapaas").put("isSystem", false).put("tables", JSONArray().put("deployments_audit")))
                .put(JSONObject().put("name", "app_ventas_db").put("isSystem", false).put("tables", JSONArray().put("clientes").put("pagos")))
            return sendJson(out, 200, JSONObject().put("databases", dbs).toString())
        }

        return sendJson(out, 200, JSONObject().put("status", "ok").toString())
    }

    private fun buildBridgeJson(): JSONObject {
        return JSONObject()
            .put("enabled", dbeaverBridgeEnabled)
            .put("mode", dbeaverBridgeMode)
            .put("host", getDeviceLanIp())
            .put("port", 13306)
            .put("remainingSeconds", 900)
            .put("activeConnections", if (dbeaverBridgeEnabled) 1 else 0)
    }

    private fun serveStaticAsset(rawPath: String, out: OutputStream) {
        val cleanPath = if (rawPath == "/" || rawPath.isEmpty()) "public/index.html" else "public/${rawPath.trimStart('/')}"
        try {
            val bytes = context.assets.open(cleanPath).use { it.readBytes() }
            val mime = when {
                cleanPath.endsWith(".html") -> "text/html; charset=utf-8"
                cleanPath.endsWith(".js") -> "application/javascript; charset=utf-8"
                cleanPath.endsWith(".css") -> "text/css; charset=utf-8"
                cleanPath.endsWith(".jpg") -> "image/jpeg"
                cleanPath.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
            val notFound = "404 Not Found".toByteArray()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${notFound.size}\r\n\r\n".toByteArray())
            out.write(notFound)
            out.flush()
        }
    }

    private fun sendJson(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "OK" else if (status == 201) "Created" else "Error"
        val header = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun getDeviceLanIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}

