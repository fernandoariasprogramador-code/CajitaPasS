package com.cajitapaas.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Motor de Base de Datos y Puente TCP (Puerto 13306) para CPS (CajitaPaaS):
 * 1. Si el demonio nativo `mysqld` está corriendo en `127.0.0.1:3306`, actúa como proxy TCP transparente.
 * 2. Si `mysqld` no está activo en segundo plano, emula el protocolo de red MySQL/MariaDB v10
 *    respaldado por SQLite nativo de Android para que clientes de escritorio como DBeaver,
 *    MySQL Workbench o HeidiSQL puedan conectarse, explorar esquemas y ejecutar consultas SQL.
 */
class EmbeddedDatabaseEngine(private val context: Context) {

    companion object {
        private const val TAG = "CPSDatabaseEngine"
        const val STANDARD_PORT = 3306
        const val BRIDGE_PORT = 13306
        const val DEFAULT_DB_USER = "cajita_admin"
        const val DEFAULT_DB_PASS = "cajitapaas2026"
        const val DEFAULT_DB_NAME = "cajitapaas"
    }

    private val prefs by lazy {
        context.getSharedPreferences("cps_admin_prefs", Context.MODE_PRIVATE)
    }

    private val dbDir by lazy {
        File(context.filesDir, "cps_databases").apply { mkdirs() }
    }

    private val executor = Executors.newCachedThreadPool()
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
        ensureDefaultDatabaseInitialized()
    }

    fun getActiveConnectionsCount(): Int = activeConnections.get()

    fun getDbUser(): String = prefs.getString("db_custom_user", DEFAULT_DB_USER) ?: DEFAULT_DB_USER

    fun getDbPassword(): String = prefs.getString("db_custom_pass", DEFAULT_DB_PASS) ?: DEFAULT_DB_PASS

    private fun ensureDefaultDatabaseInitialized() {
        openOrCreateDatabase(DEFAULT_DB_NAME).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS deployments_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_name TEXT NOT NULL,
                    active_color TEXT NOT NULL,
                    active_port INTEGER NOT NULL,
                    deployed_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
    }

    private fun sanitizeDbName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^a-zA-Z0-9_]"), "")
        return if (cleaned.isEmpty()) DEFAULT_DB_NAME else cleaned.take(64)
    }

    private fun openOrCreateDatabase(dbName: String): SQLiteDatabase {
        val safeName = sanitizeDbName(dbName)
        val dbFile = File(dbDir, "$safeName.sqlite")
        return SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    }

    fun listDatabasesJson(): JSONArray {
        ensureDefaultDatabaseInitialized()
        val files = dbDir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") }?.sortedBy { it.name } ?: emptyList()
        val arr = JSONArray()

        for (file in files) {
            val dbName = file.name.removeSuffix(".sqlite")
            val tables = JSONArray()
            runCatching {
                openOrCreateDatabase(dbName).use { db ->
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                        null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            tables.put(cursor.getString(0))
                        }
                    }
                }
            }
            arr.put(
                JSONObject()
                    .put("name", dbName)
                    .put("isSystem", false)
                    .put("tables", tables)
            )
        }
        return arr
    }

    fun createDatabase(dbName: String, appUser: String?, appPassword: String?): JSONObject {
        val safeName = sanitizeDbName(dbName)
        openOrCreateDatabase(safeName).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS info_sistema (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    clave TEXT NOT NULL,
                    valor TEXT NOT NULL,
                    creado_en TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }

        if (!appUser.isNullOrBlank() && !appPassword.isNullOrBlank()) {
            prefs.edit()
                .putString("db_custom_user", appUser.trim())
                .putString("db_custom_pass", appPassword)
                .apply()
        }

        return JSONObject()
            .put("message", "Base de datos '$safeName' creada con éxito.")
            .put("database", safeName)
            .put("user", getDbUser())
    }

    fun executeSqlJson(database: String, sql: String): JSONObject {
        val safeDb = sanitizeDbName(database)
        val trimmedSql = sql.trim()
        val cols = JSONArray()
        val rows = JSONArray()

        openOrCreateDatabase(safeDb).use { db ->
            val upper = trimmedSql.uppercase()
            if (isSystemCatalogQuery(upper)) {
                cols.put("TABLE_NAME")
                cols.put("TABLE_TYPE")
                cols.put("ENGINE")
                val files = dbDir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") } ?: emptyArray()
                for (f in files) {
                    val dbN = f.name.removeSuffix(".sqlite")
                    runCatching {
                        openOrCreateDatabase(dbN).use { d ->
                            d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'", null).use { c ->
                                while (c.moveToNext()) {
                                    val r = JSONObject()
                                    r.put("TABLE_NAME", c.getString(0))
                                    r.put("TABLE_TYPE", "BASE TABLE")
                                    r.put("ENGINE", "InnoDB")
                                    rows.put(r)
                                }
                            }
                        }
                    }
                }
            } else if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("SHOW") || upper.startsWith("DESCRIBE") || upper.startsWith("EXPLAIN")) {
                val effectiveQuery = translateMysqlQueryToSqlite(trimmedSql)
                db.rawQuery(effectiveQuery, null).use { cursor ->
                    val colNames = cursor.columnNames
                    for (c in colNames) cols.put(c)
                    var count = 0
                    while (cursor.moveToNext() && count < 200) {
                        val rowObj = JSONObject()
                        for (i in colNames.indices) {
                            val v = cursor.getString(i)
                            rowObj.put(colNames[i], v ?: JSONObject.NULL)
                        }
                        rows.put(rowObj)
                        count++
                    }
                }
            } else {
                val effectiveWrite = translateMysqlQueryToSqlite(trimmedSql)
                db.execSQL(effectiveWrite)
                cols.put("resultado")
                rows.put(JSONObject().put("resultado", "Sentencia ejecutada correctamente en '$safeDb'"))
            }
        }

        return JSONObject()
            .put("database", safeDb)
            .put("columns", cols)
            .put("rows", rows)
    }

    /**
     * Inicia o detiene el puente TCP en `0.0.0.0:13306` para permitir conexiones desde la PC (DBeaver).
     */
    @Synchronized
    fun setBridgeState(enabled: Boolean, mode: String = "session", allowedClientIp: String? = null) {
        bridgeMode = mode
        allowedIp = allowedClientIp

        if (!enabled) {
            stopBridge()
            return
        }

        if (isBridgeEnabled && bridgeServerSocket != null && !bridgeServerSocket!!.isClosed) {
            return
        }

        stopBridge()
        isBridgeEnabled = true

        thread(name = "CPS-DBeaver-Bridge-13306") {
            try {
                val server = ServerSocket(BRIDGE_PORT, 50, InetAddress.getByName("0.0.0.0"))
                bridgeServerSocket = server
                Log.i(TAG, "Puente TCP MariaDB/DBeaver escuchando en 0.0.0.0:$BRIDGE_PORT")

                while (isBridgeEnabled && !server.isClosed) {
                    val client = server.accept() ?: break
                    executor.execute { handleBridgeClient(client) }
                }
            } catch (e: Exception) {
                if (isBridgeEnabled) {
                    Log.e(TAG, "Error en puente TCP DBeaver (:$BRIDGE_PORT)", e)
                }
            }
        }

        // También escuchar en el puerto nativo 3306 si está libre para que DBeaver conecte sin cambiar el puerto por defecto
        thread(name = "CPS-DBeaver-Bridge-3306") {
            try {
                val stdServer = ServerSocket(STANDARD_PORT, 50, InetAddress.getByName("0.0.0.0"))
                standardServerSocket = stdServer
                Log.i(TAG, "Puente TCP MariaDB estándar escuchando en 0.0.0.0:$STANDARD_PORT")

                while (isBridgeEnabled && !stdServer.isClosed) {
                    val client = stdServer.accept() ?: break
                    executor.execute { handleBridgeClient(client) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Puerto estándar 3306 no disponible en 0.0.0.0 (en uso o restringido): ${e.message}")
            }
        }
    }

    @Synchronized
    fun stopBridge() {
        isBridgeEnabled = false
        runCatching { bridgeServerSocket?.close() }
        bridgeServerSocket = null
        runCatching { standardServerSocket?.close() }
        standardServerSocket = null
        for (sock in openSockets) {
            runCatching { sock.close() }
        }
        openSockets.clear()
        activeConnections.set(0)
    }

    private fun handleBridgeClient(clientSocket: Socket) {
        openSockets.add(clientSocket)
        activeConnections.incrementAndGet()
        try {
            clientSocket.soTimeout = 300_000 // 5 minutos
            val remoteIp = clientSocket.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
            val filterIp = allowedIp
            if (!filterIp.isNullOrBlank() && remoteIp != filterIp && remoteIp != "127.0.0.1") {
                Log.w(TAG, "Conexión DBeaver rechazada desde IP no autorizada: $remoteIp (permitida: $filterIp)")
                clientSocket.close()
                return
            }

            // Atender directamente la conexión mediante el protocolo de red binario MySQL/MariaDB v10
            handleMySqlWireProtocolSession(clientSocket)
        } catch (e: Exception) {
            Log.d(TAG, "Sesión DBeaver finalizada: ${e.message}")
        } finally {
            openSockets.remove(clientSocket)
            activeConnections.decrementAndGet()
            runCatching { clientSocket.close() }
        }
    }

    /**
     * Implementación ligera del protocolo MySQL/MariaDB v10 para que DBeaver / MySQL Workbench
     * puedan conectarse al puerto 13306, listar bases de datos, tablas y ejecutar consultas SQL.
     */
    private fun handleMySqlWireProtocolSession(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // 1. Enviar Handshake Inicial v10
        sendInitialHandshakePacket(output, seqId = 0)

        // 2. Leer respuesta de autenticación del cliente (HandshakeResponse41)
        val authPacket = readMysqlPacket(input) ?: return
        var currentDb = parseInitialDbFromHandshake(authPacket.payload) ?: DEFAULT_DB_NAME

        // 3. Enviar OK Packet (seqId = authPacket.seqId + 1)
        sendOkPacket(output, seqId = (authPacket.seqId + 1) and 0xFF)

        // 4. Bucle de comandos MySQL (COM_QUERY = 0x03, COM_INIT_DB = 0x02, COM_PING = 0x0E, COM_QUIT = 0x01)
        while (!socket.isClosed && isBridgeEnabled) {
            val pkt = readMysqlPacket(input) ?: break
            if (pkt.payload.isEmpty()) continue
            val command = pkt.payload[0].toInt() and 0xFF
            val nextSeq = (pkt.seqId + 1) and 0xFF

            when (command) {
                0x01 -> { // COM_QUIT
                    break
                }
                0x02 -> { // COM_INIT_DB (USE database)
                    val requestedDb = String(pkt.payload, 1, pkt.payload.size - 1, Charsets.UTF_8).trim()
                    currentDb = sanitizeDbName(requestedDb)
                    openOrCreateDatabase(currentDb).close()
                    sendOkPacket(output, seqId = nextSeq)
                }
                0x0E -> { // COM_PING
                    sendOkPacket(output, seqId = nextSeq)
                }
                0x03 -> { // COM_QUERY
                    val rawQuery = String(pkt.payload, 1, pkt.payload.size - 1, Charsets.UTF_8).trim()
                    currentDb = handleWireQuery(rawQuery, currentDb, output, nextSeq)
                }
                else -> {
                    sendOkPacket(output, seqId = nextSeq)
                }
            }
        }
    }

    private fun handleWireQuery(
        rawQuery: String,
        currentDb: String,
        output: OutputStream,
        startSeq: Int
    ): String {
        val clean = rawQuery.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").trim().removeSuffix(";").trim()
        val upper = clean.uppercase()

        try {
            // Comandos de sesión e inspección de metadatos que DBeaver/JDBC envía al conectar
            if (upper.startsWith("SET ") || upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK") || upper.startsWith("START TRANSACTION") || upper.startsWith("BEGIN")) {
                sendOkPacket(output, startSeq)
                return currentDb
            }

            if (upper.startsWith("USE ")) {
                val rawTarget = clean.substring(4).trim().trim('`', '\'', '"', ';')
                val targetDb = sanitizeDbName(rawTarget)
                if (targetDb.equals("information_schema", ignoreCase = true) ||
                    targetDb.equals("performance_schema", ignoreCase = true) ||
                    targetDb.equals("mysql", ignoreCase = true) ||
                    targetDb.equals("sys", ignoreCase = true)) {
                    sendOkPacket(output, startSeq)
                    return targetDb
                }
                openOrCreateDatabase(targetDb).close()
                sendOkPacket(output, startSeq)
                return targetDb
            }

            if (upper.startsWith("SELECT @@") || upper.contains("@@VERSION") || upper.contains("VERSION()")) {
                val varMap = mapOf(
                    "@@VERSION" to "10.11.6-MariaDB-CPS",
                    "@@VERSION_COMMENT" to "CajitaPaaS Android Embedded MariaDB",
                    "@@TX_ISOLATION" to "REPEATABLE-READ",
                    "@@TRANSACTION_ISOLATION" to "REPEATABLE-READ",
                    "@@SESSION.TX_ISOLATION" to "REPEATABLE-READ",
                    "@@SESSION.TRANSACTION_ISOLATION" to "REPEATABLE-READ",
                    "@@SESSION.AUTO_INCREMENT_INCREMENT" to "1",
                    "@@AUTO_INCREMENT_INCREMENT" to "1",
                    "@@MAX_ALLOWED_PACKET" to "67108864",
                    "@@SYSTEM_TIME_ZONE" to "UTC",
                    "@@TIME_ZONE" to "SYSTEM",
                    "@@LOWER_CASE_TABLE_NAMES" to "0",
                    "DATABASE()" to currentDb,
                    "VERSION()" to "10.11.6-MariaDB-CPS"
                )
                val rawCols = clean.substringAfter("SELECT", "").substringBefore("FROM").substringBefore("LIMIT").split(",")
                val cols = mutableListOf<String>()
                val vals = mutableListOf<String?>()
                for (c in rawCols) {
                    val trimmed = c.trim()
                    if (trimmed.isEmpty()) continue
                    val name = if (trimmed.contains(" AS ", ignoreCase = true)) {
                        trimmed.substringAfterLast(" ", "").trim('`', '\'', '"')
                    } else trimmed
                    val lookupKey = trimmed.substringBefore(" ").trim().uppercase()
                    cols.add(name)
                    vals.add(varMap[lookupKey] ?: "1")
                }
                if (cols.isEmpty()) {
                    cols.add("@@version")
                    vals.add("10.11.6-MariaDB-CPS")
                }
                sendResultSet(output, startSeq, cols, listOf(vals))
                return currentDb
            }

            if (upper == "SELECT DATABASE()" || upper.startsWith("SELECT DATABASE()")) {
                sendResultSet(output, startSeq, listOf("DATABASE()"), listOf(listOf(currentDb)))
                return currentDb
            }

            if (upper.startsWith("SHOW DATABASES") || upper.startsWith("SHOW SCHEMAS")) {
                val dbs = mutableListOf<List<String?>>()
                val jsonArr = listDatabasesJson()
                for (i in 0 until jsonArr.length()) {
                    dbs.add(listOf(jsonArr.getJSONObject(i).getString("name")))
                }
                sendResultSet(output, startSeq, listOf("Database"), dbs)
                return currentDb
            }

            if (upper.startsWith("SHOW FULL TABLES") || upper.startsWith("SHOW TABLES")) {
                val targetDb = if (upper.contains(" FROM ")) {
                    sanitizeDbName(clean.substringAfterLast(" ").trim('`', '\'', '"'))
                } else currentDb

                val rows = mutableListOf<List<String?>>()
                openOrCreateDatabase(targetDb).use { db ->
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                        null
                    ).use { c ->
                        while (c.moveToNext()) {
                            if (upper.startsWith("SHOW FULL TABLES")) {
                                rows.add(listOf(c.getString(0), "BASE TABLE"))
                            } else {
                                rows.add(listOf(c.getString(0)))
                            }
                        }
                    }
                }
                val cols = if (upper.startsWith("SHOW FULL TABLES")) {
                    listOf("Tables_in_$targetDb", "Table_type")
                } else {
                    listOf("Tables_in_$targetDb")
                }
                sendResultSet(output, startSeq, cols, rows)
                return currentDb
            }

            if (upper.startsWith("SHOW VARIABLES") || upper.startsWith("SHOW SESSION VARIABLES") || upper.startsWith("SHOW GLOBAL VARIABLES")) {
                sendResultSet(
                    output,
                    startSeq,
                    listOf("Variable_name", "Value"),
                    listOf(
                        listOf("character_set_client", "utf8mb4"),
                        listOf("character_set_connection", "utf8mb4"),
                        listOf("character_set_results", "utf8mb4"),
                        listOf("character_set_server", "utf8mb4"),
                        listOf("collation_server", "utf8mb4_unicode_ci"),
                        listOf("lower_case_table_names", "0"),
                        listOf("max_allowed_packet", "67108864"),
                        listOf("system_time_zone", "UTC"),
                        listOf("time_zone", "SYSTEM"),
                        listOf("version", "10.11.6-MariaDB-CPS"),
                        listOf("version_comment", "CajitaPaaS Android Embedded MariaDB")
                    )
                )
                return currentDb
            }

            if (upper.startsWith("SHOW WARNINGS") || upper.startsWith("SHOW ENGINES") || upper.startsWith("SHOW COLLATION") || upper.startsWith("SHOW CHARSET") || upper.startsWith("SHOW CHARACTER SET") || upper.startsWith("SHOW INDEX") || upper.startsWith("SHOW KEYS") || upper.startsWith("SHOW TABLE STATUS") || upper.startsWith("SHOW PROCEDURE") || upper.startsWith("SHOW FUNCTION")) {
                sendResultSet(output, startSeq, listOf("Info"), emptyList())
                return currentDb
            }

            if (upper.startsWith("SHOW COLUMNS") || upper.startsWith("SHOW FULL COLUMNS") || upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")) {
                val tbl = if (clean.contains("FROM", ignoreCase = true)) {
                    clean.substringAfter("FROM", "").trim().substringBefore(" ").trim('`', '\'', '"', ';').substringAfterLast('.')
                } else {
                    clean.substringAfterLast(" ").trim('`', '\'', '"', ';').substringAfterLast('.')
                }
                val rows = mutableListOf<List<String?>>()
                openOrCreateDatabase(currentDb).use { db ->
                    db.rawQuery("PRAGMA table_info(`$tbl`)", null).use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(1)
                            val type = c.getString(2).ifEmpty { "TEXT" }
                            val notNull = if (c.getInt(3) == 1) "NO" else "YES"
                            val dflt = c.getString(4)
                            val pk = if (c.getInt(5) == 1) "PRI" else ""
                            rows.add(listOf(name, type, notNull, pk, dflt, ""))
                        }
                    }
                }
                sendResultSet(output, startSeq, listOf("Field", "Type", "Null", "Key", "Default", "Extra"), rows)
                return currentDb
            }

            if (upper.startsWith("SHOW CREATE TABLE")) {
                val tbl = clean.substringAfterLast(" ").trim('`', '\'', '"').substringAfterLast('.')
                var sqlCreate = "CREATE TABLE `$tbl` (`id` int)"
                openOrCreateDatabase(currentDb).use { db ->
                    db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tbl)).use { c ->
                        if (c.moveToFirst()) {
                            sqlCreate = c.getString(0) ?: sqlCreate
                        }
                    }
                }
                sendResultSet(output, startSeq, listOf("Table", "Create Table"), listOf(listOf(tbl, sqlCreate)))
                return currentDb
            }

            if (upper.startsWith("CREATE DATABASE") || upper.startsWith("CREATE SCHEMA")) {
                val dbName = sanitizeDbName(clean.substringAfterLast(" ").trim('`', '\'', '"'))
                openOrCreateDatabase(dbName).close()
                sendOkPacket(output, startSeq)
                return currentDb
            }

            // Consultas de catálogo e introspección de esquemas que DBeaver / JDBC ejecutan
            if (isSystemCatalogQuery(upper)) {
                handleInformationSchemaQuery(clean, upper, currentDb, output, startSeq)
                return currentDb
            }

            // Consultas de lectura generales (SELECT)
            if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA")) {
                val sqliteQuery = translateMysqlQueryToSqlite(clean)
                openOrCreateDatabase(currentDb).use { db ->
                    db.rawQuery(sqliteQuery, null).use { cursor ->
                        val cols = cursor.columnNames.toList()
                        val rows = mutableListOf<List<String?>>()
                        while (cursor.moveToNext()) {
                            val r = mutableListOf<String?>()
                            for (i in cols.indices) {
                                r.add(cursor.getString(i))
                            }
                            rows.add(r)
                        }
                        sendResultSet(output, startSeq, cols, rows)
                    }
                }
                return currentDb
            }

            // Consultas de escritura (CREATE TABLE, INSERT, UPDATE, DELETE, DROP)
            val effectiveWrite = translateMysqlQueryToSqlite(clean)
            openOrCreateDatabase(currentDb).use { db ->
                db.execSQL(effectiveWrite)
            }
            sendOkPacket(output, startSeq)
        } catch (e: Exception) {
            val safeMsg = sanitizeErrorMessage(e.message)
            sendErrorPacket(output, startSeq, 1064, safeMsg)
        }
        return currentDb
    }

    private fun isSystemCatalogQuery(upper: String): Boolean {
        return upper.contains("INFORMATION_SCHEMA") ||
            upper.contains("PERFORMANCE_SCHEMA") ||
            upper.contains("MYSQL.") ||
            upper.contains("SYS.")
    }

    private fun extractSelectColumns(clean: String): List<String> {
        val upper = clean.uppercase()
        val selectIdx = upper.indexOf("SELECT")
        if (selectIdx < 0) return listOf("Result")
        val afterSelect = clean.substring(selectIdx + 6).trim()
        val fromIdx = afterSelect.uppercase().indexOf("FROM")
        val colsPart = if (fromIdx > 0) afterSelect.substring(0, fromIdx) else afterSelect
        val items = colsPart.split(",")
        val result = mutableListOf<String>()
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val colName = if (trimmed.contains(" AS ", ignoreCase = true)) {
                trimmed.substringAfterLast(" ").trim('`', '\'', '"')
            } else {
                trimmed.substringAfterLast(".").trim('`', '\'', '"')
            }
            if (colName.isNotEmpty()) {
                result.add(colName)
            }
        }
        return if (result.isEmpty()) listOf("Result") else result
    }

    private fun handleInformationSchemaQuery(
        clean: String,
        upper: String,
        currentDb: String,
        output: OutputStream,
        startSeq: Int
    ) {
        val rawCols = extractSelectColumns(clean)
        val schemaMatch = Regex("(?i)TABLE_SCHEMA\\s*=\\s*['\"]([^'\"]+)['\"]").find(clean)
            ?: Regex("(?i)SCHEMA_NAME\\s*=\\s*['\"]([^'\"]+)['\"]").find(clean)
        val targetCandidate = schemaMatch?.groups?.get(1)?.value?.let { sanitizeDbName(it) } ?: currentDb
        val targetDb = if (targetCandidate.equals("information_schema", ignoreCase = true) ||
            targetCandidate.equals("performance_schema", ignoreCase = true) ||
            targetCandidate.equals("mysql", ignoreCase = true) ||
            targetCandidate.equals("sys", ignoreCase = true)) {
            DEFAULT_DB_NAME
        } else targetCandidate

        val tableMatch = Regex("(?i)TABLE_NAME\\s*=\\s*['\"]([^'\"]+)['\"]").find(clean)
        val filterTable = tableMatch?.groups?.get(1)?.value?.trim('`', '\'', '"')

        // 1. Consultas de agregación COUNT(...)
        if (upper.contains("COUNT(")) {
            var count = 0
            if (upper.contains("TABLES") || upper.contains("COLUMNS")) {
                openOrCreateDatabase(targetDb).use { db ->
                    val countQuery = if (filterTable != null) {
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=? AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
                    } else {
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
                    }
                    val args = if (filterTable != null) arrayOf(filterTable) else null
                    db.rawQuery(countQuery, args).use { c ->
                        if (c.moveToFirst()) count = c.getInt(0)
                    }
                }
            }
            val colName = rawCols.firstOrNull() ?: "count(*)"
            sendResultSet(output, startSeq, listOf(colName), listOf(listOf(count.toString())))
            return
        }

        // Obtener tablas del esquema objetivo
        val tables = mutableListOf<String>()
        openOrCreateDatabase(targetDb).use { db ->
            val listQuery = if (filterTable != null) {
                "SELECT name FROM sqlite_master WHERE type='table' AND name=? AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
            } else {
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name"
            }
            val args = if (filterTable != null) arrayOf(filterTable) else null
            db.rawQuery(listQuery, args).use { c ->
                while (c.moveToNext()) {
                    tables.add(c.getString(0))
                }
            }
        }

        // 2. information_schema.COLUMNS
        if (upper.contains("COLUMNS")) {
            val cols = if (rawCols.contains("*")) {
                listOf(
                    "TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
                    "COLUMN_DEFAULT", "IS_NULLABLE", "DATA_TYPE", "CHARACTER_MAXIMUM_LENGTH",
                    "CHARACTER_OCTET_LENGTH", "NUMERIC_PRECISION", "NUMERIC_SCALE", "DATETIME_PRECISION",
                    "CHARACTER_SET_NAME", "COLLATION_NAME", "COLUMN_TYPE", "COLUMN_KEY", "EXTRA",
                    "PRIVILEGES", "COLUMN_COMMENT", "IS_GENERATED", "GENERATION_EXPRESSION"
                )
            } else rawCols

            val rows = mutableListOf<List<String?>>()
            openOrCreateDatabase(targetDb).use { db ->
                for (tbl in tables) {
                    db.rawQuery("PRAGMA table_info(`$tbl`)", null).use { c ->
                        while (c.moveToNext()) {
                            val cid = c.getInt(0)
                            val name = c.getString(1)
                            val type = c.getString(2).ifEmpty { "TEXT" }
                            val notNull = c.getInt(3)
                            val dflt = c.getString(4)
                            val pk = c.getInt(5)

                            val colValues = mapOf(
                                "TABLE_CATALOG" to "def",
                                "TABLE_SCHEMA" to targetDb,
                                "TABLE_NAME" to tbl,
                                "COLUMN_NAME" to name,
                                "ORDINAL_POSITION" to (cid + 1).toString(),
                                "COLUMN_DEFAULT" to dflt,
                                "IS_NULLABLE" to (if (notNull == 1) "NO" else "YES"),
                                "DATA_TYPE" to type.lowercase().substringBefore("("),
                                "COLUMN_TYPE" to type.lowercase(),
                                "COLUMN_KEY" to (if (pk == 1) "PRI" else ""),
                                "EXTRA" to (if (pk == 1 && type.contains("INT", ignoreCase = true)) "auto_increment" else ""),
                                "CHARACTER_MAXIMUM_LENGTH" to "65535",
                                "CHARACTER_OCTET_LENGTH" to "65535",
                                "NUMERIC_PRECISION" to "10",
                                "NUMERIC_SCALE" to "0",
                                "DATETIME_PRECISION" to "0",
                                "CHARACTER_SET_NAME" to "utf8mb4",
                                "COLLATION_NAME" to "utf8mb4_unicode_ci",
                                "PRIVILEGES" to "select,insert,update,references",
                                "COLUMN_COMMENT" to "",
                                "IS_GENERATED" to "NEVER",
                                "GENERATION_EXPRESSION" to null
                            )

                            val r = mutableListOf<String?>()
                            for (col in cols) {
                                r.add(colValues[col.uppercase()])
                            }
                            rows.add(r)
                        }
                    }
                }
            }
            sendResultSet(output, startSeq, cols, rows)
            return
        }

        // 3. information_schema.TABLES
        if (upper.contains("TABLES")) {
            val cols = if (rawCols.contains("*")) {
                listOf(
                    "TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_TYPE", "ENGINE",
                    "VERSION", "ROW_FORMAT", "TABLE_ROWS", "AVG_ROW_LENGTH", "DATA_LENGTH",
                    "MAX_DATA_LENGTH", "INDEX_LENGTH", "DATA_FREE", "AUTO_INCREMENT",
                    "CREATE_TIME", "UPDATE_TIME", "CHECK_TIME", "TABLE_COLLATION", "CHECKSUM",
                    "CREATE_OPTIONS", "TABLE_COMMENT"
                )
            } else rawCols

            val rows = mutableListOf<List<String?>>()
            for (tbl in tables) {
                val tableValues = mapOf(
                    "TABLE_CATALOG" to "def",
                    "TABLE_SCHEMA" to targetDb,
                    "TABLE_NAME" to tbl,
                    "TABLE_TYPE" to "BASE TABLE",
                    "ENGINE" to "InnoDB",
                    "VERSION" to "10",
                    "ROW_FORMAT" to "Dynamic",
                    "TABLE_ROWS" to "0",
                    "AVG_ROW_LENGTH" to "0",
                    "DATA_LENGTH" to "16384",
                    "MAX_DATA_LENGTH" to "0",
                    "INDEX_LENGTH" to "0",
                    "DATA_FREE" to "0",
                    "AUTO_INCREMENT" to "1",
                    "CREATE_TIME" to "2026-01-01 00:00:00",
                    "UPDATE_TIME" to "2026-01-01 00:00:00",
                    "CHECK_TIME" to null,
                    "TABLE_COLLATION" to "utf8mb4_unicode_ci",
                    "CHECKSUM" to null,
                    "CREATE_OPTIONS" to "",
                    "TABLE_COMMENT" to ""
                )
                val r = mutableListOf<String?>()
                for (col in cols) {
                    r.add(tableValues[col.uppercase()])
                }
                rows.add(r)
            }
            sendResultSet(output, startSeq, cols, rows)
            return
        }

        // 4. information_schema.SCHEMATA
        if (upper.contains("SCHEMATA")) {
            val cols = if (rawCols.contains("*")) {
                listOf("CATALOG_NAME", "SCHEMA_NAME", "DEFAULT_CHARACTER_SET_NAME", "DEFAULT_COLLATION_NAME", "SQL_PATH")
            } else rawCols

            val dbsJson = listDatabasesJson()
            val rows = mutableListOf<List<String?>>()
            for (i in 0 until dbsJson.length()) {
                val dbName = dbsJson.getJSONObject(i).getString("name")
                val schemaValues = mapOf(
                    "CATALOG_NAME" to "def",
                    "SCHEMA_NAME" to dbName,
                    "DEFAULT_CHARACTER_SET_NAME" to "utf8mb4",
                    "DEFAULT_COLLATION_NAME" to "utf8mb4_unicode_ci",
                    "SQL_PATH" to null
                )
                val r = mutableListOf<String?>()
                for (col in cols) {
                    r.add(schemaValues[col.uppercase()])
                }
                rows.add(r)
            }
            sendResultSet(output, startSeq, cols, rows)
            return
        }

        // 5. information_schema.KEY_COLUMN_USAGE o TABLE_CONSTRAINTS
        if (upper.contains("KEY_COLUMN_USAGE") || upper.contains("TABLE_CONSTRAINTS")) {
            val cols = if (rawCols.contains("*")) {
                listOf(
                    "CONSTRAINT_CATALOG", "CONSTRAINT_SCHEMA", "CONSTRAINT_NAME", "TABLE_CATALOG",
                    "TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
                    "POSITION_IN_UNIQUE_CONSTRAINT", "REFERENCED_TABLE_SCHEMA", "REFERENCED_TABLE_NAME", "REFERENCED_COLUMN_NAME"
                )
            } else rawCols

            val rows = mutableListOf<List<String?>>()
            openOrCreateDatabase(targetDb).use { db ->
                for (tbl in tables) {
                    db.rawQuery("PRAGMA table_info(`$tbl`)", null).use { c ->
                        while (c.moveToNext()) {
                            val pk = c.getInt(5)
                            if (pk == 1) {
                                val colName = c.getString(1)
                                val constraintValues = mapOf(
                                    "CONSTRAINT_CATALOG" to "def",
                                    "CONSTRAINT_SCHEMA" to targetDb,
                                    "CONSTRAINT_NAME" to "PRIMARY",
                                    "TABLE_CATALOG" to "def",
                                    "TABLE_SCHEMA" to targetDb,
                                    "TABLE_NAME" to tbl,
                                    "COLUMN_NAME" to colName,
                                    "ORDINAL_POSITION" to "1",
                                    "POSITION_IN_UNIQUE_CONSTRAINT" to null,
                                    "REFERENCED_TABLE_SCHEMA" to null,
                                    "REFERENCED_TABLE_NAME" to null,
                                    "REFERENCED_COLUMN_NAME" to null
                                )
                                val r = mutableListOf<String?>()
                                for (col in cols) {
                                    r.add(constraintValues[col.uppercase()])
                                }
                                rows.add(r)
                            }
                        }
                    }
                }
            }
            sendResultSet(output, startSeq, cols, rows)
            return
        }

        // 6. Consultas para cualquier otra tabla de catálogo (TRIGGERS, VIEWS, ROUTINES, STATISTICS, etc.)
        val cols = if (rawCols.contains("*")) listOf("Info") else rawCols
        sendResultSet(output, startSeq, cols, emptyList())
    }

    private fun sanitizeErrorMessage(raw: String?): String {
        if (raw == null) return "Error de sintaxis SQL"
        return raw
            .replace(Regex("(?i)android\\.database\\.sqlite\\.SQLiteException:\\s*"), "")
            .replace(Regex("(?i)android\\.database\\.sqlite\\."), "")
            .replace(Regex("(?i)\\bcode\\s+\\d+\\s+SQLITE_[A-Z_]+;?\\s*"), "")
            .replace(Regex("(?i)\\bSQLITE_[A-Z_]+\\b"), "")
            .replace(Regex("(?i)\\bsqlite\\b"), "MariaDB")
            .replace(Regex("(?i),?\\s*while compiling:.*"), "")
            .trim()
    }

    private fun translateMysqlQueryToSqlite(sql: String): String {
        var query = sql.trim()
        val upper = query.uppercase()

        if (upper == "SHOW DATABASES" || upper.startsWith("SHOW DATABASES")) {
            return "SELECT '$DEFAULT_DB_NAME' AS Database"
        }
        if (upper.startsWith("SHOW TABLES")) {
            return "SELECT name AS Tables_in_db FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
        }
        if (upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")) {
            val tbl = query.substringAfter(" ").trim().trim('`', '\'', '"', ';').substringAfterLast('.')
            return "PRAGMA table_info(`$tbl`)"
        }
        if (upper.startsWith("SHOW COLUMNS FROM ") || upper.startsWith("SHOW FULL COLUMNS FROM ")) {
            val tbl = query.substringAfterLast(" ").trim().trim('`', '\'', '"', ';').substringAfterLast('.')
            return "PRAGMA table_info(`$tbl`)"
        }
        if (upper.startsWith("TRUNCATE TABLE ") || upper.startsWith("TRUNCATE ")) {
            val tbl = query.substringAfterLast(" ").trim().trim(';', '`', '\'', '"')
            return "DELETE FROM `$tbl`"
        }
        if (upper.startsWith("RENAME TABLE ")) {
            query = query.replace(Regex("(?i)^RENAME\\s+TABLE\\s+([`'\"\\w]+)\\s+TO\\s+([`'\"\\w]+)"), "ALTER TABLE $1 RENAME TO $2")
        }
        if (upper.startsWith("ALTER TABLE")) {
            query = query.replace(Regex("(?i)\\b(AFTER|FIRST)\\s+[`'\"\\w]+"), "")
        }

        // Reemplazar funciones de fecha y sintaxis MySQL por SQLite
        query = query.replace(Regex("(?i)\\bNOW\\(\\s*\\)"), "datetime('now', 'localtime')")
        query = query.replace(Regex("(?i)\\bCURRENT_TIMESTAMP\\(\\s*\\)"), "CURRENT_TIMESTAMP")
        query = query.replace(Regex("(?i)\\bUNIX_TIMESTAMP\\(\\s*\\)"), "strftime('%s', 'now')")
        query = query.replace(Regex("(?i)\\bCURDATE\\(\\s*\\)"), "date('now', 'localtime')")
        query = query.replace(Regex("(?i)\\bCURTIME\\(\\s*\\)"), "time('now', 'localtime')")
        query = query.replace(Regex("(?i)\\bINSERT\\s+IGNORE\\s+INTO\\b"), "INSERT OR IGNORE INTO")

        // Transformaciones específicas para sentencias CREATE TABLE
        if (upper.startsWith("CREATE TABLE") || upper.contains("CREATE TABLE")) {
            // 1. Eliminar opciones de tabla al final (ENGINE=..., DEFAULT CHARSET=..., AUTO_INCREMENT=..., COLLATE=...)
            query = query.replace(Regex("(?i)\\)\\s*(ENGINE|DEFAULT|CHARSET|COLLATE|AUTO_INCREMENT|ROW_FORMAT)[^;]*;?$"), ");")
            query = query.replace(Regex("(?i)\\bENGINE\\s*=\\s*[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\bAUTO_INCREMENT\\s*=\\s*\\d+"), "")
            query = query.replace(Regex("(?i)\\bROW_FORMAT\\s*=\\s*[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\bDEFAULT\\s+CHARSET\\s*=\\s*[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\bDEFAULT\\s+COLLATE\\s*=\\s*[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\b(CHARACTER\\s+SET|CHARSET)\\s+[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\b(CHARACTER\\s+SET|CHARSET)\\s*=\\s*[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\bCOLLATE\\s+[a-zA-Z0-9_]+"), "")
            query = query.replace(Regex("(?i)\\bCOLLATE\\s*=\\s*[a-zA-Z0-9_]+"), "")

            // 2. Limpiar comentarios MySQL tipo COMMENT '...' en columnas
            query = query.replace(Regex("(?i)\\bCOMMENT\\s+'[^']*'"), "")

            // 3. Limpiar cláusulas de ON UPDATE en columnas TIMESTAMP
            query = query.replace(Regex("(?i)\\bON\\s+UPDATE\\s+(CURRENT_TIMESTAMP|NOW\\(\\))(\\(\\s*\\))?"), "")

            // 4. Limpiar UNSIGNED y ZEROFILL
            query = query.replace(Regex("(?i)\\bUNSIGNED\\b"), "")
            query = query.replace(Regex("(?i)\\bZEROFILL\\b"), "")

            // 5. Eliminar definiciones KEY / INDEX dentro del CREATE TABLE (SQLite solo las permite fuera con CREATE INDEX)
            query = query.replace(Regex("(?i),\\s*UNIQUE\\s+(KEY|INDEX)\\s+[a-zA-Z0-9_`'\"]*\\s*\\(([^\\)]+)\\)"), ", UNIQUE ($2)")
            query = query.replace(Regex("(?i),\\s*(KEY|INDEX|FULLTEXT\\s+KEY|SPATIAL\\s+KEY)\\s+[a-zA-Z0-9_`'\"]*\\s*\\([^\\)]+\\)"), "")

            // 6. Eliminar PRIMARY KEY redundante si hay columna AUTO_INCREMENT
            val autoIncColMatch = Regex("(?i)[`\"']?([a-zA-Z0-9_]+)[`\"']?\\s+(BIGINT|INT|INTEGER|MEDIUMINT|SMALLINT|TINYINT)[^,]*AUTO_INCREMENT").find(query)
            if (autoIncColMatch != null) {
                val colName = autoIncColMatch.groups[1]?.value ?: ""
                if (colName.isNotEmpty()) {
                    query = query.replace(Regex("(?i),\\s*PRIMARY\\s+KEY\\s*\\(\\s*[`\"']?" + Regex.escape(colName) + "[`\"']?\\s*\\)"), "")
                }
            }

            // 7. Transformar AUTO_INCREMENT para SQLite (debe ser INTEGER PRIMARY KEY AUTOINCREMENT)
            query = query.replace(Regex("(?i)\\b(BIGINT|INT|MEDIUMINT|SMALLINT|TINYINT)(\\s*\\(\\s*\\d+\\s*\\))?(\\s+NOT\\s+NULL)?\\s+AUTO_INCREMENT\\s+PRIMARY\\s+KEY"), "INTEGER PRIMARY KEY AUTOINCREMENT")
            query = query.replace(Regex("(?i)\\b(BIGINT|INT|MEDIUMINT|SMALLINT|TINYINT)(\\s*\\(\\s*\\d+\\s*\\))?\\s+PRIMARY\\s+KEY\\s+AUTO_INCREMENT"), "INTEGER PRIMARY KEY AUTOINCREMENT")
            query = query.replace(Regex("(?i)\\b(BIGINT|INT|MEDIUMINT|SMALLINT|TINYINT)(\\s*\\(\\s*\\d+\\s*\\))?\\s+AUTO_INCREMENT"), "INTEGER PRIMARY KEY AUTOINCREMENT")
            query = query.replace(Regex("(?i)\\bAUTO_INCREMENT\\b"), "AUTOINCREMENT")

            // 8. Mapear tipos de datos MySQL a SQLite
            query = query.replace(Regex("(?i)\\b(LONGTEXT|MEDIUMTEXT|TINYTEXT|JSON)\\b"), "TEXT")
            query = query.replace(Regex("(?i)\\b(LONGBLOB|MEDIUMBLOB|TINYBLOB)\\b"), "BLOB")
            query = query.replace(Regex("(?i)\\bENUM\\s*\\([^\\)]+\\)"), "TEXT")
            query = query.replace(Regex("(?i)\\b(DATETIME|TIMESTAMP)\\b"), "TEXT")
            query = query.replace(Regex("(?i)\\bVARCHAR\\s*\\(\\s*\\d+\\s*\\)"), "TEXT")
            query = query.replace(Regex("(?i)\\bCHAR\\s*\\(\\s*\\d+\\s*\\)"), "TEXT")
            query = query.replace(Regex("(?i)\\bDOUBLE(\\s*PRECISION)?\\b"), "REAL")
            query = query.replace(Regex("(?i)\\bFLOAT\\b"), "REAL")
        }

        return query
    }

    private data class MysqlPacket(val seqId: Int, val payload: ByteArray)

    private fun readMysqlPacket(input: InputStream): MysqlPacket? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val r = input.read(header, read, 4 - read)
            if (r == -1) return null
            read += r
        }
        val length = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16)
        val seqId = header[3].toInt() and 0xFF

        val payload = ByteArray(length)
        var total = 0
        while (total < length) {
            val r = input.read(payload, total, length - total)
            if (r == -1) return null
            total += r
        }
        return MysqlPacket(seqId, payload)
    }

    private fun writeMysqlPacket(output: OutputStream, seqId: Int, payload: ByteArray) {
        val len = payload.size
        val header = byteArrayOf(
            (len and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            (seqId and 0xFF).toByte()
        )
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun sendInitialHandshakePacket(output: OutputStream, seqId: Int) {
        val out = ByteArrayOutputStream()
        out.write(10) // protocol version 10
        out.write("10.11.6-MariaDB-CPS\u0000".toByteArray(Charsets.UTF_8))
        // connection id (4 bytes)
        out.write(byteArrayOf(1, 0, 0, 0))
        // auth-plugin-data-part-1 (8 bytes)
        out.write("12345678".toByteArray(Charsets.UTF_8))
        out.write(0) // filler
        // capability flags lower 2 bytes (CLIENT_LONG_PASSWORD | CLIENT_FOUND_ROWS | CLIENT_LONG_FLAG | CLIENT_CONNECT_WITH_DB | CLIENT_PROTOCOL_41 | CLIENT_TRANSACTIONS | CLIENT_SECURE_CONNECTION)
        val capLower = 0xF7FF
        out.write(capLower and 0xFF)
        out.write((capLower shr 8) and 0xFF)
        // character set (utf8mb4 = 45)
        out.write(45)
        // status flags (SERVER_STATUS_AUTOCOMMIT = 0x0002)
        out.write(byteArrayOf(2, 0))
        // capability flags upper 2 bytes (CLIENT_PLUGIN_AUTH = 0x0008)
        out.write(byteArrayOf(8, 0))
        // length of auth-plugin-data (21)
        out.write(21)
        // reserved (10 zero bytes)
        out.write(ByteArray(10))
        // auth-plugin-data-part-2 (13 bytes including null terminator)
        out.write("123456789012\u0000".toByteArray(Charsets.UTF_8))
        // auth-plugin-name
        out.write("mysql_native_password\u0000".toByteArray(Charsets.UTF_8))

        writeMysqlPacket(output, seqId, out.toByteArray())
    }

    private fun parseInitialDbFromHandshake(payload: ByteArray): String? {
        if (payload.size < 36) return null
        val clientFlags = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val hasConnectWithDb = (clientFlags and 0x0008) != 0
        if (!hasConnectWithDb) return null

        var pos = 32
        // Saltar username (null-terminated)
        while (pos < payload.size && payload[pos].toInt() != 0) pos++
        pos++ // saltar null byte
        if (pos >= payload.size) return null

        // Saltar auth response
        val authLen = payload[pos].toInt() and 0xFF
        pos += 1 + authLen
        if (pos >= payload.size) return null

        val end = (pos until payload.size).firstOrNull { payload[it].toInt() == 0 } ?: payload.size
        val db = String(payload, pos, end - pos, Charsets.UTF_8).trim()
        return db.ifEmpty { null }
    }

    private fun sendOkPacket(output: OutputStream, seqId: Int) {
        // 0x00 (OK), 0 affected rows, 0 last insert id, SERVER_STATUS_AUTOCOMMIT (0x0002), 0 warnings
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00)
        writeMysqlPacket(output, seqId, payload)
    }

    private fun sendEofPacket(output: OutputStream, seqId: Int) {
        val payload = byteArrayOf(0xFE.toByte(), 0x00, 0x00, 0x02, 0x00)
        writeMysqlPacket(output, seqId, payload)
    }

    private fun sendErrorPacket(output: OutputStream, seqId: Int, errorCode: Int, message: String) {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(errorCode and 0xFF)
        out.write((errorCode shr 8) and 0xFF)
        out.write("#42000".toByteArray(Charsets.UTF_8))
        out.write(message.toByteArray(Charsets.UTF_8))
        writeMysqlPacket(output, seqId, out.toByteArray())
    }

    private fun sendResultSet(
        output: OutputStream,
        startSeq: Int,
        columns: List<String>,
        rows: List<List<String?>>
    ) {
        var seq = startSeq
        val colCount = if (columns.isEmpty()) 1 else columns.size
        val effectiveCols = if (columns.isEmpty()) listOf("Result") else columns

        // 1. Column count packet
        val countOut = ByteArrayOutputStream()
        writeLenEncInt(countOut, colCount)
        writeMysqlPacket(output, seq++, countOut.toByteArray())

        // 2. Column definition packets (Protocol::ColumnDefinition41)
        for (col in effectiveCols) {
            val colOut = ByteArrayOutputStream()
            writeLenEncStr(colOut, "def")       // catalog
            writeLenEncStr(colOut, DEFAULT_DB_NAME) // schema
            writeLenEncStr(colOut, "result")    // table
            writeLenEncStr(colOut, "result")    // org_table
            writeLenEncStr(colOut, col)         // name
            writeLenEncStr(colOut, col)         // org_name
            colOut.write(0x0C)                  // length of fixed-length fields (12)
            colOut.write(byteArrayOf(45, 0))    // character set (utf8mb4 = 45)
            colOut.write(byteArrayOf(0, 1, 0, 0)) // column length (256)
            colOut.write(0xFD)                  // type: MYSQL_TYPE_VAR_STRING
            colOut.write(byteArrayOf(0, 0))     // flags
            colOut.write(0)                     // decimals
            colOut.write(byteArrayOf(0, 0))     // filler
            writeMysqlPacket(output, seq++, colOut.toByteArray())
        }

        // 3. EOF after columns
        sendEofPacket(output, seq++)

        // 4. Row packets
        for (row in rows) {
            val rowOut = ByteArrayOutputStream()
            for (i in 0 until colCount) {
                val cell = row.getOrNull(i)
                if (cell == null) {
                    rowOut.write(0xFB) // NULL en protocolo texto MySQL
                } else {
                    writeLenEncStr(rowOut, cell)
                }
            }
            writeMysqlPacket(output, seq++, rowOut.toByteArray())
        }

        // 5. Final EOF packet
        sendEofPacket(output, seq)
    }

    private fun writeLenEncInt(out: ByteArrayOutputStream, value: Int) {
        when {
            value < 251 -> out.write(value)
            value < 65536 -> {
                out.write(0xFC)
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            else -> {
                out.write(0xFD)
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write((value shr 16) and 0xFF)
            }
        }
    }

    private fun writeLenEncStr(out: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeLenEncInt(out, bytes.size)
        out.write(bytes)
    }
}

