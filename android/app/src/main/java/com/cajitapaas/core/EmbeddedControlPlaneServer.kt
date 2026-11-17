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
        private const val MAX_LOG_LINES_PER_APP = 300
        private val BUILTIN_NODE_MODULES = setOf(
            "assert", "async_hooks", "buffer", "child_process", "cluster", "console",
            "constants", "crypto", "dgram", "diagnostics_channel", "dns", "domain",
            "events", "fs", "http", "http2", "https", "inspector", "module", "net",
            "os", "path", "perf_hooks", "process", "punycode", "querystring", "readline",
            "repl", "stream", "string_decoder", "sys", "timers", "tls", "trace_events",
            "tty", "url", "util", "v8", "vm", "wasi", "worker_threads", "zlib"
        )
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var gatewaySocket: ServerSocket? = null
    private val appServerSockets = ConcurrentHashMap<String, ServerSocket>()
    private val appProcesses = ConcurrentHashMap<String, Process>()
    private val appNodeLogs = ConcurrentHashMap<String, String>()
    private val appLogLines = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val intentionallyStoppedApps = ConcurrentHashMap.newKeySet<String>()
    private val appRestartCounts = ConcurrentHashMap<String, Int>()
    private val appWatchdogState = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool()

    private fun getDefaultMaxRamMb(): Int {
        return prefs.getInt("expert_default_max_ram_mb", 256).coerceIn(64, 2048)
    }

    private fun isWatchdogEnabled(): Boolean {
        return prefs.getBoolean("expert_watchdog_enabled", true)
    }

    private fun getWatchdogMaxRestarts(): Int {
        return prefs.getInt("expert_watchdog_max_restarts", 5).coerceIn(1, 20)
    }

    private fun appendAppLog(appName: String, rawLine: String) {
        val clean = rawLine.trimEnd()
        if (clean.isEmpty()) return
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val formatted = if (clean.startsWith("[")) clean else "[$ts] $clean"
        val deque = appLogLines.getOrPut(appName) { ArrayDeque(MAX_LOG_LINES_PER_APP) }
        synchronized(deque) {
            if (deque.size >= MAX_LOG_LINES_PER_APP) deque.removeFirst()
            deque.addLast(formatted)
            val tail = if (deque.size <= 25) deque.toList() else deque.toList().takeLast(25)
            appNodeLogs[appName] = tail.joinToString("\n")
        }
    }

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
        intentionallyStoppedApps.remove(appName)
        appRestartCounts[appName] = 0
        appWatchdogState[appName] = "running"
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
        intentionallyStoppedApps.add(appName)
        appWatchdogState[appName] = "stopped"
        appServerSockets.remove(appName)?.let { sock ->
            runCatching { sock.close() }
        }
        appProcesses.remove(appName)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * Detecta si el directorio desplegado de `appName` contiene una aplicación Node.js
     * y la ejecuta en segundo plano redirigiendo su puerto interno a `appPort + 10000` (ej. 13001),
     * aplicando límite de RAM (`--max-old-space-size`), almacén compartido de dependencias y Watchdog.
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
        val sharedNodeModules = dbEngine.getSharedNodeModulesPath()
        ensureNodeModulesLinked(appRoot, globalNodeModules, sharedNodeModules)

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

            const extraModPaths = [
              process.env.CPS_SHARED_NODE_MODULES || '',
              process.env.CPS_GLOBAL_NODE_MODULES || ''
            ].filter(Boolean);

            for (const modDir of extraModPaths) {
              if (Array.isArray(Module.globalPaths) && !Module.globalPaths.includes(modDir)) {
                Module.globalPaths.push(modDir);
              }
              const parentDir = path.dirname(modDir);
              if (Array.isArray(Module.globalPaths) && !Module.globalPaths.includes(parentDir)) {
                Module.globalPaths.push(parentDir);
              }
            }

            const origNodeModulePaths = Module._nodeModulePaths;
            Module._nodeModulePaths = function(from) {
              const paths = origNodeModulePaths.call(this, from) || [];
              for (const modDir of extraModPaths) {
                if (!paths.includes(modDir)) paths.push(modDir);
              }
              return paths;
            };

            const origResolveLookupPaths = Module._resolveLookupPaths;
            Module._resolveLookupPaths = function(request, parent) {
              const paths = origResolveLookupPaths.call(this, request, parent) || [];
              for (const modDir of extraModPaths) {
                if (!paths.includes(modDir)) paths.push(modDir);
              }
              return paths;
            };

            const origResolveFilename = Module._resolveFilename;
            Module._resolveFilename = function(request, parent, isMain, options) {
              try {
                return origResolveFilename.call(this, request, parent, isMain, options);
              } catch (err) {
                if (!request.startsWith('.') && !request.startsWith('/')) {
                  for (const modDir of extraModPaths) {
                    const candidateDir = path.join(modDir, request);
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

        val projects = loadSavedProjects()
        val appConfig = projects.optJSONObject(appName)
        val appMaxRamMb = (appConfig?.optInt("maxRamMb", 0)?.takeIf { it >= 64 } ?: getDefaultMaxRamMb()).coerceIn(64, 2048)

        val localNodeModules = File(appRoot, "node_modules").absolutePath
        val args = listOf(
            muslLinker.absolutePath,
            "--library-path",
            libSearchPath,
            nodeBin.absolutePath,
            "--max-old-space-size=$appMaxRamMb",
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
        env["CPS_APP_NAME"] = appName
        env["CPS_MAX_RAM_MB"] = appMaxRamMb.toString()
        env["CPS_GLOBAL_NODE_MODULES"] = globalNodeModules
        env["CPS_SHARED_NODE_MODULES"] = sharedNodeModules
        env["HOST"] = "127.0.0.1"
        env["NODE_ENV"] = env["NODE_ENV"]?.takeIf { it.isNotBlank() } ?: "production"
        env["NODE_PATH"] = "$localNodeModules:$sharedNodeModules:$globalNodeModules"
        env["LD_LIBRARY_PATH"] = libSearchPath
        env["HOME"] = appRoot.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath

        val projectDb = appConfig?.optString("dbName")?.takeIf { it.isNotBlank() }
            ?: appConfig?.optString("database")?.takeIf { it.isNotBlank() }

        // Si el usuario especificó una BD en su .env, respetarla siempre con máxima prioridad
        val envDb = env["DB_NAME"]?.takeIf { it.isNotBlank() }
            ?: env["DB_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["MYSQL_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: env["DATABASE_NAME"]?.takeIf { it.isNotBlank() }

        val effectiveDb = envDb ?: projectDb ?: EmbeddedDatabaseEngine.DEFAULT_DB_NAME
        if (effectiveDb != EmbeddedDatabaseEngine.DEFAULT_DB_NAME) {
            runCatching { dbEngine.createDatabase(effectiveDb, null, null) }
        }

        if (env["DB_HOST"].isNullOrBlank() || env["DB_HOST"] == "localhost") env["DB_HOST"] = "127.0.0.1"
        if (env["DB_PORT"].isNullOrBlank()) env["DB_PORT"] = "3306"
        if (env["DB_USER"].isNullOrBlank() || env["DB_USER"] == "root") env["DB_USER"] = dbEngine.getDbUser()
        if (env["DB_PASS"].isNullOrBlank()) env["DB_PASS"] = dbEngine.getDbPassword()
        if (env["DB_PASSWORD"].isNullOrBlank()) env["DB_PASSWORD"] = dbEngine.getDbPassword()

        env["DB_NAME"] = effectiveDb
        env["DB_DATABASE"] = effectiveDb
        env["DATABASE"] = effectiveDb
        env["MYSQL_DATABASE"] = effectiveDb
        env["DATABASE_NAME"] = effectiveDb

        appProcesses.remove(appName)?.let { runCatching { it.destroyForcibly() } }
        val startTimestamp = System.currentTimeMillis()
        val proc = pb.start()
        appProcesses[appName] = proc
        appWatchdogState[appName] = "running"
        appendAppLog(appName, "[CPS-Runtime] 🚀 Iniciando '${entryScript.name}' (Puerto :$appPort -> Interno :$internalNodePort · RAM máx: ${appMaxRamMb}MB · BD: '$effectiveDb')")
        Log.i(TAG, "Proceso Node.js iniciado para '$appName' (${entryScript.absolutePath}) en puerto interno $internalNodePort")

        thread(name = "CPS-NodeLog-$appName", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            appendAppLog(appName, line)
                        }
                        Log.i(TAG, "[Node:$appName] $line")
                    }
                }
            }
            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (isRunning && !intentionallyStoppedApps.contains(appName) && appProcesses[appName] === proc) {
                val uptimeMs = System.currentTimeMillis() - startTimestamp
                if (uptimeMs > 60_000L) {
                    appRestartCounts[appName] = 0
                }
                if (isWatchdogEnabled()) {
                    val crashes = (appRestartCounts[appName] ?: 0) + 1
                    appRestartCounts[appName] = crashes
                    val maxRestarts = getWatchdogMaxRestarts()
                    if (crashes <= maxRestarts) {
                        val backoffSec = minOf(25, crashes * 3)
                        appWatchdogState[appName] = "restarting ($crashes/$maxRestarts)"
                        appendAppLog(appName, "[CPS-Watchdog] ⚠️ Proceso terminado inesperadamente (código $exitCode). Auto-reiniciando en ${backoffSec}s (intento $crashes/$maxRestarts)...")
                        runCatching { Thread.sleep(backoffSec * 1000L) }
                        if (isRunning && !intentionallyStoppedApps.contains(appName)) {
                            runCatching { startAppNodeProcessIfPresent(appName, appPort) }
                        }
                    } else {
                        appWatchdogState[appName] = "crash_loop"
                        appendAppLog(appName, "[CPS-Watchdog] 🛑 Auto-reinicio pausado tras $maxRestarts fallos consecutivos. Revisá la consola o instalá las dependencias faltantes.")
                    }
                } else {
                    appWatchdogState[appName] = "exited ($exitCode)"
                    appendAppLog(appName, "[CPS-Runtime] Proceso finalizado (código $exitCode). Watchdog desactivado.")
                }
            }
        }
    }

    /**
     * Unifica en el almacén compartido (`sharedNodeModulesPath`) los enlaces simbólicos de los
     * paquetes incluidos de fábrica (`globalNodeModulesPath`) junto con los paquetes instalados
     * bajo demanda por el usuario, y enlaza `appRoot/node_modules` hacia este almacén común.
     */
    private fun ensureNodeModulesLinked(appRoot: File, globalNodeModulesPath: String, sharedNodeModulesPath: String) {
        val globalDir = File(globalNodeModulesPath)
        val sharedDir = File(sharedNodeModulesPath).apply { mkdirs() }
        if (!globalDir.exists()) return

        // 1. Asegurar que todos los paquetes de fábrica estén enlazados dentro del almacén compartido
        runCatching {
            val globalPkgs = globalDir.listFiles() ?: emptyArray()
            for (pkg in globalPkgs) {
                if (pkg.name.startsWith("@") && pkg.isDirectory) {
                    val scopeDir = File(sharedDir, pkg.name).apply { mkdirs() }
                    val scopedChildren = pkg.listFiles() ?: emptyArray()
                    for (sub in scopedChildren) {
                        val destSub = File(scopeDir, sub.name)
                        if (!destSub.exists()) {
                            runCatching { Os.symlink(sub.absolutePath, destSub.absolutePath) }
                        }
                    }
                } else {
                    val destPkg = File(sharedDir, pkg.name)
                    if (!destPkg.exists()) {
                        runCatching { Os.symlink(pkg.absolutePath, destPkg.absolutePath) }
                    }
                }
            }
        }

        // 2. Enlazar node_modules en los directorios padre hacia sharedDir
        for (parentDir in listOf(context.filesDir, deployedAppsBaseDir)) {
            val link = File(parentDir, "node_modules")
            runCatching {
                if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    java.nio.file.Files.deleteIfExists(link.toPath())
                }
                if (!link.exists()) {
                    Os.symlink(sharedDir.absolutePath, link.absolutePath)
                }
            }
        }

        // 3. Enlazar appRoot/node_modules hacia sharedDir (o completar paquetes faltantes si tiene carpeta propia)
        val appNodeModules = File(appRoot, "node_modules")
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(appNodeModules.toPath())) {
                val target = runCatching { java.nio.file.Files.readSymbolicLink(appNodeModules.toPath()) }.getOrNull()
                if (target == null || target.toString() != sharedDir.absolutePath || !appNodeModules.exists()) {
                    java.nio.file.Files.deleteIfExists(appNodeModules.toPath())
                    Os.symlink(sharedDir.absolutePath, appNodeModules.absolutePath)
                }
            } else if (!appNodeModules.exists()) {
                Os.symlink(sharedDir.absolutePath, appNodeModules.absolutePath)
            } else if (appNodeModules.isDirectory) {
                val sharedPkgs = sharedDir.listFiles() ?: emptyArray()
                for (pkg in sharedPkgs) {
                    val targetPkg = File(appNodeModules, pkg.name)
                    if (!targetPkg.exists()) {
                        runCatching { Os.symlink(pkg.absolutePath, targetPkg.absolutePath) }
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
            path.startsWith("/api/v1/network/") ||
            path.startsWith("/api/v1/runtime/")
    }

    private fun sendAppStatusJson(appName: String, appPort: Int, out: OutputStream) {
        val internalNodePort = appPort + 10000
        val nodeActive = isLocalPortOpen(internalNodePort, 150)
        val appConfig = loadSavedProjects().optJSONObject(appName)
        val maxRamMb = (appConfig?.optInt("maxRamMb", 0)?.takeIf { it >= 64 } ?: getDefaultMaxRamMb())
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
            .put("maxRamMb", maxRamMb)
            .put("restartCount", appRestartCounts[appName] ?: 0)
            .put("watchdogState", appWatchdogState[appName] ?: if (nodeActive) "running" else "idle")
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
        val safeLen = contentLength.coerceAtMost(15L * 1024L * 1024L).toInt()
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

                val primaryUrl = if (appRole == "frontend" || appRole == "fullstack") {
                    "http://$lanIp:$GATEWAY_PORT$cleanRoute"
                } else {
                    "http://$lanIp:$appPort"
                }
                val appMaxRamMb = (info.optInt("maxRamMb", 0).takeIf { it >= 64 } ?: getDefaultMaxRamMb())
                val internalNodePort = appPort + 10000
                val nodeAlive = isLocalPortOpen(internalNodePort, 100) || (appProcesses[appName]?.isAlive == true)

                // Si la app tiene un .env con DB_NAME y info no lo tenía aún, reflejarlo
                val envMap = readEnvMapFromAppName(appName)
                val envDb = envMap["DB_NAME"]?.takeIf { it.isNotBlank() }
                    ?: envMap["DB_DATABASE"]?.takeIf { it.isNotBlank() }
                    ?: envMap["DATABASE"]?.takeIf { it.isNotBlank() }
                if (!envDb.isNullOrBlank() && info.optString("dbName").isNullOrBlank()) {
                    info.put("dbName", envDb)
                }

                val depReport = if (appRole != "frontend") inspectAppDependencies(appName) else null
                val missingArr = depReport?.optJSONArray("missing") ?: JSONArray()

                info.put("appUrl", primaryUrl)
                info.put("directPortUrl", "http://$lanIp:$appPort")
                info.put("gatewayUrl", "http://$lanIp:$GATEWAY_PORT$cleanRoute")
                info.put("maxRamMb", appMaxRamMb)
                info.put("restartCount", appRestartCounts[appName] ?: 0)
                info.put("watchdogState", appWatchdogState[appName] ?: if (nodeAlive) "running" else "idle")
                info.put("nodeRuntimeActive", nodeAlive)
                info.put("missingDependencies", missingArr)
                activeSlots.put(appName, info)

                val color = info.optString("activeColor", "blue")
                pm2.put(
                    JSONObject()
                        .put("name", "$appName-$color")
                        .put("pid", info.optInt("pid", 4100))
                        .put("status", "online")
                        .put("memoryMb", info.optInt("memoryMb", 42))
                        .put("maxRamMb", appMaxRamMb)
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
                    .put("defaultMaxRamMb", getDefaultMaxRamMb())
                    .toString()
            )
        }

        // Consola de Logs en Vivo por App
        if (path.startsWith("/api/v1/projects/") && path.endsWith("/logs") && method == "GET") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/logs").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val projectsObj = loadSavedProjects()
            val appInfo = projectsObj.optJSONObject(appName)
            val appPort = appInfo?.optInt("activePort", 3001) ?: 3001
            val internalNodePort = appPort + 10000
            val nodeActive = isLocalPortOpen(internalNodePort, 120) || (appProcesses[appName]?.isAlive == true)
            val maxRamMb = (appInfo?.optInt("maxRamMb", 0)?.takeIf { it >= 64 } ?: getDefaultMaxRamMb())

            val linesArr = JSONArray()
            val deque = appLogLines[appName]
            if (deque != null) {
                synchronized(deque) {
                    for (line in deque) {
                        linesArr.put(line)
                    }
                }
            }
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("appName", appName)
                    .put("port", appPort)
                    .put("nodeRuntimeActive", nodeActive)
                    .put("maxRamMb", maxRamMb)
                    .put("restartCount", appRestartCounts[appName] ?: 0)
                    .put("watchdogState", appWatchdogState[appName] ?: if (nodeActive) "running" else "idle")
                    .put("lines", linesArr)
                    .toString()
            )
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/logs/clear") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/logs/clear").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            appLogLines[appName]?.let { deque ->
                synchronized(deque) { deque.clear() }
            }
            appNodeLogs.remove(appName)
            appendAppLog(appName, "[CPS-Console] 🧹 Consola limpiada por el usuario.")
            return sendJson(out, 200, JSONObject().put("status", "success").toString())
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/restart") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/restart").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val projectsObj = loadSavedProjects()
            val appInfo = projectsObj.optJSONObject(appName)
                ?: return sendJson(out, 404, JSONObject().put("error", "Proyecto no encontrado").toString())
            val appPort = appInfo.optInt("activePort", 3001)
            appRestartCounts[appName] = 0
            appendAppLog(appName, "[CPS-Control] 🔄 Reinicio manual solicitado por el usuario...")
            startDedicatedAppServer(appName, appPort)
            return sendJson(
                out,
                200,
                JSONObject()
                    .put("status", "success")
                    .put("message", "Servicio '$appName' reiniciado correctamente.")
                    .toString()
            )
        }

        // Editor Visual de Variables de Entorno (.env) por App
        if (path.startsWith("/api/v1/projects/") && path.endsWith("/env") && method == "GET") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/env").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            return sendJson(out, 200, getAppEnvJson(appName).toString())
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/env") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/env").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            return try {
                val res = saveAppEnvJson(appName, json)
                sendJson(out, 200, res.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error guardando variables .env").toString())
            }
        }

        // Escaneo e Instalación Autorizada de Dependencias NPM
        if (path.startsWith("/api/v1/projects/") && path.endsWith("/dependencies") && method == "GET") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/dependencies").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            return sendJson(out, 200, inspectAppDependencies(appName).toString())
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/dependencies/install") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/dependencies/install").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val customPkgs = mutableListOf<String>()
            val arr = json.optJSONArray("packages")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "").trim()
                    if (p.isNotEmpty()) customPkgs.add(p)
                }
            }
            return try {
                val result = installNpmPackagesToSharedStore(appName, customPkgs.takeIf { it.isNotEmpty() })
                sendJson(out, 200, result.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error instalando dependencias").toString())
            }
        }

        if (path.startsWith("/api/v1/projects/") && path.endsWith("/database") && method == "POST") {
            val rawName = path.removePrefix("/api/v1/projects/").removeSuffix("/database").trim('/')
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val newDb = json.optString("database", json.optString("dbName", "")).trim()
            val projectsObj = loadSavedProjects()
            val appInfo = projectsObj.optJSONObject(appName)
            if (appInfo != null && newDb.isNotEmpty()) {
                runCatching { dbEngine.createDatabase(newDb, null, null) }
                appInfo.put("dbName", newDb)
                projectsObj.put(appName, appInfo)
                saveProjects(projectsObj)
                syncDbNameToAppEnvFile(appName, newDb)
                val port = appInfo.optInt("activePort", 3001)
                appendAppLog(appName, "[CPS-DB] 🗄️ Base de datos cambiada en caliente a '$newDb'. Reiniciando proceso...")
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

        if (path.startsWith("/api/v1/projects/") && method == "DELETE") {
            val rawName = path.removePrefix("/api/v1/projects/").trim()
            val appName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            stopDedicatedAppServer(appName)
            File(deployedAppsBaseDir, appName).deleteRecursively()
            appLogLines.remove(appName)
            appNodeLogs.remove(appName)
            appRestartCounts.remove(appName)
            appWatchdogState.remove(appName)

            val projectsObj = loadSavedProjects()
            projectsObj.remove(appName)
            saveProjects(projectsObj)
            return sendJson(
                out,
                200,
                JSONObject().put("message", "Proyecto '$appName' eliminado correctamente.").toString()
            )
        }

        // Configuración de Modo Experto (Límite de RAM por App, Watchdog, Buffer InnoDB)
        if (path == "/api/v1/runtime/expert-config" && method == "GET") {
            return sendJson(out, 200, buildExpertConfigJson().toString())
        }

        if (path == "/api/v1/runtime/expert-config" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val defaultMaxRamMb = json.optInt("defaultMaxRamMb", getDefaultMaxRamMb()).coerceIn(64, 2048)
            val watchdogEnabled = json.optBoolean("watchdogEnabled", isWatchdogEnabled())
            val watchdogMaxRestarts = json.optInt("watchdogMaxRestarts", getWatchdogMaxRestarts()).coerceIn(1, 20)
            val innoDbBufferMb = json.optInt("innoDbBufferMb", dbEngine.getInnoDbBufferPoolMb()).coerceIn(16, 512)

            prefs.edit()
                .putInt("expert_default_max_ram_mb", defaultMaxRamMb)
                .putBoolean("expert_watchdog_enabled", watchdogEnabled)
                .putInt("expert_watchdog_max_restarts", watchdogMaxRestarts)
                .apply()

            // Actualizar overrides de RAM por app si fueron enviados
            val overridesObj = json.optJSONObject("appRamOverrides")
            val projectsObj = loadSavedProjects()
            if (overridesObj != null) {
                val k = overridesObj.keys()
                while (k.hasNext()) {
                    val appName = k.next()
                    val ramVal = overridesObj.optInt(appName, 0)
                    val appInfo = projectsObj.optJSONObject(appName) ?: continue
                    if (ramVal >= 64) {
                        appInfo.put("maxRamMb", ramVal.coerceIn(64, 2048))
                    } else {
                        appInfo.remove("maxRamMb")
                    }
                    projectsObj.put(appName, appInfo)
                }
                saveProjects(projectsObj)
            }

            executor.execute {
                runCatching { dbEngine.applyInnoDbBufferPoolSize(innoDbBufferMb) }
                // Reiniciar procesos Node activos para aplicar el nuevo --max-old-space-size
                val keys = projectsObj.keys()
                while (keys.hasNext()) {
                    val appName = keys.next()
                    val info = projectsObj.optJSONObject(appName) ?: continue
                    val p = info.optInt("activePort", 3001)
                    if (appProcesses.containsKey(appName)) {
                        appRestartCounts[appName] = 0
                        startAppNodeProcessIfPresent(appName, p)
                    }
                }
            }

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("status", "success")
                    .put("message", "Configuración de Modo Experto guardada y aplicada a los servicios.")
                    .put("expertConfig", buildExpertConfigJson())
                    .toString()
            )
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

            // Si el usuario especificó dbName en el formulario de subida, sincronizarlo con el .env del proyecto
            if (dbName.isNotEmpty()) {
                syncDbNameToAppEnvFile(appName, dbName)
            }
            val envVarsFromZip = readEnvMapFromAppName(appName)
            val envDetectedDb = envVarsFromZip["DB_NAME"]?.takeIf { it.isNotBlank() }
                ?: envVarsFromZip["DB_DATABASE"]?.takeIf { it.isNotBlank() }
                ?: envVarsFromZip["DATABASE"]?.takeIf { it.isNotBlank() }

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

            if (existing?.has("maxRamMb") == true) {
                slotInfo.put("maxRamMb", existing.optInt("maxRamMb"))
            }

            val effectiveAppDb = when {
                dbName.isNotEmpty() -> dbName
                !envDetectedDb.isNullOrBlank() -> envDetectedDb
                else -> existing?.optString("dbName") ?: ""
            }
            if (effectiveAppDb.isNotEmpty()) {
                slotInfo.put("dbName", effectiveAppDb)
            }

            projectsObj.put(appName, slotInfo)
            saveProjects(projectsObj)

            // Escanear dependencias del package.json antes de arrancar para avisar al usuario si faltan paquetes
            val depReport = inspectAppDependencies(appName)
            val missingArr = depReport.optJSONArray("missing") ?: JSONArray()
            if (missingArr.length() > 0) {
                val missingNames = (0 until missingArr.length()).mapNotNull {
                    missingArr.optJSONObject(it)?.optString("name")
                }
                appendAppLog(
                    appName,
                    "[CPS-Dependencies] ⚠️ Se detectaron ${missingNames.size} dependencias pendientes de autorización: ${missingNames.joinToString(", ")}"
                )
            }

            // Levantar el servidor HTTP real en el puerto asignado (ej. :3001)
            startDedicatedAppServer(appName, candidatePort)

            val deployPayload = JSONObject(slotInfo.toString())
                .put("appName", appName)
                .put("missingDependencies", missingArr)
                .put("dependencyReport", depReport)

            return sendJson(
                out,
                200,
                JSONObject()
                    .put("status", "success")
                    .put("message", "¡Sistema actualizado con éxito!")
                    .put("deployment", deployPayload)
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

        if (path == "/api/v1/db/export" && method == "GET") {
            val database = queryParams["database"]?.takeIf { it.isNotBlank() }
                ?: queryParams["db"]?.takeIf { it.isNotBlank() }
                ?: EmbeddedDatabaseEngine.DEFAULT_DB_NAME
            return try {
                val res = dbEngine.exportDatabaseSql(database)
                sendJson(out, 200, res.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error exportando base de datos").toString())
            }
        }

        if (path == "/api/v1/db/import" && method == "POST") {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val database = json.optString("database", queryParams["database"] ?: EmbeddedDatabaseEngine.DEFAULT_DB_NAME).trim()
            val sql = json.optString("sql", "").trim()
            if (sql.isEmpty()) {
                return sendJson(out, 400, JSONObject().put("error", "El contenido del archivo .SQL está vacío.").toString())
            }
            return try {
                val res = dbEngine.importDatabaseSql(database, sql)
                sendJson(out, 200, res.toString())
            } catch (e: Exception) {
                sendJson(out, 400, JSONObject().put("error", e.message ?: "Error importando archivo SQL").toString())
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

    // ========================================================================
    // GESTIÓN DE ARCHIVOS .ENV EN CALIENTE POR APLICACIÓN
    // ========================================================================

    private fun findExistingEnvFile(appRoot: File): File? {
        val entryDir = findNodeEntryScript(appRoot)?.parentFile ?: appRoot
        val candidates = listOf(
            File(appRoot, ".env"),
            File(entryDir, ".env"),
            File(appRoot, ".env.production"),
            File(appRoot, ".env.local"),
            File(appRoot, ".env.example")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private fun readEnvMapFromAppName(appName: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val baseDir = File(deployedAppsBaseDir, appName)
        if (!baseDir.exists()) return map
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val envFile = findExistingEnvFile(appRoot) ?: return map
        runCatching {
            envFile.readLines(Charsets.UTF_8).forEach { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val k = trimmed.substringBefore("=").trim()
                    val v = trimmed.substringAfter("=").trim().trim('"', '\'')
                    if (k.isNotEmpty()) map[k] = v
                }
            }
        }
        return map
    }

    private fun syncDbNameToAppEnvFile(appName: String, newDbName: String) {
        val baseDir = File(deployedAppsBaseDir, appName)
        if (!baseDir.exists()) return
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val entryDir = findNodeEntryScript(appRoot)?.parentFile ?: appRoot
        val primaryEnv = File(appRoot, ".env")
        val existingFile = findExistingEnvFile(appRoot)

        val currentMap = readEnvMapFromAppName(appName)
        if (currentMap.isEmpty() && existingFile == null) {
            return
        }
        currentMap["DB_NAME"] = newDbName
        if (currentMap.containsKey("DB_DATABASE")) currentMap["DB_DATABASE"] = newDbName
        if (currentMap.containsKey("DATABASE")) currentMap["DATABASE"] = newDbName
        if (currentMap.containsKey("MYSQL_DATABASE")) currentMap["MYSQL_DATABASE"] = newDbName

        val content = currentMap.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
        runCatching { primaryEnv.writeText(content, Charsets.UTF_8) }
        if (entryDir.canonicalPath != appRoot.canonicalPath) {
            runCatching { File(entryDir, ".env").writeText(content, Charsets.UTF_8) }
        }
    }

    private fun getAppEnvJson(appName: String): JSONObject {
        val baseDir = File(deployedAppsBaseDir, appName)
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val projects = loadSavedProjects()
        val appInfo = projects.optJSONObject(appName)
        val appPort = appInfo?.optInt("activePort", 3001) ?: 3001
        val savedDb = appInfo?.optString("dbName")?.takeIf { it.isNotBlank() } ?: EmbeddedDatabaseEngine.DEFAULT_DB_NAME

        val envFile = findExistingEnvFile(appRoot)
        val hasEnvFile = envFile != null && envFile.exists()
        val rawMap = readEnvMapFromAppName(appName)

        val effectiveDb = rawMap["DB_NAME"]?.takeIf { it.isNotBlank() }
            ?: rawMap["DB_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: rawMap["DATABASE"]?.takeIf { it.isNotBlank() }
            ?: savedDb

        val defaultsObj = JSONObject()
            .put("PORT", appPort.toString())
            .put("DB_HOST", "127.0.0.1")
            .put("DB_PORT", "3306")
            .put("DB_USER", dbEngine.getDbUser())
            .put("DB_PASSWORD", dbEngine.getDbPassword())
            .put("DB_NAME", effectiveDb)
            .put("NODE_ENV", "production")

        val variablesArr = JSONArray()
        if (rawMap.isNotEmpty()) {
            for ((k, v) in rawMap) {
                variablesArr.put(JSONObject().put("key", k).put("value", v))
            }
        } else {
            // Si el proyecto no traía .env o estaba vacío, mostrar las variables activas por defecto
            val defaultKeys = listOf("PORT", "DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "NODE_ENV")
            for (k in defaultKeys) {
                variablesArr.put(JSONObject().put("key", k).put("value", defaultsObj.optString(k, "")))
            }
        }

        val rawText = if (hasEnvFile) {
            runCatching { envFile!!.readText(Charsets.UTF_8) }.getOrDefault("")
        } else {
            (0 until variablesArr.length()).joinToString("\n") { idx ->
                val item = variablesArr.getJSONObject(idx)
                "${item.optString("key")}=${item.optString("value")}"
            }
        }

        return JSONObject()
            .put("appName", appName)
            .put("hasEnvFile", hasEnvFile)
            .put("envFileName", envFile?.name ?: ".env")
            .put("variables", variablesArr)
            .put("rawContent", rawText)
            .put("injectedDefaults", defaultsObj)
    }

    private fun saveAppEnvJson(appName: String, payload: JSONObject): JSONObject {
        val baseDir = File(deployedAppsBaseDir, appName)
        if (!baseDir.exists()) {
            throw IllegalStateException("El directorio del proyecto '$appName' no existe.")
        }
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val entryDir = findNodeEntryScript(appRoot)?.parentFile ?: appRoot

        val finalMap = LinkedHashMap<String, String>()
        val varsArr = payload.optJSONArray("variables")
        if (varsArr != null) {
            for (i in 0 until varsArr.length()) {
                val obj = varsArr.optJSONObject(i) ?: continue
                val k = obj.optString("key", "").trim().replace(Regex("[^a-zA-Z0-9_]"), "")
                val v = obj.optString("value", "").trim()
                if (k.isNotEmpty()) {
                    finalMap[k] = v
                }
            }
        } else if (payload.has("rawContent")) {
            val raw = payload.optString("rawContent", "")
            raw.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val k = trimmed.substringBefore("=").trim()
                    val v = trimmed.substringAfter("=").trim().trim('"', '\'')
                    if (k.isNotEmpty()) finalMap[k] = v
                }
            }
        }

        val content = finalMap.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
        File(appRoot, ".env").writeText(content, Charsets.UTF_8)
        if (entryDir.canonicalPath != appRoot.canonicalPath) {
            runCatching { File(entryDir, ".env").writeText(content, Charsets.UTF_8) }
        }

        // Sincronizar DB_NAME con la configuración del proyecto y crear la BD si no existía
        val newDb = finalMap["DB_NAME"]?.takeIf { it.isNotBlank() }
            ?: finalMap["DB_DATABASE"]?.takeIf { it.isNotBlank() }
            ?: finalMap["DATABASE"]?.takeIf { it.isNotBlank() }

        val projectsObj = loadSavedProjects()
        val appInfo = projectsObj.optJSONObject(appName)
        val appPort = appInfo?.optInt("activePort", 3001) ?: 3001
        if (appInfo != null && !newDb.isNullOrBlank()) {
            runCatching { dbEngine.createDatabase(newDb, null, null) }
            appInfo.put("dbName", newDb)
            projectsObj.put(appName, appInfo)
            saveProjects(projectsObj)
        }

        appRestartCounts[appName] = 0
        appendAppLog(appName, "[CPS-Env] ⚙️ Archivo .env actualizado (${finalMap.size} variables). Reiniciando servicio...")
        startDedicatedAppServer(appName, appPort)

        return JSONObject()
            .put("status", "success")
            .put("message", "Variables .env guardadas y servicio '$appName' reiniciado.")
            .put("env", getAppEnvJson(appName))
    }

    private fun buildExpertConfigJson(): JSONObject {
        val projectsObj = loadSavedProjects()
        val overrides = JSONObject()
        val keys = projectsObj.keys()
        while (keys.hasNext()) {
            val appName = keys.next()
            val info = projectsObj.optJSONObject(appName) ?: continue
            val customRam = info.optInt("maxRamMb", 0)
            overrides.put(appName, if (customRam >= 64) customRam else getDefaultMaxRamMb())
        }

        val sharedDir = File(dbEngine.getSharedNodeModulesPath())
        val sharedPkgs = sharedDir.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0

        return JSONObject()
            .put("defaultMaxRamMb", getDefaultMaxRamMb())
            .put("watchdogEnabled", isWatchdogEnabled())
            .put("watchdogMaxRestarts", getWatchdogMaxRestarts())
            .put("innoDbBufferMb", dbEngine.getInnoDbBufferPoolMb())
            .put("sharedPackagesCount", sharedPkgs)
            .put("appRamOverrides", overrides)
    }

    // ========================================================================
    // ESCÁNER E INSTALADOR DE DEPENDENCIAS NPM CON AUTORIZACIÓN DEL USUARIO
    // ========================================================================

    private fun isModuleInstalledInDir(dir: File, pkgName: String): Boolean {
        val target = File(dir, pkgName)
        return target.exists() && target.isDirectory && File(target, "package.json").exists()
    }

    private fun inspectAppDependencies(appName: String): JSONObject {
        val baseDir = File(deployedAppsBaseDir, appName)
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val pkgFile = File(appRoot, "package.json")

        val globalDir = File(dbEngine.getNodeGlobalModulesPath())
        val sharedDir = File(dbEngine.getSharedNodeModulesPath())
        val localDir = File(appRoot, "node_modules")

        val allDepsArr = JSONArray()
        val missingArr = JSONArray()

        if (pkgFile.exists() && pkgFile.isFile) {
            runCatching {
                val pkgJson = JSONObject(pkgFile.readText(Charsets.UTF_8))
                val depsObj = pkgJson.optJSONObject("dependencies") ?: JSONObject()
                val depKeys = depsObj.keys()
                while (depKeys.hasNext()) {
                    val rawName = depKeys.next().trim()
                    val verSpec = depsObj.optString(rawName, "latest").trim()
                    if (rawName.isEmpty()) continue

                    val cleanCore = rawName.removePrefix("node:")
                    val status = when {
                        BUILTIN_NODE_MODULES.contains(cleanCore) -> "builtin"
                        rawName == "bcrypt" && isModuleInstalledInDir(globalDir, "bcryptjs") -> "preinstalled"
                        isModuleInstalledInDir(globalDir, rawName) -> "preinstalled"
                        isModuleInstalledInDir(sharedDir, rawName) -> "shared"
                        !java.nio.file.Files.isSymbolicLink(localDir.toPath()) && isModuleInstalledInDir(localDir, rawName) -> "local"
                        else -> "missing"
                    }

                    val item = JSONObject()
                        .put("name", rawName)
                        .put("version", verSpec)
                        .put("status", status)
                    allDepsArr.put(item)

                    if (status == "missing") {
                        missingArr.put(item)
                    }
                }
            }
        }

        return JSONObject()
            .put("appName", appName)
            .put("hasPackageJson", pkgFile.exists())
            .put("dependencies", allDepsArr)
            .put("missing", missingArr)
    }

    private fun installNpmPackagesToSharedStore(appName: String, requestedPackages: List<String>?): JSONObject {
        val baseDir = File(deployedAppsBaseDir, appName)
        val appRoot = resolveEffectiveAppRoot(baseDir)
        val globalDir = File(dbEngine.getNodeGlobalModulesPath())
        val sharedDir = File(dbEngine.getSharedNodeModulesPath()).apply { mkdirs() }

        val queue = ArrayDeque<Pair<String, String>>()

        if (!requestedPackages.isNullOrEmpty()) {
            val pkgFile = File(appRoot, "package.json")
            val pkgDeps = if (pkgFile.exists()) {
                runCatching { JSONObject(pkgFile.readText(Charsets.UTF_8)).optJSONObject("dependencies") }.getOrNull()
            } else null

            for (rawSpec in requestedPackages) {
                val trimmed = rawSpec.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("@") && trimmed.substring(1).contains("@")) {
                    val namePart = "@" + trimmed.substring(1).substringBefore("@")
                    val verPart = trimmed.substring(1).substringAfter("@", "latest")
                    queue.addLast(namePart to verPart)
                } else if (!trimmed.startsWith("@") && trimmed.contains("@")) {
                    val namePart = trimmed.substringBefore("@")
                    val verPart = trimmed.substringAfter("@", "latest")
                    queue.addLast(namePart to verPart)
                } else {
                    val verFromPkg = pkgDeps?.optString(trimmed, "latest") ?: "latest"
                    queue.addLast(trimmed to verFromPkg)
                }
            }
        } else {
            val report = inspectAppDependencies(appName)
            val missing = report.optJSONArray("missing") ?: JSONArray()
            for (i in 0 until missing.length()) {
                val item = missing.optJSONObject(i) ?: continue
                val n = item.optString("name", "").trim()
                val v = item.optString("version", "latest").trim()
                if (n.isNotEmpty()) queue.addLast(n to v)
            }
        }

        if (queue.isEmpty()) {
            return JSONObject()
                .put("status", "success")
                .put("installed", JSONArray())
                .put("message", "Todas las dependencias de '$appName' ya están instaladas.")
                .put("dependencyReport", inspectAppDependencies(appName))
        }

        val visited = mutableSetOf<String>()
        val installedList = JSONArray()
        val failedList = JSONArray()

        appendAppLog(appName, "[CPS-NPM] 📦 Iniciando instalación autorizada de dependencias en almacén compartido...")

        while (queue.isNotEmpty() && visited.size < 100) {
            val (pkgName, verSpec) = queue.removeFirst()
            val cleanCore = pkgName.removePrefix("node:").trim()
            if (cleanCore.isEmpty() || visited.contains(pkgName) || BUILTIN_NODE_MODULES.contains(cleanCore)) {
                continue
            }
            if (pkgName == "bcrypt" && isModuleInstalledInDir(globalDir, "bcryptjs")) {
                visited.add(pkgName)
                continue
            }
            if (isModuleInstalledInDir(globalDir, pkgName) || isModuleInstalledInDir(sharedDir, pkgName)) {
                visited.add(pkgName)
                // Si ya estaba en sharedDir, revisar igualmente sus sub-dependencias por si faltaba alguna
                val existingPkgJson = File(sharedDir, "$pkgName/package.json")
                if (existingPkgJson.exists()) {
                    runCatching {
                        val subDeps = JSONObject(existingPkgJson.readText(Charsets.UTF_8)).optJSONObject("dependencies")
                        if (subDeps != null) {
                            val k = subDeps.keys()
                            while (k.hasNext()) {
                                val depName = k.next()
                                if (!visited.contains(depName) && !isModuleInstalledInDir(globalDir, depName) && !isModuleInstalledInDir(sharedDir, depName)) {
                                    queue.addLast(depName to subDeps.optString(depName, "latest"))
                                }
                            }
                        }
                    }
                }
                continue
            }

            visited.add(pkgName)

            try {
                appendAppLog(appName, "[CPS-NPM] ⬇️ Descargando '$pkgName' ($verSpec) desde registry.npmjs.org...")
                val encodedPkg = if (pkgName.startsWith("@")) {
                    "@" + java.net.URLEncoder.encode(pkgName.substring(1), "UTF-8").replace("+", "%20")
                } else {
                    java.net.URLEncoder.encode(pkgName, "UTF-8").replace("+", "%20")
                }
                val metaUrl = "https://registry.npmjs.org/$encodedPkg"
                val conn = (java.net.URL(metaUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.npm.install-v1+json, application/json")
                    setRequestProperty("User-Agent", "CajitaPaaS-NPM-Installer/1.0 (Android ARM64)")
                }

                val metaText = try {
                    if (conn.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP ${conn.responseCode} al consultar paquete '$pkgName'")
                    }
                    conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } finally {
                    conn.disconnect()
                }

                val metaJson = JSONObject(metaText)
                val versionsObj = metaJson.optJSONObject("versions")
                    ?: throw IllegalStateException("Metadatos sin versiones para '$pkgName'")
                val latestVer = metaJson.optJSONObject("dist-tags")?.optString("latest", "") ?: ""
                val resolvedVer = resolveBestNpmVersion(versionsObj, latestVer, verSpec)
                val verObj = versionsObj.optJSONObject(resolvedVer)
                    ?: throw IllegalStateException("No se pudo resolver versión '$verSpec' para '$pkgName'")

                val tarballUrl = verObj.optJSONObject("dist")?.optString("tarball", "") ?: ""
                if (tarballUrl.isEmpty()) {
                    throw IllegalStateException("Sin URL tarball para '$pkgName@$resolvedVer'")
                }

                val destDir = File(sharedDir, pkgName)
                extractNpmTarballTgz(tarballUrl, destDir)

                installedList.put("$pkgName@$resolvedVer")
                appendAppLog(appName, "[CPS-NPM] ✅ Instalado: $pkgName@$resolvedVer")

                val subDeps = verObj.optJSONObject("dependencies")
                if (subDeps != null) {
                    val k = subDeps.keys()
                    while (k.hasNext()) {
                        val depName = k.next()
                        val depVer = subDeps.optString(depName, "latest")
                        if (!visited.contains(depName) &&
                            !isModuleInstalledInDir(globalDir, depName) &&
                            !isModuleInstalledInDir(sharedDir, depName)
                        ) {
                            queue.addLast(depName to depVer)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error instalando paquete NPM '$pkgName': ${e.message}")
                failedList.put("$pkgName (${e.message ?: "error"})")
                appendAppLog(appName, "[CPS-NPM] ❌ No se pudo instalar '$pkgName': ${e.message}")
            }
        }

        ensureNodeModulesLinked(appRoot, globalDir.absolutePath, sharedDir.absolutePath)

        val projectsObj = loadSavedProjects()
        val appPort = projectsObj.optJSONObject(appName)?.optInt("activePort", 3001) ?: 3001
        appRestartCounts[appName] = 0
        appendAppLog(appName, "[CPS-NPM] 🔄 Instalación finalizada (${installedList.length()} paquetes). Reiniciando '$appName'...")
        startDedicatedAppServer(appName, appPort)

        return JSONObject()
            .put("status", if (failedList.length() == 0) "success" else "partial")
            .put("installed", installedList)
            .put("failed", failedList)
            .put("message", "Se instalaron ${installedList.length()} paquetes en el almacén compartido y se reinició '$appName'.")
            .put("dependencyReport", inspectAppDependencies(appName))
    }

    private fun resolveBestNpmVersion(versionsObj: JSONObject, latestVer: String, verSpec: String): String {
        val raw = verSpec.trim()
        if (raw.isEmpty() || raw == "*" || raw.equals("latest", ignoreCase = true)) {
            if (latestVer.isNotEmpty() && versionsObj.has(latestVer)) return latestVer
        }
        val cleaned = raw.replace(Regex("^[~^>=<v\\s]+"), "").substringBefore(" ").trim()
        if (cleaned.isNotEmpty() && versionsObj.has(cleaned)) {
            return cleaned
        }

        val majorPrefix = cleaned.substringBefore(".")
        val minorPrefix = cleaned.split(".").take(2).joinToString(".")
        val allVersions = mutableListOf<String>()
        val k = versionsObj.keys()
        while (k.hasNext()) {
            val v = k.next()
            if (!v.contains("-")) allVersions.add(v)
        }

        if (raw.startsWith("~") && minorPrefix.contains(".")) {
            val matchingMinor = allVersions.filter { it.startsWith("$minorPrefix.") }
            if (matchingMinor.isNotEmpty()) {
                return matchingMinor.maxWithOrNull(::compareSemver) ?: matchingMinor.last()
            }
        }

        if (majorPrefix.isNotEmpty() && majorPrefix.all { it.isDigit() }) {
            val matchingMajor = allVersions.filter { it == majorPrefix || it.startsWith("$majorPrefix.") }
            if (matchingMajor.isNotEmpty()) {
                return matchingMajor.maxWithOrNull(::compareSemver) ?: matchingMajor.last()
            }
        }

        if (latestVer.isNotEmpty() && versionsObj.has(latestVer)) return latestVer
        return allVersions.lastOrNull() ?: versionsObj.keys().next()
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun extractNpmTarballTgz(tarballUrl: String, destPkgDir: File) {
        val conn = (java.net.URL(tarballUrl).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CajitaPaaS-NPM-Installer/1.0 (Android ARM64)")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} descargando tarball")
            }
            if (destPkgDir.exists()) destPkgDir.deleteRecursively()
            destPkgDir.mkdirs()

            java.util.zip.GZIPInputStream(BufferedInputStream(conn.inputStream, 32 * 1024)).use { gis ->
                val header = ByteArray(512)
                var pendingLongName: String? = null
                val copyBuf = ByteArray(16 * 1024)

                while (true) {
                    var readHeader = 0
                    while (readHeader < 512) {
                        val r = gis.read(header, readHeader, 512 - readHeader)
                        if (r <= 0) break
                        readHeader += r
                    }
                    if (readHeader < 512) break
                    if (header.all { it == 0.toByte() }) break

                    val rawName = readTarString(header, 0, 100)
                    val prefix = readTarString(header, 345, 155)
                    val sizeOctal = readTarString(header, 124, 12).trim()
                    val fileSize = sizeOctal.toLongOrNull(8) ?: 0L
                    val typeFlag = header[156].toInt().toChar()

                    val fullEntryName = when {
                        !pendingLongName.isNullOrBlank() -> {
                            val n = pendingLongName!!
                            pendingLongName = null
                            n
                        }
                        prefix.isNotEmpty() -> "$prefix/$rawName"
                        else -> rawName
                    }.replace('\\', '/').trimStart('/')

                    if (typeFlag == 'L') {
                        val nameBytes = readExactTarPayload(gis, fileSize)
                        pendingLongName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000', '\r', '\n')
                        continue
                    } else if (typeFlag == 'x' || typeFlag == 'g') {
                        val paxBytes = readExactTarPayload(gis, fileSize)
                        val paxStr = String(paxBytes, Charsets.UTF_8)
                        for (line in paxStr.lines()) {
                            val idx = line.indexOf(" path=")
                            if (idx > 0) {
                                pendingLongName = line.substring(idx + 6).trim()
                            }
                        }
                        continue
                    }

                    val relPath = if (fullEntryName.contains('/')) {
                        fullEntryName.substringAfter('/')
                    } else {
                        ""
                    }

                    if (typeFlag == '5' || fullEntryName.endsWith("/")) {
                        if (relPath.isNotEmpty()) {
                            val dir = File(destPkgDir, relPath)
                            if (dir.canonicalPath.startsWith(destPkgDir.canonicalPath)) {
                                dir.mkdirs()
                            }
                        }
                        val pad = ((512L - (fileSize % 512L)) % 512L)
                        skipExactBytes(gis, fileSize + pad)
                    } else if (typeFlag == '0' || typeFlag == '\u0000') {
                        val outFile = if (relPath.isNotEmpty()) File(destPkgDir, relPath) else null
                        if (outFile != null && outFile.canonicalPath.startsWith(destPkgDir.canonicalPath)) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var remaining = fileSize
                                while (remaining > 0L) {
                                    val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                                    val r = gis.read(copyBuf, 0, toRead)
                                    if (r <= 0) break
                                    fos.write(copyBuf, 0, r)
                                    remaining -= r
                                }
                            }
                        } else {
                            skipExactBytes(gis, fileSize)
                        }
                        val pad = ((512L - (fileSize % 512L)) % 512L)
                        skipExactBytes(gis, pad)
                    } else {
                        val pad = ((512L - (fileSize % 512L)) % 512L)
                        skipExactBytes(gis, fileSize + pad)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = (offset + length).coerceAtMost(buf.size)
        while (end < limit && buf[end] != 0.toByte()) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun readExactTarPayload(input: InputStream, fileSize: Long): ByteArray {
        val safeLen = fileSize.coerceIn(0L, 2L * 1024L * 1024L).toInt()
        val data = readFixedBytes(input, safeLen)
        val pad = ((512L - (fileSize % 512L)) % 512L)
        skipExactBytes(input, pad)
        return data
    }

    private fun skipExactBytes(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(4096)
        while (remaining > 0L) {
            val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (r <= 0) break
            remaining -= r
        }
    }
}
