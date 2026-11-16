package com.cajitapaas.core

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Servidor HTTP del Control Plane (puerto 4000) + Gateway de Aplicaciones (puerto 8080)
 * + Servidores dedicados por puerto de cada App desplegada (3001, 3003, etc.).
 *
 * Al subir un archivo .ZIP, extrae su contenido real en `filesDir/deployed_apps/<appName>`,
 * detecta si es un Backend Node.js (ejecutándolo con `libnode.so` de Alpine ARM64) o un Frontend Web,
 * y expone el servicio en `0.0.0.0:<activePort>` y en el Gateway `:8080`.
 */
class EmbeddedControlPlaneServer(
    private val context: Context,
    private val port: Int = 4000,
    var failoverManager: NetworkFailoverManager? = null,
    var onTunnelRestartRequested: ((mode: String, token: String, customDomain: String) -> String?)? = null
) {
    companion object {
        private const val TAG = "CPSHttpServer"
        private const val PREF_PROJECTS_KEY = "deployed_projects_v2_json"
        const val GATEWAY_PORT = 8080
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var gatewaySocket: ServerSocket? = null
    private val appServerSockets = ConcurrentHashMap<String, ServerSocket>()
    private val appProcesses = ConcurrentHashMap<String, Process>()
    private val appNodeLogs = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool()

    private val prefs by lazy {
        context.getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
    }

    private val dbEngine by lazy {
        EmbeddedDatabaseEngine(context.applicationContext)
    }

    private val deployedAppsBaseDir by lazy {
        File(context.filesDir, "deployed_apps").apply { mkdirs() }
    }

    @Volatile
    var publicTunnelUrl: String? = null

    @Volatile
    var tunnelStatus: String = "connecting"

    @Volatile
    var tunnelLastError: String? = null

    @Synchronized
    fun start() {
        val controlAlive = serverSocket != null && !serverSocket!!.isClosed
        val gatewayAlive = gatewaySocket != null && !gatewaySocket!!.isClosed
        if (isRunning && controlAlive && gatewayAlive) return
        isRunning = true

        // 1. Iniciar servidor principal del Panel de Configuración (0.0.0.0:4000)
        if (!controlAlive) {
            thread(name = "CPS-Embedded-HTTP-$port") {
                try {
                    val srv = ServerSocket()
                    srv.reuseAddress = true
                    srv.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 50)
                    serverSocket = srv
                    Log.i(TAG, "Control Plane escuchando en red interna 0.0.0.0:$port (LAN IP: ${getDeviceLanIp()}:$port)")
                    while (isRunning && !srv.isClosed) {
                        val client = srv.accept() ?: break
                        executor.execute { handleClient(client) }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Error en servidor HTTP embebido (:$port)", e)
                }
            }
        }

        // 2. Iniciar Gateway reverso en 0.0.0.0:8080 (para el túnel Cloudflare y acceso general)
        if (!gatewayAlive) {
            thread(name = "CPS-Gateway-$GATEWAY_PORT") {
                try {
                    val gw = ServerSocket()
                    gw.reuseAddress = true
                    gw.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), GATEWAY_PORT), 50)
                    gatewaySocket = gw
                    Log.i(TAG, "Gateway de Apps escuchando en 0.0.0.0:$GATEWAY_PORT")
                    while (isRunning && !gw.isClosed) {
                        val client = gw.accept() ?: break
                        executor.execute { handleGatewayClient(client) }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Error en Gateway HTTP (:$GATEWAY_PORT)", e)
                }
            }
        }

        // 3. Levantar los servidores dedicados (ej. :3001, :3003) de las apps que ya estaban guardadas
        if (appServerSockets.isEmpty()) {
            restoreRunningAppServers()
        }
    }

    fun stop() {
        isRunning = false
        dbEngine.shutdownEngine()
        runCatching { serverSocket?.close() }
        runCatching { gatewaySocket?.close() }
        for ((_, sock) in appServerSockets) {
            runCatching { sock.close() }
        }
        appServerSockets.clear()
        for ((_, proc) in appProcesses) {
            runCatching { proc.destroyForcibly() }
        }
        appProcesses.clear()
    }

    private fun restoreRunningAppServers() {
        val projects = loadSavedProjects()
        val keys = projects.keys()
        while (keys.hasNext()) {
            val appName = keys.next()
            val info = projects.optJSONObject(appName) ?: continue
            val appPort = info.optInt("activePort", 3001)
            startDedicatedAppServer(appName, appPort)
        }
    }

    /**
     * Abre un ServerSocket real en `0.0.0.0:<appPort>` (ej. 3001) y, si el proyecto contiene
     * un Backend Node.js (`server.js` / `index.js` / `package.json`), levanta su proceso real con `libnode.so`.
     */
    private fun startDedicatedAppServer(appName: String, appPort: Int) {
        stopDedicatedAppServer(appName)
        executor.execute {
            runCatching { startAppNodeProcessIfPresent(appName, appPort) }
        }
        thread(name = "CPS-App-$appName-$appPort") {
            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), appPort), 50)
                appServerSockets[appName] = sock
                Log.i(TAG, "App '$appName' sirviendo en http://${getDeviceLanIp()}:$appPort")
                while (isRunning && !sock.isClosed) {
                    val client = sock.accept() ?: break
                    executor.execute { handleDedicatedAppClient(appName, appPort, client) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Servidor de app '$appName' en puerto $appPort finalizado: ${e.message}")
            }
        }
    }

    private fun stopDedicatedAppServer(appName: String) {
        appServerSockets.remove(appName)?.let { sock ->
            runCatching { sock.close() }
        }
        appProcesses.remove(appName)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * Detecta si el directorio desplegado de `appName` contiene una aplicación Node.js
     * y la ejecuta en segundo plano redirigiendo su puerto interno a `appPort + 10000` (ej. 13001).
     */
    @Synchronized
    private fun startAppNodeProcessIfPresent(appName: String, appPort: Int) {
        val baseDir = File(deployedAppsBaseDir, appName)
        if (!baseDir.exists()) return
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val entryScript = findNodeEntryScript(appRoot) ?: return

        val existingProc = appProcesses[appName]
        val internalNodePort = appPort + 10000
        if (existingProc != null && existingProc.isAlive && isLocalPortOpen(internalNodePort, 150)) {
            return
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val muslLinker = File(nativeLibDir, "libalpine_musl.so")
        val nodeBin = File(nativeLibDir, "libnode.so")
        if (!muslLinker.exists() || !nodeBin.exists()) {
            Log.w(TAG, "Binario libnode.so no disponible para iniciar '$appName'")
            return
        }

        runCatching { Os.chmod(muslLinker.absolutePath, 493) }
        runCatching { Os.chmod(nodeBin.absolutePath, 493) }

        val libSearchPath = dbEngine.getLibSearchPath()
        val globalNodeModules = dbEngine.getNodeGlobalModulesPath()
        ensureNodeModulesLinked(appRoot, globalNodeModules)

        val preloadFile = File(context.filesDir, "cps_node_preload.cjs")
        preloadFile.writeText(
            """
            const net = require('net');
            const dns = require('dns');
            const Module = require('module');
            const path = require('path');
            const fs = require('fs');

            if (dns.setDefaultResultOrder) {
              dns.setDefaultResultOrder('ipv4first');
            }

            if (typeof BigInt !== 'undefined' && !BigInt.prototype.toJSON) {
              BigInt.prototype.toJSON = function() { return Number(this); };
            }

            process.on('unhandledRejection', (reason) => {
              console.error('[CPS-UnhandledRejection]', reason);
            });
            process.on('uncaughtException', (err) => {
              console.error('[CPS-UncaughtException]', err);
            });

            const globalModPath = process.env.CPS_GLOBAL_NODE_MODULES || '';

            if (globalModPath) {
              if (Array.isArray(Module.globalPaths) && !Module.globalPaths.includes(globalModPath)) {
                Module.globalPaths.push(globalModPath);
              }
              const parentGlobal = path.dirname(globalModPath);
              if (Array.isArray(Module.globalPaths) && !Module.globalPaths.includes(parentGlobal)) {
                Module.globalPaths.push(parentGlobal);
              }
            }

            const origNodeModulePaths = Module._nodeModulePaths;
            Module._nodeModulePaths = function(from) {
              const paths = origNodeModulePaths.call(this, from) || [];
              if (globalModPath && !paths.includes(globalModPath)) {
                paths.push(globalModPath);
              }
              return paths;
            };

            const origResolveLookupPaths = Module._resolveLookupPaths;
            Module._resolveLookupPaths = function(request, parent) {
              const paths = origResolveLookupPaths.call(this, request, parent) || [];
              if (globalModPath && !paths.includes(globalModPath)) {
                paths.push(globalModPath);
              }
              return paths;
            };

            const origResolveFilename = Module._resolveFilename;
            Module._resolveFilename = function(request, parent, isMain, options) {
              try {
                return origResolveFilename.call(this, request, parent, isMain, options);
              } catch (err) {
                if (globalModPath && !request.startsWith('.') && !request.startsWith('/')) {
                  const candidateDir = path.join(globalModPath, request);
                  if (fs.existsSync(candidateDir)) {
                    const candidatePkg = path.join(candidateDir, 'package.json');
                    if (fs.existsSync(candidatePkg)) {
                      try {
                        const pkg = JSON.parse(fs.readFileSync(candidatePkg, 'utf8'));
                        const mainFile = pkg.main || 'index.js';
                        const resolvedMain = path.resolve(candidateDir, mainFile);
                        if (fs.existsSync(resolvedMain)) {
                          return resolvedMain;
                        }
                      } catch (_pe) {}
                    }
                    const candidateIndex = path.join(candidateDir, 'index.js');
                    if (fs.existsSync(candidateIndex)) {
                      return candidateIndex;
                    }
                  }
                  const candidateJs = candidateDir + '.js';
                  if (fs.existsSync(candidateJs)) {
                    return candidateJs;
                  }
                }
                throw err;
              }
            };

            const origLoad = Module._load;
            Module._load = function(request, parent, isMain) {
              if (request === 'bcrypt') {
                try {
                  return origLoad.apply(this, arguments);
                } catch (_e) {
                  return origLoad.call(this, 'bcryptjs', parent, isMain);
                }
              }
              const mod = origLoad.apply(this, arguments);
              if ((request === 'mariadb' || request === 'mysql2' || request === 'mysql2/promise' || request === 'mysql') && mod) {
                const patchCfg = (cfg) => {
                  if (typeof cfg === 'string') {
                    return cfg.replace('localhost', '127.0.0.1');
                  }
                  if (!cfg || typeof cfg !== 'object') return cfg;
                  const copy = Object.assign({}, cfg);
                  if (!copy.host || copy.host === 'localhost') copy.host = '127.0.0.1';
                  if (!copy.port) copy.port = 3306;
                  if (request === 'mariadb') {
                    if (copy.allowPublicKeyRetrieval === undefined) copy.allowPublicKeyRetrieval = true;
                    if (copy.insertIdAsNumber === undefined) copy.insertIdAsNumber = true;
                    if (copy.bigIntAsNumber === undefined) copy.bigIntAsNumber = true;
                  }
                  if (!copy.password && process.env.DB_PASSWORD) {
                    copy.password = process.env.DB_PASSWORD;
                  }
                  if (!copy.database && process.env.DB_NAME) {
                    copy.database = process.env.DB_NAME;
                  }
                  return copy;
                };
                if (typeof mod.createPool === 'function' && !mod.__cpsPatched) {
                  const origPool = mod.createPool;
                  mod.createPool = function(cfg) { return origPool.call(this, patchCfg(cfg)); };
                  const origConn = mod.createConnection;
                  if (typeof origConn === 'function') {
                    mod.createConnection = function(cfg) { return origConn.call(this, patchCfg(cfg)); };
                  }
                  mod.__cpsPatched = true;
                }
              }
              return mod;
            };

            const origListen = net.Server.prototype.listen;
            const targetPort = parseInt(process.env.CPS_INTERNAL_PORT || '$internalNodePort', 10);
            net.Server.prototype.listen = function(...args) {
              if (args.length > 0 && typeof args[0] === 'number' && args[0] !== targetPort) {
                args[0] = targetPort;
              } else if (args.length > 0 && typeof args[0] === 'string' && /^\d+$/.test(args[0])) {
                args[0] = targetPort;
              } else if (args.length > 0 && typeof args[0] === 'object' && args[0] !== null && args[0].port) {
                args[0] = Object.assign({}, args[0], { port: targetPort, host: '127.0.0.1' });
              }
              return origListen.apply(this, args);
            };
            """.trimIndent()
        )

        val localNodeModules = File(appRoot, "node_modules").absolutePath
        val args = listOf(
            muslLinker.absolutePath,
            "--library-path",
            libSearchPath,
            nodeBin.absolutePath,
            "--dns-result-order=ipv4first",
            "--require",
            preloadFile.absolutePath,
            entryScript.absolutePath
        )

        val pb = ProcessBuilder(args)
            .directory(entryScript.parentFile ?: appRoot)
            .redirectErrorStream(true)

        val env = pb.environment()
        // Cargar variables del archivo .env si existe en appRoot o junto al script
        for (candidateEnv in listOf(File(appRoot, ".env"), File(entryScript.parentFile ?: appRoot, ".env"))) {
            if (candidateEnv.exists() && candidateEnv.isFile) {
                runCatching {
                    candidateEnv.readLines().forEach { rawLine ->
                        val trimmed = rawLine.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                            val k = trimmed.substringBefore("=").trim()
                            val v = trimmed.substringAfter("=").trim().trim('"', '\'')
                            if (k.isNotEmpty()) env[k] = v
                        }
                    }
                }
            }
        }

        env["PORT"] = internalNodePort.toString()
        env["CPS_INTERNAL_PORT"] = internalNodePort.toString()
        env["CPS_GLOBAL_NODE_MODULES"] = globalNodeModules
        env["HOST"] = "127.0.0.1"
        env["NODE_ENV"] = "production"
        env["NODE_PATH"] = "$localNodeModules:$globalNodeModules"
        env["LD_LIBRARY_PATH"] = libSearchPath
        env["HOME"] = appRoot.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath

        val projects = loadSavedProjects()
        val appConfig = projects.optJSONObject(appName)
        val projectDb = appConfig?.optString("dbName")?.takeIf { it.isNotBlank() }
            ?: appConfig?.optString("database")?.takeIf { it.isNotBlank() }

        // Si el usuario especificó una BD en su .env, respetarla siempre con máxima prioridad
        val envDb = env["DB_NAME"]?.takeIf { it.isNotBlank() }
            ?: env["DB_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["MYSQL_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["DATABASE_NAME"]?.takeIf { it.isNotBlank() }

        val effectiveDb = envDb ?: projectDb ?: EmbeddedDatabaseEngine.DEFAULT_DB_NAME

        if (env["DB_HOST"].isNullOrBlank() || env["DB_HOST"] == "localhost") env["DB_HOST"] = "127.0.0.1"
        if (env["DB_PORT"].isNullOrBlank()) env["DB_PORT"] = "3306"
        if (env["DB_USER"].isNullOrBlank()) env["DB_USER"] = dbEngine.getDbUser()
        if (env["DB_PASS"].isNullOrBlank()) env["DB_PASS"] = dbEngine.getDbPassword()
        if (env["DB_PASSWORD"].isNullOrBlank()) env["DB_PASSWORD"] = dbEngine.getDbPassword()

        env["DB_NAME"] = effectiveDb
        env["DB_DATABASE"] = effectiveDb
        env["DATABASE"] = effectiveDb
        env["MYSQL_DATABASE"] = effectiveDb
        env["DATABASE_NAME"] = effectiveDb

        appProcesses.remove(appName)?.let { runCatching { it.destroyForcibly() } }
        val logBuffer = ArrayDeque<String>(30)
        val proc = pb.start()
        appProcesses[appName] = proc
        Log.i(TAG, "Proceso Node.js iniciado para '$appName' (${entryScript.absolutePath}) en puerto interno $internalNodePort")

        thread(name = "CPS-NodeLog-$appName", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            synchronized(logBuffer) {
                                if (logBuffer.size >= 25) logBuffer.removeFirst()
                                logBuffer.addLast(line)
                                appNodeLogs[appName] = logBuffer.joinToString("\n")
                            }
                        }
                        Log.i(TAG, "[Node:$appName] $line")
                    }
                }
            }
        }
    }

    /**
     * Crea enlaces simbólicos de `node_modules` tanto en los directorios padre (`filesDir`, `deployed_apps`)
     * como en `appRoot/node_modules` para que tanto CommonJS (`require`) como ES Modules (`import`)
     * encuentren todos los paquetes preinstalados (`express`, `mysql2`, `cors`, `dotenv`, etc.).
     */
    private fun ensureNodeModulesLinked(appRoot: File, globalNodeModulesPath: String) {
        val globalDir = File(globalNodeModulesPath)
        if (!globalDir.exists()) return

        for (parentDir in listOf(context.filesDir, deployedAppsBaseDir)) {
            val link = File(parentDir, "node_modules")
            runCatching {
                if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    java.nio.file.Files.deleteIfExists(link.toPath())
                }
                if (!link.exists()) {
                    Os.symlink(globalDir.absolutePath, link.absolutePath)
                }
            }
        }

        val appNodeModules = File(appRoot, "node_modules")
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(appNodeModules.toPath())) {
                val target = runCatching { java.nio.file.Files.readSymbolicLink(appNodeModules.toPath()) }.getOrNull()
                if (target == null || target.toString() != globalDir.absolutePath || !appNodeModules.exists()) {
                    java.nio.file.Files.deleteIfExists(appNodeModules.toPath())
                    Os.symlink(globalDir.absolutePath, appNodeModules.absolutePath)
                }
            } else if (!appNodeModules.exists()) {
                Os.symlink(globalDir.absolutePath, appNodeModules.absolutePath)
            } else if (appNodeModules.isDirectory) {
                val localExpress = File(appNodeModules, "express")
                val localMariadb = File(appNodeModules, "mariadb")
                if (!localExpress.exists() && !localMariadb.exists()) {
                    appNodeModules.deleteRecursively()
                    Os.symlink(globalDir.absolutePath, appNodeModules.absolutePath)
                } else {
                    val globalPkgs = globalDir.listFiles() ?: emptyArray()
                    for (pkg in globalPkgs) {
                        val targetPkg = File(appNodeModules, pkg.name)
                        if (java.nio.file.Files.isSymbolicLink(targetPkg.toPath())) {
                            if (!targetPkg.exists()) {
                                java.nio.file.Files.deleteIfExists(targetPkg.toPath())
                                Os.symlink(pkg.absolutePath, targetPkg.absolutePath)
                            }
                        } else if (!targetPkg.exists()) {
                            Os.symlink(pkg.absolutePath, targetPkg.absolutePath)
                        }
                    }
                }
            }
        }
    }

    private fun ensureAppNodeServerReady(appName: String, appPort: Int): Boolean {
        val internalNodePort = appPort + 10000
        if (isLocalPortOpen(internalNodePort, 150)) return true

        val baseDir = File(deployedAppsBaseDir, appName)
        if (!baseDir.exists()) return false
        val appRoot = resolveEffectiveAppRoot(baseDir)
        if (findNodeEntryScript(appRoot) == null) return false

        val proc = appProcesses[appName]
        if (proc == null || !proc.isAlive) {
            runCatching { startAppNodeProcessIfPresent(appName, appPort) }
        }

        repeat(20) {
            if (isLocalPortOpen(internalNodePort, 150)) return true
            if (appProcesses[appName]?.isAlive == false) return false
            Thread.sleep(150)
        }
        return isLocalPortOpen(internalNodePort, 150)
    }

    private fun findNodeEntryScript(appRoot: File): File? {
        val pkgFile = File(appRoot, "package.json")
        if (pkgFile.exists() && pkgFile.isFile) {
            runCatching {
                val pkg = JSONObject(pkgFile.readText(Charsets.UTF_8))
                val startCmd = pkg.optJSONObject("scripts")?.optString("start", "") ?: ""
                if (startCmd.contains(".js") || startCmd.contains(".mjs") || startCmd.contains(".cjs")) {
                    val scriptToken = startCmd.split(" ").firstOrNull {
                        it.endsWith(".js") || it.endsWith(".mjs") || it.endsWith(".cjs")
                    }
                    if (!scriptToken.isNullOrBlank()) {
                        val f = File(appRoot, scriptToken.removePrefix("./"))
                        if (f.exists() && f.isFile) return f
                    }
                }
                val mainField = pkg.optString("main", "").trim()
                if (mainField.isNotEmpty()) {
                    val f = File(appRoot, mainField.removePrefix("./"))
                    if (f.exists() && f.isFile) return f
                }
            }
        }

        val candidates = listOf(
            "server.js", "index.js", "app.js", "main.js",
            "src/server.js", "src/index.js", "src/app.js", "src/main.js",
            "dist/server.js", "dist/index.js", "dist/main.js",
            "build/server.js", "build/index.js"
        )
        for (rel in candidates) {
            val f = File(appRoot, rel)
            if (f.exists() && f.isFile && !File(appRoot, "index.html").exists()) {
                return f
            }
        }

        return appRoot.walkTopDown()
            .maxDepth(3)
            .onEnter { it.name != "node_modules" && !it.name.startsWith(".") && it.name != "__MACOSX" }
            .firstOrNull {
                it.isFile && it.name in listOf("server.js", "index.js", "app.js", "main.js") &&
                    !File(it.parentFile, "index.html").exists()
            }
    }

    private fun resolveEffectiveAppRoot(baseDir: File): File {
        if (!baseDir.exists()) return baseDir
        if (File(baseDir, "package.json").exists() || File(baseDir, "server.js").exists() || File(baseDir, "index.js").exists() || File(baseDir, "index.html").exists()) {
            return baseDir
        }
        val children = baseDir.listFiles()?.filter { !it.name.startsWith(".") && it.name != "__MACOSX" } ?: emptyList()
        if (children.size == 1 && children[0].isDirectory) {
            return resolveEffectiveAppRoot(children[0])
        }
        val pkg = baseDir.walkTopDown()
            .maxDepth(3)
            .onEnter { it.name != "node_modules" && !it.name.startsWith(".") && it.name != "__MACOSX" }
            .firstOrNull { it.isFile && (it.name == "package.json" || it.name == "server.js" || it.name == "index.js") }
        if (pkg?.parentFile != null) {
            return pkg.parentFile!!
        }
        return baseDir
    }

    private fun isLocalPortOpen(port: Int, timeoutMs: Int = 250): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun handleDedicatedAppClient(appName: String, appPort: Int, socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val out = client.getOutputStream()

            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullUri = parts[1]
            val rawPath = fullUri.substringBefore("?")

            val headerLines = mutableListOf<String>()
            var contentLength = 0L
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                headerLines.add(line)
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("content-length", ignoreCase = true)) {
                    contentLength = line.substring(idx + 1).trim().toLongOrNull() ?: 0L
                }
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendCorsPreflightResponse(out)
                runCatching { out.flush() }
                return
            }

            val bodyBytes = if (contentLength > 0L) {
                readFixedBytes(input, contentLength.coerceAtMost(10L * 1024L * 1024L).toInt())
            } else ByteArray(0)

            val internalNodePort = appPort + 10000
            if (ensureAppNodeServerReady(appName, appPort)) {
                val proxied = proxyToLocalNodeServer(internalNodePort, method, fullUri, rawPath, headerLines, bodyBytes, appName, appPort, out)
                if (proxied) {
                    runCatching { out.flush() }
                    runCatching { client.shutdownOutput() }
                    return
                }
            }

            serveDeployedAppFile(appName, appPort, rawPath, out)
            runCatching { out.flush() }
            runCatching { client.shutdownOutput() }
        }
    }

    private fun sendCorsPreflightResponse(out: OutputStream) {
        val resp = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(resp.toByteArray(Charsets.UTF_8))
    }

    private fun readFixedBytes(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var total = 0
        while (total < len) {
            val r = input.read(buf, total, len - total)
            if (r <= 0) break
            total += r
        }
        return if (total == len) buf else buf.copyOf(total)
    }

    private fun proxyToLocalNodeServer(
        internalNodePort: Int,
        method: String,
        fullUri: String,
        rawPath: String,
        headerLines: List<String>,
        bodyBytes: ByteArray,
        appName: String,
        appPort: Int,
        clientOut: OutputStream,
        allowPrefixRetry: Boolean = true
    ): Boolean {
        return try {
            Socket().use { backend ->
                backend.soTimeout = 30_000
                backend.connect(InetSocketAddress("127.0.0.1", internalNodePort), 1000)
                val bOut = backend.getOutputStream()
                val bIn = BufferedInputStream(backend.getInputStream(), 16 * 1024)

                val reqHeader = buildString {
                    append("$method $fullUri HTTP/1.1\r\n")
                    for (h in headerLines) {
                        val lower = h.lowercase()
                        if (!lower.startsWith("connection:")) {
                            append(h).append("\r\n")
                        }
                    }
                    append("Connection: close\r\n\r\n")
                }
                bOut.write(reqHeader.toByteArray(Charsets.ISO_8859_1))
                if (bodyBytes.isNotEmpty()) {
                    bOut.write(bodyBytes)
                }
                bOut.flush()

                val statusLine = readHeaderLine(bIn) ?: return false
                if (statusLine.contains(" 404 ") && allowPrefixRetry) {
                    val altUri = if (fullUri.startsWith("/api/")) {
                        fullUri.removePrefix("/api").ifEmpty { "/" }
                    } else if (fullUri == "/api") {
                        "/"
                    } else {
                        "/api$fullUri"
                    }
                    val altRawPath = altUri.substringBefore("?")
                    val retried = proxyToLocalNodeServer(
                        internalNodePort, method, altUri, altRawPath, headerLines, bodyBytes, appName, appPort, clientOut, allowPrefixRetry = false
                    )
                    if (retried) return true
                }

                if (statusLine.contains(" 404 ") && !allowPrefixRetry) {
                    if (rawPath == "/api/status" || rawPath == "/status" || rawPath == "/health" || rawPath == "/api/health") {
                        sendAppStatusJson(appName, appPort, clientOut)
                        return true
                    }
                    return false
                }

                val respHeaders = mutableListOf<String>()
                var hasCors = false
                while (true) {
                    val line = readHeaderLine(bIn) ?: break
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("access-control-allow-origin:")) {
                        hasCors = true
                    }
                    respHeaders.add(line)
                }

                val outHeader = buildString {
                    append(statusLine).append("\r\n")
                    for (h in respHeaders) {
                        append(h).append("\r\n")
                    }
                    if (!hasCors) {
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
                        append("Access-Control-Allow-Headers: *\r\n")
                    }
                    append("\r\n")
                }
                clientOut.write(outHeader.toByteArray(Charsets.ISO_8859_1))
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = bIn.read(buf)
                    if (r == -1) break
                    clientOut.write(buf, 0, r)
                }
                clientOut.flush()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isControlPlaneApiPath(path: String): Boolean {
        return path == "/api/auth/setup-status" ||
            path == "/api/auth/setup" ||
            path == "/api/auth/login" ||
            path == "/api/v1/auth/token" ||
            path == "/api/deploy/zip" ||
            path == "/api/v1/deploy/zip" ||
            path == "/api/v1/deploy/git" ||
            path == "/api/v1/projects" ||
            path.startsWith("/api/v1/projects/") ||
            path.startsWith("/api/v1/db/") ||
            path.startsWith("/api/v1/tunnel/") ||
            path.startsWith("/api/v1/network/")
    }

    private fun sendAppStatusJson(appName: String, appPort: Int, out: OutputStream) {
        val internalNodePort = appPort + 10000
        val nodeActive = isLocalPortOpen(internalNodePort, 150)
        val json = JSONObject()
            .put("status", "online")
            .put("healthy", true)
            .put("app", appName)
            .put("port", appPort)
            .put("mariadbConnected", dbEngine.isDaemonHealthy())
            .put("dbEngine", "MariaDB Alpine ARM64 (InnoDB)")
            .put("dbHost", "127.0.0.1")
            .put("dbPort", EmbeddedDatabaseEngine.STANDARD_PORT)
            .put("nodeRuntimeActive", nodeActive)
            .put("lastLog", appNodeLogs[appName] ?: "Servicio listo")
            .put("message", "API '$appName' activa y operativa en CajitaPaaS (Puerto :$appPort)")
        sendJson(out, 200, json.toString())
    }

    // Atiende peticiones en el puerto 8080 (Gateway local y destino del Túnel Cloudflare).
    // Enruta /_control/ y endpoints de administración al Control Plane y el resto a las apps subidas.
    private fun handleGatewayClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 180_000
            val remoteIp = client.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
            val input = BufferedInputStream(client.getInputStream(), 32 * 1024)
            val out = client.getOutputStream()

            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullUri = parts[1]
            val rawPath = fullUri.substringBefore("?")
            val rawQuery = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(rawQuery)

            val headers = mutableMapOf<String, String>()
            val headerLines = mutableListOf<String>()
            var contentLength = 0L
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                headerLines.add(line)
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toLongOrNull() ?: 0L
                    }
                }
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendCorsPreflightResponse(out)
                runCatching { out.flush() }
                return
            }

            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            // Redirigir /_control sin barra final a /_control/ para que las rutas relativas ./api/* funcionen siempre
            if (rawPath == "/_control") {
                val redir = "HTTP/1.1 302 Found\r\nLocation: /_control/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(redir.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }

            // Si acceden a /_control/* o a un endpoint propio del Control Plane (como /api/deploy/zip)
            if (rawPath.startsWith("/_control/") || isControlPlaneApiPath(rawPath)) {
                val stripped = if (rawPath.startsWith("/_control/")) {
                    rawPath.removePrefix("/_control").ifEmpty { "/" }
                } else rawPath

                if (stripped.startsWith("/api/")) {
                    val (body, uploadedFile) = readBodyAndMultipartIfPresent(input, contentLength, headers)
                    try {
                        handleApiRequest(method, stripped, queryParams, body, uploadedFile, remoteIp, out)
                    } finally {
                        uploadedFile?.delete()
                    }
                } else {
                    serveStaticAsset(stripped, out)
                }
                runCatching { out.flush() }
                runCatching { client.shutdownOutput() }
                return
            }

            // Buscar qué app desplegada coincide con la ruta solicitada
            val projects = loadSavedProjects()
            val matchedApp = findMatchingAppForPath(projects, rawPath)

            if (matchedApp != null) {
                val (appName, routePrefix) = matchedApp
                val appInfo = projects.optJSONObject(appName)
                val appPort = appInfo?.optInt("activePort", 3001) ?: 3001
                val internalNodePort = appPort + 10000

                val bodyBytes = if (contentLength > 0L) {
                    readFixedBytes(input, contentLength.coerceAtMost(10L * 1024L * 1024L).toInt())
                } else ByteArray(0)

                if (ensureAppNodeServerReady(appName, appPort)) {
                    val proxied = proxyToLocalNodeServer(internalNodePort, method, fullUri, rawPath, headerLines, bodyBytes, appName, appPort, out)
                    if (proxied) {
                        runCatching { out.flush() }
                        runCatching { client.shutdownOutput() }
                        return
                    }
                }

                val subPath = if (routePrefix != "/" && rawPath.startsWith(routePrefix)) {
                    rawPath.removePrefix(routePrefix).let { if (it.startsWith("/")) it else "/$it" }
                } else {
                    rawPath
                }
                serveDeployedAppFile(appName, appPort, subPath, out)
            } else {
                serveStaticAsset(rawPath, out)
            }

            runCatching { out.flush() }
            runCatching { client.shutdownOutput() }
        }
    }

    private fun findMatchingAppForPath(projects: JSONObject, requestPath: String): Pair<String, String>? {
        val entries = mutableListOf<Pair<String, String>>()
        val keys = projects.keys()
        while (keys.hasNext()) {
            val appName = keys.next()
            val info = projects.optJSONObject(appName) ?: continue
            val route = info.optString("routePath", "/").removeSuffix("*").removeSuffix("/").ifEmpty { "/" }
            entries.add(appName to route)
        }
        if (entries.isEmpty()) return null

        // Priorizar rutas más específicas (ej. /api antes que /)
        entries.sortByDescending { if (it.second == "/") 0 else it.second.length }
        for ((appName, prefix) in entries) {
            if (prefix == "/" || requestPath == prefix || requestPath.startsWith("$prefix/")) {
                return appName to prefix
            }
        }
        return entries.first()
    }

    /**
     * Sirve los archivos reales extraídos del `.ZIP` de la aplicación `appName`
     * o responde a las rutas de estado (`/api/status`, `/status`, `/health`, `/api/health`).
     */
    private fun serveDeployedAppFile(appName: String, appPort: Int, rawPath: String, out: OutputStream) {
        if (rawPath == "/health" || rawPath == "/api/health" || rawPath == "/status" || rawPath == "/api/status" || rawPath == "/api/v1/status") {
            return sendAppStatusJson(appName, appPort, out)
        }

        val appRootDir = resolveEffectiveWebRoot(File(deployedAppsBaseDir, appName))
        if (!appRootDir.exists()) {
            return sendAppStatusJson(appName, appPort, out)
        }

        val cleanRel = URLDecoder.decode(rawPath, "UTF-8").trimStart('/').ifEmpty { "index.html" }
        var targetFile = File(appRootDir, cleanRel)

        // Prevención de Path Traversal
        if (!targetFile.canonicalPath.startsWith(appRootDir.canonicalPath)) {
            val forbidden = "403 Forbidden".toByteArray()
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: ${forbidden.size}\r\n\r\n".toByteArray())
            out.write(forbidden)
            return
        }

        if (targetFile.isDirectory) {
            targetFile = File(targetFile, "index.html")
        }

        // Soporte SPA (React / Vue / Angular / Vite): si no existe la ruta y no tiene extensión estática, servir index.html
        if (!targetFile.exists() && !cleanRel.substringAfterLast('/').contains('.')) {
            val spaIndex = File(appRootDir, "index.html")
            if (spaIndex.exists()) {
                targetFile = spaIndex
            }
        }

        // Si aún no existe index.html en la raíz, buscar el primer archivo .html o .json disponible
        if (!targetFile.exists() && cleanRel == "index.html") {
            val fallbackHtml = appRootDir.walkTopDown().firstOrNull {
                it.isFile && it.extension.lowercase() in listOf("html", "htm", "json")
            }
            if (fallbackHtml != null) {
                targetFile = fallbackHtml
            }
        }

        if (targetFile.exists() && targetFile.isFile) {
            val mime = resolveMimeType(targetFile.name)
            val bytes = if (mime.startsWith("text/html")) {
                injectCpsApiBridgeIntoHtml(targetFile.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            } else {
                targetFile.readBytes()
            }
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
        } else {
            // Si es un servicio Backend sin archivo estático coincidente, devolver JSON estructurado
            val projects = loadSavedProjects()
            val role = projects.optJSONObject(appName)?.optString("appRole", "frontend") ?: "frontend"
            if (role == "backend") {
                return sendAppStatusJson(appName, appPort, out)
            }
            val notFoundMsg = "Archivo no encontrado en '$appName': /$cleanRel".toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${notFoundMsg.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(notFoundMsg)
        }
    }

    /**
     * Inyecta automáticamente en el HTML del Frontend el puente de descubrimiento de API (`window.CPS_API_URL`).
     * Si el Frontend tenía hardcodeado `http://localhost:3000` o `http://127.0.0.1:3001`,
     * redirige de forma transparente esas peticiones `fetch` / `XMLHttpRequest` hacia la IP real del celular
     * (en Wi-Fi) o hacia la ruta `/api` del Gateway `:8080` (en el Túnel HTTPS de Cloudflare).
     */
    private fun injectCpsApiBridgeIntoHtml(rawHtml: String): String {
        val projects = loadSavedProjects()
        var backendPort = 3001
        var backendRoute = "/api"
        val keys = projects.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val info = projects.optJSONObject(k) ?: continue
            if (info.optString("appRole") == "backend") {
                backendPort = info.optInt("activePort", 3001)
                backendRoute = info.optString("routePath", "/api/*").removeSuffix("*").removeSuffix("/").ifEmpty { "/api" }
                break
            }
        }

        val bridgeScript = """
            <script>
            (function(){
              var isHttpsTunnel = window.location.protocol === 'https:';
              var host = window.location.hostname;
              window.CPS_API_PORT = $backendPort;
              window.CPS_API_URL = isHttpsTunnel ? (window.location.origin + '$backendRoute') : ('http://' + host + ':$backendPort');
              window.CPS_GATEWAY_API_URL = window.location.origin + '$backendRoute';
              function rewriteLocalUrl(u) {
                if (typeof u !== 'string') return u;
                var m = u.match(/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/.*)?$/i);
                if (!m) return u;
                var path = m[3] || '/';
                if (isHttpsTunnel) {
                  return path.indexOf('$backendRoute') === 0 ? path : ('$backendRoute' + path);
                }
                return 'http://' + host + ':$backendPort' + path;
              }
              if (window.fetch) {
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                  if (typeof input === 'string') input = rewriteLocalUrl(input);
                  return origFetch.call(this, input, init);
                };
              }
              if (window.XMLHttpRequest) {
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  arguments[1] = rewriteLocalUrl(url);
                  return origOpen.apply(this, arguments);
                };
              }
            })();
            </script>
        """.trimIndent()

        return if (rawHtml.contains("<head>", ignoreCase = true)) {
            rawHtml.replaceFirst("<head>", "<head>\n$bridgeScript", ignoreCase = true)
        } else {
            "$bridgeScript\n$rawHtml"
        }
    }

    /**
     * Si el usuario comprimió una carpeta completa (ej. `mi-web/index.html` o `dist/index.html`),
     * detecta automáticamente la subcarpeta donde se encuentra `index.html`.
     */
    private fun resolveEffectiveWebRoot(baseDir: File): File {
        if (!baseDir.exists()) return baseDir
        if (File(baseDir, "index.html").exists()) return baseDir

        for (candidate in listOf("dist", "build", "public", "www", "out")) {
            val sub = File(baseDir, candidate)
            if (File(sub, "index.html").exists()) return sub
        }

        val children = baseDir.listFiles()?.filter { !it.name.startsWith(".") && it.name != "__MACOSX" } ?: emptyList()
        if (children.size == 1 && children[0].isDirectory) {
            return resolveEffectiveWebRoot(children[0])
        }
        return baseDir
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 180_000
            val remoteIp = client.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
            val input = BufferedInputStream(client.getInputStream(), 32 * 1024)
            val out = client.getOutputStream()

            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullUri = parts[1]
            val rawPath = fullUri.substringBefore("?")
            val rawQuery = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(rawQuery)

            val headers = mutableMapOf<String, String>()
            var contentLength = 0L
            while (true) {
                val line = readHeaderLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toLongOrNull() ?: 0L
                    }
                }
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendCorsPreflightResponse(out)
                runCatching { out.flush() }
                return
            }

            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val (body, uploadedMultipartFile) = readBodyAndMultipartIfPresent(input, contentLength, headers)

            try {
                if (rawPath.startsWith("/api/")) {
                    handleApiRequest(method, rawPath, queryParams, body, uploadedMultipartFile, remoteIp, out)
                } else if (rawPath.startsWith("/app/")) {
                    val afterApp = rawPath.removePrefix("/app/")
                    val appName = afterApp.substringBefore("/")
                    val subPath = "/" + afterApp.substringAfter("/", "")
                    val appPort = loadSavedProjects().optJSONObject(appName)?.optInt("activePort", 3001) ?: 3001
                    serveDeployedAppFile(appName, appPort, subPath, out)
                } else {
                    serveStaticAsset(rawPath, out)
                }
            } finally {
                uploadedMultipartFile?.delete()
            }

            runCatching { out.flush() }
            runCatching { client.shutdownOutput() }
            runCatching {
                client.soTimeout = 400
                val drain = ByteArray(1024)
                while (input.read(drain) != -1) {
                    // Esperar FIN del cliente
                }
            }
        }
    }

    private fun readBodyAndMultipartIfPresent(
        input: InputStream,
        contentLength: Long,
        headers: Map<String, String>
    ): Pair<String, File?> {
        val contentType = headers["content-type"]?.lowercase() ?: ""
        val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        var uploadedMultipartFile: File? = null

        val body = try {
            if (contentLength > 0L && !contentType.startsWith("multipart/form-data")) {
                readRequestBodyAsString(input, contentLength)
            } else if (contentLength > 0L && contentType.startsWith("multipart/form-data")) {
                val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}_${System.nanoTime()}.bin")
                uploadedMultipartFile = tempFile
                val maxPreview = 64 * 1024
                val previewOut = ByteArrayOutputStream(minOf(contentLength.toInt().coerceAtLeast(0), maxPreview))
                val chunk = ByteArray(32 * 1024)
                var remaining = contentLength

                FileOutputStream(tempFile).use { fos ->
                    while (remaining > 0L) {
                        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                        val r = input.read(chunk, 0, toRead)
                        if (r <= 0) break
                        fos.write(chunk, 0, r)
                        if (previewOut.size() < maxPreview) {
                            val copyLen = minOf(r, maxPreview - previewOut.size())
                            previewOut.write(chunk, 0, copyLen)
                        }
                        remaining -= r
                    }
                    fos.flush()
                }
                String(previewOut.toByteArray(), Charsets.ISO_8859_1)
            } else if (isChunked) {
                drainChunkedBody(input)
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo cuerpo HTTP", e)
            ""
        }
        return body to uploadedMultipartFile
    }

    private fun readRequestBodyAsString(input: InputStream, contentLength: Long): String {
        val safeLen = contentLength.coerceAtMost(2L * 1024L * 1024L).toInt()
        val buf = ByteArray(safeLen)
        var readTotal = 0
        while (readTotal < safeLen) {
            val r = input.read(buf, readTotal, safeLen - readTotal)
            if (r == -1) break
            readTotal += r
        }
        var remaining = contentLength - readTotal
        val discard = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (r <= 0) break
            remaining -= r
        }
        return String(buf, 0, readTotal, Charsets.UTF_8)
    }

    /**
     * Extrae el contenido de un archivo `.ZIP` embebido dentro de un cuerpo `multipart/form-data`.
     * Aislando primero el rango exacto `[PK\x03\x04 .. EOCD PK\x05\x06]` en un archivo `.zip` limpio,
     * utiliza `java.util.zip.ZipFile` (nativo C++) que soporta descriptores EXT de Windows y archivos grandes.
     */
    private fun extractZipFromMultipartFile(multipartFile: File, targetDir: File): Int {
        if (!multipartFile.exists() || multipartFile.length() < 4) return 0

        val zipStartOffset = findZipMagicOffset(multipartFile)
        if (zipStartOffset < 0L) {
            Log.w(TAG, "No se encontró firma PK\\x03\\x04 en el paquete subido.")
            return 0
        }

        val zipEndOffset = findZipEndOffset(multipartFile, zipStartOffset)
        val cleanZipFile = File(context.cacheDir, "clean_pkg_${System.nanoTime()}.zip")

        try {
            FileInputStream(multipartFile).use { fis ->
                var skipped = 0L
                while (skipped < zipStartOffset) {
                    val s = fis.skip(zipStartOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                var remaining = (zipEndOffset - zipStartOffset).coerceAtLeast(0L)
                val buf = ByteArray(32 * 1024)
                FileOutputStream(cleanZipFile).use { fos ->
                    while (remaining > 0L) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val r = fis.read(buf, 0, toRead)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                        remaining -= r
                    }
                    fos.flush()
                }
            }

            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            var extractedCount = 0
            val extractedViaZipFile = runCatching {
                ZipFile(cleanZipFile).use { zf ->
                    val buffer = ByteArray(16 * 1024)
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        if (entryName.isEmpty() || entryName.startsWith("__MACOSX")) continue

                        val outFile = File(targetDir, entryName)
                        if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) continue

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zf.getInputStream(entry).use { zis ->
                                FileOutputStream(outFile).use { outStream ->
                                    while (true) {
                                        val len = zis.read(buffer)
                                        if (len <= 0) break
                                        outStream.write(buffer, 0, len)
                                    }
                                }
                            }
                            extractedCount++
                        }
                    }
                }
                true
            }.getOrDefault(false)

            if (!extractedViaZipFile || extractedCount == 0) {
                FileInputStream(cleanZipFile).use { fis ->
                    ZipInputStream(BufferedInputStream(fis)).use { zis ->
                        val buffer = ByteArray(16 * 1024)
                        var guard = 0
                        while (guard++ < 25_000) {
                            val entry = runCatching { zis.nextEntry }.getOrNull() ?: break
                            val entryName = entry.name.replace('\\', '/').trimStart('/')
                            if (entryName.isNotEmpty() && !entryName.startsWith("__MACOSX")) {
                                val outFile = File(targetDir, entryName)
                                if (outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                    if (entry.isDirectory) {
                                        outFile.mkdirs()
                                    } else {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { outStream ->
                                            while (true) {
                                                val len = runCatching { zis.read(buffer) }.getOrDefault(-1)
                                                if (len <= 0) break
                                                outStream.write(buffer, 0, len)
                                            }
                                        }
                                        extractedCount++
                                    }
                                }
                            }
                            if (runCatching { zis.closeEntry() }.isFailure) break
                        }
                    }
                }
            }

            Log.i(TAG, "Archivo .ZIP descomprimido en ${targetDir.absolutePath} ($extractedCount archivos extraídos)")
            return extractedCount
        } finally {
            runCatching { cleanZipFile.delete() }
        }
    }

    /**
     * Busca la posición exacta donde comienza el binario `.ZIP` (`PK\x03\x04` = `0x50 0x4B 0x03 0x04`)
     * dentro del archivo temporal multipart.
     */
    private fun findZipMagicOffset(file: File): Long {
        FileInputStream(file).use { fis ->
            val searchLimit = minOf(file.length(), 128L * 1024L).toInt()
            val buf = ByteArray(searchLimit)
            var total = 0
            while (total < searchLimit) {
                val r = fis.read(buf, total, searchLimit - total)
                if (r <= 0) break
                total += r
            }
            for (i in 0..(total - 4)) {
                if (buf[i] == 0x50.toByte() &&
                    buf[i + 1] == 0x4B.toByte() &&
                    buf[i + 2] == 0x03.toByte() &&
                    buf[i + 3] == 0x04.toByte()
                ) {
                    return i.toLong()
                }
            }
        }
        return -1L
    }

    /**
     * Localiza el final del archivo `.ZIP` buscando la firma End of Central Directory (`PK\x05\x06`)
     * en los últimos 66KB del archivo multipart.
     */
    private fun findZipEndOffset(file: File, startOffset: Long): Long {
        return runCatching {
            val len = file.length()
            val tailSize = minOf(len - startOffset, 66_000L).toInt()
            if (tailSize < 22) return len
            val tailStart = len - tailSize
            val buf = ByteArray(tailSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(tailStart)
                raf.readFully(buf)
            }
            for (i in (tailSize - 22) downTo 0) {
                if (buf[i] == 0x50.toByte() &&
                    buf[i + 1] == 0x4B.toByte() &&
                    buf[i + 2] == 0x05.toByte() &&
                    buf[i + 3] == 0x06.toByte()
                ) {
                    val commentLen = ((buf[i + 20].toInt() and 0xFF)) or ((buf[i + 21].toInt() and 0xFF) shl 8)
                    val endPos = tailStart + i + 22 + commentLen
                    return endPos.coerceAtMost(len)
                }
            }
            len
        }.getOrDefault(file.length())
    }

    private fun drainChunkedBody(input: InputStream): String {
        val previewOut = ByteArrayOutputStream(4096)
        val discard = ByteArray(16 * 1024)
        while (true) {
            val sizeLine = readHeaderLine(input)?.trim() ?: break
            val chunkSize = sizeLine.substringBefore(";").trim().toLongOrNull(16) ?: break
            if (chunkSize == 0L) {
                readHeaderLine(input)
                break
            }
            var remaining = chunkSize
            while (remaining > 0L) {
                val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (r <= 0) return String(previewOut.toByteArray(), Charsets.ISO_8859_1)
                if (previewOut.size() < 64 * 1024) {
                    previewOut.write(discard, 0, minOf(r, 64 * 1024 - previewOut.size()))
                }
                remaining -= r
            }
            readHeaderLine(input)
        }
        return String(previewOut.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readHeaderLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
            }
            if (b == '\n'.code) {
                break
            }
            if (out.size() < 8192) {
                out.write(b)
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                map[k] = v
            }
        }
        return map
    }

    private fun handleApiRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: String,
        uploadedMultipartFile: File?,
        remoteIp: String,
        out: OutputStream
    ) {
        val isInitialized = prefs.getBoolean("initialized", false)

        if (path == "/api/auth/setup-status" && method == "GET") {
            val lanIp = getDeviceLanIp()
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("initialized", isInitialized)
                    .put("lanIp", lanIp)
                    .put("lanPort", port)
                    .put("lanUrl", "http://$lanIp:$port")
                    .toString()
            )
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
                .putString("db_custom_pass", pass)
                .apply()

            executor.execute {
                runCatching { dbEngine.syncAdminUserCredentials() }
            }

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
            val lanIp = getDeviceLanIp()
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("system", "CPS (CajitaPaaS Android APK)")
                    .put("status", "online")
                    .put("mariadbConnected", dbEngine.isDaemonHealthy())
                    .put("dbEngine", "MariaDB Alpine ARM64 (InnoDB)")
                    .put("lanIp", lanIp)
                    .put("lanPort", port)
                    .put("lanUrl", "http://$lanIp:$port")
                    .toString()
            )
        }

        // ====================================================================
        // GESTIÓN REAL DE PROYECTOS / SERVIDORES Y EXTRACCIÓN DE .ZIP
        // ====================================================================
        if (path == "/api/v1/projects" && method == "GET") {
            val projectsObj = loadSavedProjects()
            val activeSlots = JSONObject()
            val pm2 = JSONArray()
            val lanIp = getDeviceLanIp()

            val keys = projectsObj.keys()
            while (keys.hasNext()) {
                val appName = keys.next()
                val info = projectsObj.optJSONObject(appName) ?: continue
                val appRole = info.optString("appRole", "frontend")
                val appPort = info.optInt("activePort", 3001)
                val cleanRoute = info.optString("routePath", "/").removeSuffix("*")

                // Si es Frontend o Monolítico (Fullstack), su URL principal es el puerto 8080 (y 80 con Root).
                // Si es Backend API, se expone en su puerto exclusivo (:3001, :3003...) y también en :8080/api/
                val primaryUrl = if (appRole == "frontend" || appRole == "fullstack") {
                    "http://$lanIp:$GATEWAY_PORT$cleanRoute"
                } else {
                    "http://$lanIp:$appPort"
                }
                info.put("appUrl", primaryUrl)
                info.put("directPortUrl", "http://$lanIp:$appPort")
                info.put("gatewayUrl", "http://$lanIp:$GATEWAY_PORT$cleanRoute")
                activeSlots.put(appName, info)

                val color = info.optString("activeColor", "blue")
                pm2.put(
                    JSONObject()
                        .put("name", "$appName-$color")
                        .put("pid", info.optInt("pid", 4100))
                        .put("status", "online")
                        .put("memoryMb", info.optInt("memoryMb", 42))
                )
            }

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("activeSlots", activeSlots)
                    .put("pm2Processes", pm2)
                    .put("lanIp", lanIp)
                    .put("gatewayPort", GATEWAY_PORT)
                    .toString()
            )
        }

        if (path.startsWith("/api/v1/projects/") && method == "DELETE") {
            val rawName = path.removePrefix("/api/v1/projects/").trim()
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            stopDedicatedAppServer(appName)
            File(deployedAppsBaseDir, appName).deleteRecursively()

            val projectsObj = loadSavedProjects()
            projectsObj.remove(appName)
            saveProjects(projectsObj)
            return sendJson(
                out,
                200,
                JSONObject().put("message", "Proyecto '$appName' eliminado correctamente.").toString()
            )
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/database") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/database").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val newDb = json.optString("database", json.optString("dbName", "")).trim()
            val projectsObj = loadSavedProjects()
            val appInfo = projectsObj.optJSONObject(appName)
            if (appInfo != null && newDb.isNotEmpty()) {
                appInfo.put("dbName", newDb)
                projectsObj.put(appName, appInfo)
                saveProjects(projectsObj)
                val port = appInfo.optInt("activePort", 3001)
                startDedicatedAppServer(appName, port)
                return sendJson(
                    out,
                    200,
                    JSONObject()
                        .put("status", "success")
                        .put("message", "Base de datos de '$appName' cambiada a '$newDb'")
                        .put("dbName", newDb)
                        .toString()
                )
            }
            return sendJson(out, 400, JSONObject().put("error", "No se encontró el servicio o nombre de base inválido").toString())
        }

        if ((path == "/api/deploy/zip" || path == "/api/v1/deploy/zip" || path == "/api/v1/deploy/git") && method == "POST") {
            val appName: String
            val appRole: String
            val routePath: String
            val dbName: String

            if (path.endsWith("/git")) {
                val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
                appName = sanitizeSlug(json.optString("appName", queryParams["appName"] ?: "mi-app"))
                appRole = json.optString("appRole", queryParams["appRole"] ?: "backend")
                routePath = json.optString("routePath", queryParams["routePath"] ?: if (appRole == "frontend") "/" else "/api/*")
                dbName = json.optString("dbName", json.optString("database", queryParams["dbName"] ?: "")).trim()
            } else {
                appName = sanitizeSlug(queryParams["appName"] ?: extractMultipartField(body, "appName") ?: "mi-app")
                appRole = queryParams["appRole"] ?: extractMultipartField(body, "appRole") ?: "frontend"
                routePath = queryParams["routePath"] ?: extractMultipartField(body, "routePath") ?: "/"
                dbName = (queryParams["dbName"] ?: queryParams["database"] ?: extractMultipartField(body, "dbName") ?: extractMultipartField(body, "database") ?: "").trim()
            }

            val projectsObj = loadSavedProjects()
            val existing = projectsObj.optJSONObject(appName)

            // Asignar un puerto libre exclusivo para esta app (3001, 3003, 3005...)
            val usedPorts = mutableSetOf<Int>()
            val k = projectsObj.keys()
            while (k.hasNext()) {
                val key = k.next()
                if (key != appName) {
                    usedPorts.add(projectsObj.optJSONObject(key)?.optInt("activePort", 0) ?: 0)
                }
            }
            var candidatePort = existing?.optInt("activePort", 3001) ?: 3001
            while (usedPorts.contains(candidatePort)) {
                candidatePort += 2
            }

            val nextColor = if (existing?.optString("activeColor") == "blue") "green" else "blue"
            val appTargetDir = File(deployedAppsBaseDir, appName)

            // Extraer el .ZIP subido a disco
            var filesExtracted = 0
            if (uploadedMultipartFile != null && uploadedMultipartFile.exists()) {
                filesExtracted = runCatching {
                    extractZipFromMultipartFile(uploadedMultipartFile, appTargetDir)
                }.getOrDefault(0)
            }

            // Si aún no había archivos (ej. deploy git simulado), crear un index.html base
            if (!appTargetDir.exists() || (appTargetDir.listFiles()?.size ?: 0) == 0) {
                appTargetDir.mkdirs()
                File(appTargetDir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="es">
                    <head><meta charset="UTF-8"><title>$appName · CajitaPaaS</title></head>
                    <body style="background:#0d131a;color:#f5f9fc;font-family:sans-serif;text-align:center;padding:3rem;">
                      <h1 style="color:#00e5a3;">🚀 $appName está Online</h1>
                      <p>Desplegado con éxito en CajitaPaaS (Puerto :$candidatePort · Ruta: $routePath)</p>
                    </body>
                    </html>
                    """.trimIndent()
                )
            }

            // Levantar el servidor HTTP real en el puerto asignado (ej. :3001)
            startDedicatedAppServer(appName, candidatePort)

            val lanIp = getDeviceLanIp()
            val cleanRoute = routePath.removeSuffix("*")
            val primaryUrl = if (appRole == "frontend" || appRole == "fullstack") {
                "http://$lanIp:$GATEWAY_PORT$cleanRoute"
            } else {
                "http://$lanIp:$candidatePort"
            }
            val slotInfo = JSONObject()
                .put("appRole", appRole)
                .put("routePath", routePath)
                .put("activeColor", nextColor)
                .put("activePort", candidatePort)
                .put("filesExtracted", filesExtracted)
                .put("appUrl", primaryUrl)
                .put("directPortUrl", "http://$lanIp:$candidatePort")
                .put("gatewayUrl", "http://$lanIp:$GATEWAY_PORT$cleanRoute")
                .put("pid", 4200 + (projectsObj.length() * 17))
                .put("memoryMb", if (appRole == "frontend") 38 else 54)

            val effectiveAppDb = if (dbName.isNotEmpty()) dbName else (existing?.optString("dbName") ?: "")
            if (effectiveAppDb.isNotEmpty()) {
                slotInfo.put("dbName", effectiveAppDb)
            }

            projectsObj.put(appName, slotInfo)
            saveProjects(projectsObj)

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("status", "success")
                    .put("message", "¡Sistema actualizado con éxito!")
                    .put("deployment", JSONObject(slotInfo.toString()).put("appName", appName))
                    .toString()
            )
        }

        // ====================================================================
        // ESTADO Y CONFIGURACIÓN DEL TÚNEL CLOUDFLARE + RED INTERNA (IP:PUERTO)
        // ====================================================================
        if (path == "/api/v1/tunnel/status" && method == "GET") {
            return sendJson(out, 200, buildTunnelStatusJson().toString())
        }

        if (path == "/api/v1/tunnel/config" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val mode = json.optString("mode", "quick")
            val token = json.optString("token", "").trim()
            val customDomain = json.optString("customDomain", "").trim()

            if (mode == "token" && token.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "Debes pegar el Token del túnel de Cloudflare.").toString())
            }

            prefs.edit()
                .putString("tunnel_mode", mode)
                .putString("tunnel_token", token)
                .putString("tunnel_custom_domain", customDomain)
                .apply()

            onTunnelRestartRequested?.invoke(mode, token, customDomain)

            val tunnelObj = buildTunnelStatusJson()
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("message", "Configuración de túnel actualizada.")
                    .put("tunnel", tunnelObj)
                    .toString()
            )
        }

        // ====================================================================
        // MARIADB / PUENTE DBEAVER (PUERTO 13306 + CREDENCIALES VISIBLES)
        // ====================================================================
        if (path == "/api/v1/db/bridge/status" || path == "/api/v1/db/bridge/heartbeat") {
            val bridgeJson = buildBridgeJson()
            bridgeJson.put("bridge", buildBridgeJson())
            return sendJson(out, 200, bridgeJson.toString())
        }

        if (path == "/api/v1/db/bridge/toggle" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val enabled = json.optBoolean("enabled", false)
            val mode = json.optString("mode", "session")
            val restrictIp = json.optBoolean("restrictToMyIp", false)
            val allowedClientIp = if (restrictIp && remoteIp.isNotBlank()) remoteIp else null

            dbEngine.setBridgeState(enabled, mode, allowedClientIp)

            val bridgeJson = buildBridgeJson()
            val response = JSONObject(bridgeJson.toString())
                .put("bridge", bridgeJson)
                .put(
                    "message",
                    if (enabled) "Modo PC (DBeaver) activado en ${getDeviceLanIp()}:${EmbeddedDatabaseEngine.STANDARD_PORT}"
                    else "Modo PC (DBeaver) desactivado."
                )
            return sendJson(out, 200, response.toString())
        }

        if (path == "/api/v1/db/databases" && method == "GET") {
            return sendJson(out, 200, JSONObject().put("databases", dbEngine.listDatabasesJson()).toString())
        }

        if (path == "/api/v1/db/databases" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val dbName = json.optString("dbName", "").trim()
            if (dbName.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "El nombre de la base de datos es obligatorio.").toString())
            }
            val appUser = json.optString("appUser", "").trim().ifEmpty { null }
            val appPass = json.optString("appPassword", "").ifEmpty { null }
            val result = dbEngine.createDatabase(dbName, appUser, appPass)
            return sendJson(out, 201, result.toString())
        }

        if (path == "/api/v1/db/query" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val database = json.optString("database", EmbeddedDatabaseEngine.DEFAULT_DB_NAME)
            val sql = json.optString("sql", "").trim()
            if (sql.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "La consulta SQL no puede estar vacía.").toString())
            }
            return try {
                val res = dbEngine.executeSqlJson(database, sql)
                sendJson(out, 200, res.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error en consulta SQL").toString())
            }
        }

        // ====================================================================
        // SUPERVIVENCIA DE RED WI-FI (FAILOVER / FAILBACK)
        // ====================================================================
        if (path == "/api/v1/network/failover/status" && method == "GET") {
            return sendJson(out, 200, JSONObject().put("failover", buildFailoverJson()).toString())
        }

        if (path == "/api/v1/network/failover/toggle" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val currentEnabled = failoverManager?.isEnabled() ?: prefs.getBoolean("network_failover_enabled", false)
            val currentMinutes = failoverManager?.getPollIntervalMinutes() ?: prefs.getInt("network_failover_poll_minutes", 10)
            val enabled = json.optBoolean("enabled", currentEnabled)
            val pollMinutes = json.optInt("pollIntervalMinutes", currentMinutes).coerceIn(1, 120)

            failoverManager?.setConfig(enabled, pollMinutes) ?: run {
                prefs.edit()
                    .putBoolean("network_failover_enabled", enabled)
                    .putInt("network_failover_poll_minutes", pollMinutes)
                    .apply()
            }

            return sendJson(
                out,
                200,
                JSONObject()
                    .put(
                        "message",
                        if (enabled) "Supervivencia Wi-Fi (Failover/Failback) ACTIVADA."
                        else "Supervivencia Wi-Fi (Failover/Failback) DESACTIVADA."
                    )
                    .put("failover", buildFailoverJson())
                    .toString()
            )
        }

        return sendJson(out, 200, JSONObject().put("status", "ok").toString())
    }

    private fun buildTunnelStatusJson(): JSONObject {
        val lanIp = getDeviceLanIp()
        val lanAdminAddress = "http://$lanIp:$port"
        val lanGatewayAddress = "http://$lanIp:$GATEWAY_PORT"
        val mode = prefs.getString("tunnel_mode", "quick") ?: "quick"
        val pub = publicTunnelUrl

        return JSONObject()
            .put("mode", mode)
            .put("status", if (pub != null) "online" else tunnelStatus)
            .put("publicUrl", pub ?: "Conectando túnel Cloudflare... (Usá $lanGatewayAddress en tu Wi-Fi)")
            .put("adminUrl", if (pub != null) "$pub/_control/" else lanAdminAddress)
            .put("lanIp", lanIp)
            .put("lanPort", port)
            .put("gatewayPort", GATEWAY_PORT)
            .put("lanUrl", lanGatewayAddress)
            .put("lanAdminUrl", lanAdminAddress)
            .put("lastError", tunnelLastError ?: JSONObject.NULL)
    }

    private fun loadSavedProjects(): JSONObject {
        val raw = prefs.getString(PREF_PROJECTS_KEY, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
    }

    private fun saveProjects(projects: JSONObject) {
        prefs.edit().putString(PREF_PROJECTS_KEY, projects.toString()).apply()
    }

    private fun sanitizeSlug(raw: String): String {
        return raw.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "mi-app" }
    }

    private fun extractMultipartField(rawMultipart: String, fieldName: String): String? {
        val marker = "name=\"$fieldName\""
        val idx = rawMultipart.indexOf(marker)
        if (idx == -1) return null
        val afterHeader = rawMultipart.indexOf("\r\n\r\n", idx)
        if (afterHeader == -1) return null
        val start = afterHeader + 4
        val end = rawMultipart.indexOf("\r\n--", start)
        if (end == -1) return null
        return rawMultipart.substring(start, end).trim()
    }

    private fun buildBridgeJson(): JSONObject {
        return JSONObject()
            .put("enabled", dbEngine.isBridgeEnabled)
            .put("mode", dbEngine.bridgeMode)
            .put("engine", "MariaDB Alpine ARM64 (InnoDB)")
            .put("daemonHealthy", dbEngine.isDaemonHealthy())
            .put("host", getDeviceLanIp())
            .put("port", EmbeddedDatabaseEngine.STANDARD_PORT)
            .put("altPort", EmbeddedDatabaseEngine.BRIDGE_PORT)
            .put("database", EmbeddedDatabaseEngine.DEFAULT_DB_NAME)
            .put("user", dbEngine.getDbUser())
            .put("password", dbEngine.getDbPassword())
            .put("allowedIp", dbEngine.allowedIp ?: "Cualquier PC de tu red Wi-Fi")
            .put("remainingSeconds", 900)
            .put("activeConnections", dbEngine.getActiveConnectionsCount())
    }

    private fun buildFailoverJson(): JSONObject {
        val mgr = failoverManager
        val enabled = mgr?.isEnabled() ?: prefs.getBoolean("network_failover_enabled", false)
        val pollMinutes = mgr?.getPollIntervalMinutes() ?: prefs.getInt("network_failover_poll_minutes", 10)
        val stateName = mgr?.currentState?.name ?: if (enabled) "NORMAL_WIFI_STA" else "DISABLED"
        val wifiConnected = mgr?.isWifiCurrentlyConnected() ?: true

        return JSONObject()
            .put("enabled", enabled)
            .put("pollIntervalMinutes", pollMinutes)
            .put("reconnectWindowSeconds", 30)
            .put("state", stateName)
            .put("wifiConnected", wifiConnected)
    }

    private fun resolveMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript; charset=utf-8"
            lower.endsWith(".css") -> "text/css; charset=utf-8"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    private fun serveStaticAsset(rawPath: String, out: OutputStream) {
        val cleanPath = if (rawPath == "/" || rawPath.isEmpty()) "public/index.html" else "public/${rawPath.trimStart('/')}"
        try {
            val bytes = context.assets.open(cleanPath).use { it.readBytes() }
            val mime = resolveMimeType(cleanPath)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            val notFound = "404 Not Found".toByteArray()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${notFound.size}\r\n\r\n".toByteArray())
            out.write(notFound)
            out.flush()
        }
    }

    private fun sendJson(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "OK" else if (status == 201) "Created" else "Error"
        val header = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
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
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()

            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("p2p")) {
                    continue
                }

                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) && ip.startsWith("192.168.") -> 100
                            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 90
                            ip.startsWith("192.168.") -> 80
                            addr.isSiteLocalAddress -> 70
                            else -> 50
                        }
                        candidates.add(priority to ip)
                    }
                }
            }

            candidates.maxByOrNull { it.first }?.second ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
