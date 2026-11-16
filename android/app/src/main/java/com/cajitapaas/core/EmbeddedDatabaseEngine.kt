package com.cajitapaas.core

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Motor de Base de Datos Real basado en `mariadb:alpine` (ARM64 / aarch64) para CajitaPaaS (CPS):
 *
 * - Ejecuta el binario oficial `/usr/bin/mariadbd` de Alpine Linux (`libmariadbd.so`) mediante
 *   el linker `musl` (`libalpine_musl.so`) desde `nativeLibraryDir` (cumpliendo políticas SELinux W^X de Android 10+).
 * - Mantiene el demonio real `mariadbd` (InnoDB + catálogos `information_schema` reales) en `127.0.0.1:13307`
 *   y socket Unix local.
 * - Expone los puertos TCP `0.0.0.0:3306` (estándar) y `0.0.0.0:13306` hacia DBeaver / MySQL Workbench / Apps
 *   mediante un puente TCP binario directo hacia `127.0.0.1:13307`, sin emulación ni SQLite.
 */
class EmbeddedDatabaseEngine(private val context: Context) {

    companion object {
        private const val TAG = "CPSMariaDbAlpine"
        const val STANDARD_PORT = 3306
        const val BRIDGE_PORT = 13306
        const val INTERNAL_MARIADB_PORT = 13307
        const val DEFAULT_DB_USER = "cajita_admin"
        const val DEFAULT_DB_PASS = "cajitapaas2026"
        const val DEFAULT_DB_NAME = "cajitapaas"
        private const val BUNDLE_ASSET_NAME = "mariadb_alpine_bundle.zip"
        private const val BUNDLE_VERSION_MARKER = ".alpine_mariadb_v2_ready"
    }

    private val prefs by lazy {
        context.getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
    }

    private val mariadbRootDir by lazy {
        File(context.filesDir, "mariadb_alpine").apply { mkdirs() }
    }

    private val dataDir by lazy {
        File(mariadbRootDir, "data")
    }

    private val shareDir by lazy {
        File(mariadbRootDir, "share")
    }

    private val libSymlinkDir by lazy {
        File(mariadbRootDir, "lib").apply { mkdirs() }
    }

    private val tmpDir by lazy {
        File(mariadbRootDir, "tmp").apply { mkdirs() }
    }

    private val socketFile by lazy {
        File(tmpDir, "mysql.sock")
    }

    private val pidFile by lazy {
        File(tmpDir, "mariadbd.pid")
    }

    private val myCnfFile by lazy {
        File(mariadbRootDir, "my.cnf")
    }

    private val executor = Executors.newCachedThreadPool()
    private val watchdogStarted = AtomicBoolean(false)

    @Volatile
    private var mariadbProcess: Process? = null

    @Volatile
    var lastDaemonLog: String = "Inicializando motor MariaDB Alpine..."
        private set

    private var bridgeServerSocket: ServerSocket? = null
    private var standardServerSocket: ServerSocket? = null

    @Volatile
    var isBridgeEnabled: Boolean = false
        private set

    @Volatile
    var bridgeMode: String = "session"
        private set

    @Volatile
    var allowedIp: String? = null
        private set

    private val activeConnections = AtomicInteger(0)
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    init {
        executor.execute {
            runCatching {
                cleanupLegacySqliteFiles()
                ensureMariaDbDaemonRunning()
                // Activar el puente en 3306 y 13306 por defecto para evitar rechazos de conexión en DBeaver
                setBridgeState(enabled = true, mode = "session", allowedClientIp = null)
                startDaemonWatchdog()
            }.onFailure { e ->
                Log.e(TAG, "Error inicializando motor MariaDB Alpine", e)
            }
        }
    }

    fun getDbUser(): String {
        return prefs.getString("db_custom_user", DEFAULT_DB_USER)?.takeIf { it.isNotBlank() } ?: DEFAULT_DB_USER
    }

    fun getDbPassword(): String {
        return prefs.getString("db_custom_pass", DEFAULT_DB_PASS)?.takeIf { it.isNotBlank() } ?: DEFAULT_DB_PASS
    }

    fun getActiveConnectionsCount(): Int = activeConnections.get()

    fun isDaemonHealthy(): Boolean {
        return isLocalPortOpen(INTERNAL_MARIADB_PORT, timeoutMs = 400)
    }

    fun getLibSearchPath(): String {
        ensureBundleExtracted()
        ensureLibrarySymlinks()
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return "${libSymlinkDir.absolutePath}:$nativeLibDir"
    }

    fun getNodeGlobalModulesPath(): String {
        ensureBundleExtracted()
        return File(mariadbRootDir, "node_global/lib/node_modules").let {
            if (it.exists()) it.absolutePath else File(mariadbRootDir, "node_global/node_modules").absolutePath
        }
    }

    /**
     * Elimina cualquier resto antiguo de bases SQLite para evitar conflictos de almacenamiento.
     */
    private fun cleanupLegacySqliteFiles() {
        runCatching {
            val legacyDir = File(context.filesDir, "cps_databases")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
                Log.i(TAG, "Directorio legado SQLite eliminado correctamente.")
            }
        }
    }

    /**
     * Descomprime el datadir pre-inicializado y los archivos de sistema (`errmsg.sys`, `charsets`, `node_global`)
     * de `mariadb:alpine` desde los assets de la APK durante el primer arranque, preservando `data/` si ya existía.
     */
    @Synchronized
    private fun ensureBundleExtracted() {
        val marker = File(mariadbRootDir, BUNDLE_VERSION_MARKER)
        val mysqlSystemDir = File(dataDir, "mysql")
        val errmsgFile = File(shareDir, "english/errmsg.sys")

        if (marker.exists() && mysqlSystemDir.exists() && errmsgFile.exists()) {
            tmpDir.mkdirs()
            ensureLibrarySymlinks()
            writeMyCnfConfiguration()
            return
        }

        val preserveExistingData = mysqlSystemDir.exists()
        Log.i(TAG, "Extrayendo entorno base MariaDB Alpine + Node.js ARM64 (preserveData=$preserveExistingData)...")
        runCatching { mariadbProcess?.destroyForcibly() }
        mariadbProcess = null

        if (!preserveExistingData && dataDir.exists()) dataDir.deleteRecursively()
        if (shareDir.exists()) shareDir.deleteRecursively()
        if (tmpDir.exists()) tmpDir.deleteRecursively()

        dataDir.mkdirs()
        shareDir.mkdirs()
        tmpDir.mkdirs()

        context.assets.open(BUNDLE_ASSET_NAME).use { rawStream ->
            ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val cleanName = entry.name.replace('\\', '/').trimStart('/')
                    if (cleanName.isEmpty() || (preserveExistingData && cleanName.startsWith("data/"))) {
                        zis.closeEntry()
                        continue
                    }
                    val outFile = File(mariadbRootDir, cleanName)
                    if (!outFile.canonicalPath.startsWith(mariadbRootDir.canonicalPath)) {
                        zis.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val len = zis.read(buffer)
                                if (len <= 0) break
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }

        ensureLibrarySymlinks()
        writeMyCnfConfiguration()
        marker.writeText("alpine-mariadb-arm64-v2-ready")
        Log.i(TAG, "Entorno MariaDB Alpine + Node.js extraído con éxito en ${mariadbRootDir.absolutePath}")
    }

    /**
     * Crea enlaces simbólicos en `filesDir/mariadb_alpine/lib/<SONAME>` apuntando a los archivos
     * `libalpine_*.so` extraídos en `nativeLibraryDir` (contexto SELinux `apk_data_file`),
     * permitiendo que `ld-musl` cargue las librerías oficiales sin modificar las cabeceras ELF.
     */
    private fun ensureLibrarySymlinks() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        libSymlinkDir.mkdirs()

        val mapFile = File(mariadbRootDir, "libs_map.txt")
        val lines = when {
            mapFile.exists() -> runCatching { mapFile.readLines() }.getOrDefault(emptyList())
            else -> runCatching {
                context.assets.open("mariadb_libs_map.txt").bufferedReader().use { it.readLines() }
            }.getOrDefault(emptyList())
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.contains("=")) continue
            val soname = trimmed.substringBefore("=").trim()
            val apkSoName = trimmed.substringAfter("=").trim()
            val targetFile = File(nativeLibDir, apkSoName)
            val linkFile = File(libSymlinkDir, soname)
            runCatching {
                if (linkFile.exists()) linkFile.delete()
                Os.symlink(targetFile.absolutePath, linkFile.absolutePath)
            }
        }
    }

    private fun writeMyCnfConfiguration() {
        val cnfContent = """
            [mysqld]
            datadir=${dataDir.absolutePath}
            lc-messages-dir=${shareDir.absolutePath}
            character-sets-dir=${File(shareDir, "charsets").absolutePath}
            tmpdir=${tmpDir.absolutePath}
            socket=${socketFile.absolutePath}
            pid-file=${pidFile.absolutePath}
            port=$INTERNAL_MARIADB_PORT
            bind-address=127.0.0.1
            skip-networking=0
            skip-name-resolve
            innodb-data-file-path=ibdata1:12M:autoextend
            innodb-log-file-size=8M
            innodb-buffer-pool-size=32M
            loose-innodb-use-native-aio=0
            innodb-flush-method=fsync
            max-connections=60
            max-allowed-packet=64M
            character-set-server=utf8mb4
            collation-server=utf8mb4_general_ci
            default-storage-engine=InnoDB

            [client]
            port=$INTERNAL_MARIADB_PORT
            host=127.0.0.1
            socket=${socketFile.absolutePath}
            default-character-set=utf8mb4
        """.trimIndent()
        myCnfFile.writeText(cnfContent)
    }

    /**
     * Inicia el demonio real `mariadbd` de Alpine Linux si no está corriendo y espera hasta que acepte conexiones.
     */
    @Synchronized
    fun ensureMariaDbDaemonRunning(): Boolean {
        if (mariadbProcess?.isAlive == true && isLocalPortOpen(INTERNAL_MARIADB_PORT, timeoutMs = 300)) {
            return true
        }

        ensureBundleExtracted()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val muslLinker = File(nativeLibDir, "libalpine_musl.so")
        val mariadbdBin = File(nativeLibDir, "libmariadbd.so")

        if (!muslLinker.exists() || !mariadbdBin.exists()) {
            val msg = "Binarios de MariaDB Alpine no encontrados en $nativeLibDir"
            lastDaemonLog = msg
            Log.e(TAG, msg)
            return false
        }

        runCatching { Os.chmod(muslLinker.absolutePath, 493) }
        runCatching { Os.chmod(mariadbdBin.absolutePath, 493) }

        // Limpiar sockets o locks huérfanos de cierres previos
        runCatching { if (socketFile.exists()) socketFile.delete() }
        runCatching { if (pidFile.exists()) pidFile.delete() }
        runCatching {
            val tcLog = File(dataDir, "tc.log")
            if (tcLog.exists() && tcLog.length() == 0L) tcLog.delete()
        }

        runCatching { mariadbProcess?.destroyForcibly() }

        val libSearchPath = "${libSymlinkDir.absolutePath}:$nativeLibDir"
        val args = listOf(
            muslLinker.absolutePath,
            "--library-path",
            libSearchPath,
            mariadbdBin.absolutePath,
            "--defaults-file=${myCnfFile.absolutePath}"
        )

        Log.i(TAG, "Iniciando demonio real MariaDB Alpine en 127.0.0.1:$INTERNAL_MARIADB_PORT...")
        val pb = ProcessBuilder(args)
            .directory(mariadbRootDir)
            .redirectErrorStream(true)

        val env = pb.environment()
        env["HOME"] = mariadbRootDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = libSearchPath

        val proc = pb.start()
        mariadbProcess = proc

        thread(name = "CPS-MariaDB-Logger", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        lastDaemonLog = line
                        Log.i(TAG, "[mariadbd] $line")
                    }
                }
            }
        }

        // Esperar hasta 15 segundos a que mariadbd abra el puerto 13307
        repeat(60) {
            if (isLocalPortOpen(INTERNAL_MARIADB_PORT, timeoutMs = 250)) {
                Log.i(TAG, "Demonio MariaDB Alpine listo y escuchando en 127.0.0.1:$INTERNAL_MARIADB_PORT")
                syncAdminUserCredentials()
                return true
            }
            if (!proc.isAlive) {
                Log.e(TAG, "El proceso mariadbd terminó prematuramente. Último log: $lastDaemonLog")
                return false
            }
            Thread.sleep(250)
        }

        Log.w(TAG, "Tiempo de espera agotado aguardando a mariadbd. Último log: $lastDaemonLog")
        return isLocalPortOpen(INTERNAL_MARIADB_PORT, timeoutMs = 300)
    }

    private fun startDaemonWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        thread(name = "CPS-MariaDB-Watchdog", isDaemon = true) {
            while (watchdogStarted.get()) {
                try {
                    Thread.sleep(5000L)
                    if (!isLocalPortOpen(INTERNAL_MARIADB_PORT, timeoutMs = 400)) {
                        Log.w(TAG, "Watchdog detectó MariaDB inactivo. Reiniciando demonio...")
                        ensureMariaDbDaemonRunning()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error en Watchdog de MariaDB", e)
                }
            }
        }
    }

    private fun isLocalPortOpen(port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    /**
     * Ejecuta una sentencia SQL contra el servidor real `mariadbd` utilizando el cliente oficial
     * `/usr/bin/mariadb` de Alpine (`libmariadb_cli.so`).
     */
    private fun runMariaDbCli(database: String?, sql: String): CliResult {
        if (!ensureMariaDbDaemonRunning()) {
            return CliResult(
                exitCode = 1,
                stdout = "",
                stderr = "El motor MariaDB Alpine no está disponible ($lastDaemonLog)"
            )
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val muslLinker = File(nativeLibDir, "libalpine_musl.so")
        val mariadbCliBin = File(nativeLibDir, "libmariadb_cli.so")

        if (!mariadbCliBin.exists()) {
            return CliResult(1, "", "Cliente libmariadb_cli.so no encontrado en $nativeLibDir")
        }

        runCatching { Os.chmod(mariadbCliBin.absolutePath, 493) }

        val libSearchPath = "${libSymlinkDir.absolutePath}:$nativeLibDir"
        val args = mutableListOf(
            muslLinker.absolutePath,
            "--library-path",
            libSearchPath,
            mariadbCliBin.absolutePath,
            "--no-defaults",
            "--protocol=TCP",
            "-h",
            "127.0.0.1",
            "-P",
            INTERNAL_MARIADB_PORT.toString(),
            "-u",
            "root",
            "-p$DEFAULT_DB_PASS",
            "--default-character-set=utf8mb4",
            "--batch",
            "--raw"
        )

        if (!database.isNullOrBlank()) {
            args.add("-D")
            args.add(database.trim())
        }

        args.add("-e")
        args.add(sql)

        val pb = ProcessBuilder(args).directory(mariadbRootDir)
        val env = pb.environment()
        env["HOME"] = mariadbRootDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = libSearchPath

        val proc = pb.start()
        val stdoutFuture = executor.submit<String> {
            proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }
        val stderrFuture = executor.submit<String> {
            proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
        }

        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return CliResult(1, "", "Tiempo de espera agotado ejecutando consulta en MariaDB.")
        }

        val outText = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        val errText = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        return CliResult(proc.exitValue(), outText, errText)
    }

    /**
     * Sincroniza el usuario administrador (`cajita_admin`) y su contraseña con el servidor real MariaDB,
     * otorgándole el 100% de privilegios DBA (`GRANT ALL PRIVILEGES ON *.* WITH GRANT OPTION`).
     */
    fun syncAdminUserCredentials() {
        val user = getDbUser().replace("'", "")
        val pass = getDbPassword().replace("'", "\\'")
        val sql = """
            CREATE DATABASE IF NOT EXISTS `$DEFAULT_DB_NAME` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
            CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            ALTER USER '$user'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            GRANT ALL PRIVILEGES ON *.* TO '$user'@'%' WITH GRANT OPTION;
            CREATE USER IF NOT EXISTS '$user'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            ALTER USER '$user'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            GRANT ALL PRIVILEGES ON *.* TO '$user'@'127.0.0.1' WITH GRANT OPTION;
            CREATE USER IF NOT EXISTS '$user'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            ALTER USER '$user'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('$pass');
            GRANT ALL PRIVILEGES ON *.* TO '$user'@'localhost' WITH GRANT OPTION;
            FLUSH PRIVILEGES;
        """.trimIndent()
        val res = runMariaDbCli(null, sql)
        if (res.exitCode != 0) {
            Log.w(TAG, "Advertencia sincronizando credenciales DBA en MariaDB: ${res.stderr}")
        }
    }

    private fun sanitizeDbName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^a-zA-Z0-9_]"), "")
        return if (cleaned.isEmpty()) DEFAULT_DB_NAME else cleaned.take(64)
    }

    fun listDatabasesJson(): JSONArray {
        val arr = JSONArray()
        val dbListRes = runMariaDbCli(null, "SHOW DATABASES;")
        if (dbListRes.exitCode != 0) {
            Log.w(TAG, "Error listando bases de datos en MariaDB: ${dbListRes.stderr}")
            return arr
        }

        val systemSchemas = setOf("information_schema", "performance_schema", "mysql", "sys")
        val lines = dbListRes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dbNames = if (lines.size > 1) lines.drop(1) else emptyList()

        for (dbName in dbNames) {
            if (systemSchemas.contains(dbName.lowercase())) continue
            val tablesArr = JSONArray()
            val tblRes = runMariaDbCli(dbName, "SHOW TABLES;")
            if (tblRes.exitCode == 0) {
                val tblLines = tblRes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (tbl in tblLines.drop(1)) {
                    tablesArr.put(tbl)
                }
            }
            arr.put(
                JSONObject()
                    .put("name", dbName)
                    .put("isSystem", false)
                    .put("tables", tablesArr)
            )
        }
        return arr
    }

    fun createDatabase(dbName: String, appUser: String?, appPassword: String?): JSONObject {
        val safeName = sanitizeDbName(dbName)

        if (!appUser.isNullOrBlank() && !appPassword.isNullOrBlank() && appPassword.length >= 4) {
            prefs.edit()
                .putString("db_custom_user", appUser.trim())
                .putString("db_custom_pass", appPassword)
                .apply()
        }

        val activeUser = getDbUser().replace("'", "")
        val activePass = getDbPassword().replace("'", "\\'")

        val sql = """
            CREATE DATABASE IF NOT EXISTS `$safeName` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
            CREATE TABLE IF NOT EXISTS `$safeName`.`info_sistema` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `clave` VARCHAR(128) NOT NULL,
                `valor` TEXT NOT NULL,
                `creado_en` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            INSERT INTO `$safeName`.`info_sistema` (`clave`, `valor`) VALUES ('base_creada', '$safeName');
            CREATE USER IF NOT EXISTS '$activeUser'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            ALTER USER '$activeUser'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            GRANT ALL PRIVILEGES ON *.* TO '$activeUser'@'%' WITH GRANT OPTION;
            CREATE USER IF NOT EXISTS '$activeUser'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            ALTER USER '$activeUser'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            GRANT ALL PRIVILEGES ON *.* TO '$activeUser'@'127.0.0.1' WITH GRANT OPTION;
            CREATE USER IF NOT EXISTS '$activeUser'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            ALTER USER '$activeUser'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('$activePass');
            GRANT ALL PRIVILEGES ON *.* TO '$activeUser'@'localhost' WITH GRANT OPTION;
            FLUSH PRIVILEGES;
        """.trimIndent()

        val res = runMariaDbCli(null, sql)
        if (res.exitCode != 0) {
            throw IllegalStateException(res.stderr.trim().ifEmpty { "Error creando base de datos en MariaDB" })
        }

        return JSONObject()
            .put("message", "Base de datos '$safeName' creada con éxito en MariaDB Alpine.")
            .put("database", safeName)
            .put("user", getDbUser())
    }

    fun executeSqlJson(database: String, sql: String): JSONObject {
        val safeDb = database.trim().ifEmpty { DEFAULT_DB_NAME }
        val trimmedSql = sql.trim()
        val cols = JSONArray()
        val rows = JSONArray()

        val res = runMariaDbCli(safeDb, trimmedSql)
        if (res.exitCode != 0) {
            val cleanErr = res.stderr.lines()
                .filterNot { it.contains("Warning:", ignoreCase = true) }
                .joinToString("\n")
                .trim()
            throw IllegalStateException(cleanErr.ifEmpty { "Error ejecutando sentencia SQL en MariaDB" })
        }

        val rawLines = res.stdout.lines().filter { it.isNotEmpty() }
        if (rawLines.isNotEmpty()) {
            val headers = rawLines.first().split('\t')
            for (h in headers) {
                cols.put(h)
            }
            val dataLines = rawLines.drop(1).take(500)
            for (line in dataLines) {
                val values = line.split('\t')
                val rowObj = JSONObject()
                for (i in headers.indices) {
                    val v = values.getOrNull(i)
                    if (v == null || v == "NULL") {
                        rowObj.put(headers[i], JSONObject.NULL)
                    } else {
                        rowObj.put(headers[i], v)
                    }
                }
                rows.put(rowObj)
            }
        } else {
            cols.put("resultado")
            rows.put(JSONObject().put("resultado", "Sentencia ejecutada correctamente en '$safeDb' (MariaDB Alpine)"))
        }

        return JSONObject()
            .put("database", safeDb)
            .put("columns", cols)
            .put("rows", rows)
    }

    /**
     * Inicia o detiene el puente TCP en `0.0.0.0:3306` y `0.0.0.0:13306` hacia el demonio real
     * `mariadbd` (`127.0.0.1:13307`).
     */
    @Synchronized
    fun setBridgeState(enabled: Boolean, mode: String = "session", allowedClientIp: String? = null) {
        bridgeMode = mode
        allowedIp = allowedClientIp

        if (!enabled) {
            stopBridge()
            return
        }

        ensureMariaDbDaemonRunning()

        if (isBridgeEnabled && standardServerSocket != null && !standardServerSocket!!.isClosed) {
            return
        }

        stopBridge()
        isBridgeEnabled = true

        // 1. Escuchar en el puerto estándar 3306
        thread(name = "CPS-MariaDB-Proxy-$STANDARD_PORT") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), STANDARD_PORT), 50)
                standardServerSocket = server
                Log.i(TAG, "Puerto estándar MariaDB activo en 0.0.0.0:$STANDARD_PORT -> 127.0.0.1:$INTERNAL_MARIADB_PORT")

                while (isBridgeEnabled && !server.isClosed) {
                    val client = server.accept() ?: break
                    executor.execute { handleBridgeClient(client) }
                }
            } catch (e: Exception) {
                if (isBridgeEnabled) {
                    Log.w(TAG, "No se pudo enlazar el puerto estándar $STANDARD_PORT: ${e.message}")
                }
            }
        }

        // 2. Escuchar también en el puerto alternativo 13306 por compatibilidad
        thread(name = "CPS-MariaDB-Proxy-$BRIDGE_PORT") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), BRIDGE_PORT), 50)
                bridgeServerSocket = server
                Log.i(TAG, "Puente alternativo MariaDB activo en 0.0.0.0:$BRIDGE_PORT -> 127.0.0.1:$INTERNAL_MARIADB_PORT")

                while (isBridgeEnabled && !server.isClosed) {
                    val client = server.accept() ?: break
                    executor.execute { handleBridgeClient(client) }
                }
            } catch (e: Exception) {
                if (isBridgeEnabled) {
                    Log.e(TAG, "Error en servidor puente MariaDB ($BRIDGE_PORT): ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun stopBridge() {
        isBridgeEnabled = false
        runCatching { standardServerSocket?.close() }
        standardServerSocket = null
        runCatching { bridgeServerSocket?.close() }
        bridgeServerSocket = null
        for (sock in openSockets) {
            runCatching { sock.close() }
        }
        openSockets.clear()
        activeConnections.set(0)
    }

    fun shutdownEngine() {
        watchdogStarted.set(false)
        stopBridge()
        runCatching { mariadbProcess?.destroy() }
        mariadbProcess = null
    }

    /**
     * Conecta de forma transparente y binaria el socket entrante (DBeaver / App en puerto 3306 o 13306)
     * con el servidor real `mariadbd` en `127.0.0.1:13307`.
     */
    private fun handleBridgeClient(clientSocket: Socket) {
        val remoteIp = clientSocket.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
        val filterIp = allowedIp
        if (!filterIp.isNullOrBlank() && remoteIp != filterIp && remoteIp != "127.0.0.1") {
            Log.w(TAG, "Conexión rechazada desde IP no autorizada: $remoteIp (permitida: $filterIp)")
            runCatching { clientSocket.close() }
            return
        }

        if (!ensureMariaDbDaemonRunning()) {
            Log.e(TAG, "No se pudo atender conexión en 3306 porque mariadbd no está activo.")
            runCatching { clientSocket.close() }
            return
        }

        var backendSocket: Socket? = null
        openSockets.add(clientSocket)
        activeConnections.incrementAndGet()

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = 0

            backendSocket = Socket()
            backendSocket.tcpNoDelay = true
            backendSocket.soTimeout = 0
            backendSocket.connect(InetSocketAddress("127.0.0.1", INTERNAL_MARIADB_PORT), 5000)
            openSockets.add(backendSocket)

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val backendIn = backendSocket.getInputStream()
            val backendOut = backendSocket.getOutputStream()

            // Hilo para reenviar datos Cliente -> MariaDB
            val upstreamJob = executor.submit {
                pipeStream(clientIn, backendOut)
                runCatching { backendSocket.shutdownOutput() }
            }

            // Hilo actual reenvía datos MariaDB -> Cliente
            pipeStream(backendIn, clientOut)
            runCatching { clientSocket.shutdownOutput() }
            runCatching { upstreamJob.get(2, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            Log.d(TAG, "Sesión TCP MariaDB finalizada ($remoteIp): ${e.message}")
        } finally {
            openSockets.remove(clientSocket)
            backendSocket?.let {
                openSockets.remove(it)
                runCatching { it.close() }
            }
            activeConnections.decrementAndGet()
            runCatching { clientSocket.close() }
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // Cierre normal del stream
        }
    }
}
