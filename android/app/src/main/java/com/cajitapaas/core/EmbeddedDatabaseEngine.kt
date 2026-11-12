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
            if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("SHOW") || upper.startsWith("DESCRIBE") || upper.startsWith("EXPLAIN")) {
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
                db.execSQL(trimmedSql)
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
    }

    @Synchronized
    fun stopBridge() {
        isBridgeEnabled = false
        runCatching { bridgeServerSocket?.close() }
        bridgeServerSocket = null
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

            // 1. Intentar conectar al demonio MariaDB local (127.0.0.1:3306) si está corriendo en PRoot/Docker
            val localMariaDb = runCatching {
                Socket().apply { connect(InetSocketAddress("127.0.0.1", 3306), 400) }
            }.getOrNull()

            if (localMariaDb != null) {
                openSockets.add(localMariaDb)
                val t1 = thread {
                    runCatching { clientSocket.getInputStream().copyTo(localMariaDb.getOutputStream()) }
                    runCatching { localMariaDb.close() }
                    runCatching { clientSocket.close() }
                }
                runCatching { localMariaDb.getInputStream().copyTo(clientSocket.getOutputStream()) }
                t1.join(1000)
                runCatching { localMariaDb.close() }
            } else {
                // 2. Responder directamente usando el protocolo de red MySQL/MariaDB v10
                handleMySqlWireProtocolSession(clientSocket)
            }
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
        val clean = rawQuery.trim().removeSuffix(";").trim()
        val upper = clean.uppercase()

        try {
            // Comandos de sesión e inspección de metadatos que DBeaver/JDBC envía al conectar
            if (upper.startsWith("SET ") || upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK") || upper.startsWith("START TRANSACTION") || upper.startsWith("BEGIN")) {
                sendOkPacket(output, startSeq)
                return currentDb
            }

            if (upper.startsWith("USE ")) {
                val targetDb = sanitizeDbName(clean.substring(4).trim().trim('`', '\'', '"'))
                openOrCreateDatabase(targetDb).close()
                sendOkPacket(output, startSeq)
                return targetDb
            }

            if (upper.startsWith("SELECT @@") || upper.contains("@@VERSION") || upper.contains("VERSION()")) {
                sendResultSet(
                    output = output,
                    startSeq = startSeq,
                    columns = listOf("@@version", "@@version_comment", "@@tx_isolation", "@@session.auto_increment_increment", "DATABASE()"),
                    rows = listOf(listOf("10.11.6-MariaDB-CPS", "CajitaPaaS Android Embedded MariaDB", "REPEATABLE-READ", "1", currentDb))
                )
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
                val tbl = clean.substringAfterLast(" ").trim('`', '\'', '"').substringAfterLast('.')
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
            openOrCreateDatabase(currentDb).use { db ->
                db.execSQL(clean)
            }
            sendOkPacket(output, startSeq)
        } catch (e: Exception) {
            sendErrorPacket(output, startSeq, 1064, e.message ?: "Error ejecutando SQL")
        }
        return currentDb
    }

    private fun translateMysqlQueryToSqlite(sql: String): String {
        val upper = sql.trim().uppercase()
        if (upper == "SHOW DATABASES") {
            return "SELECT '$DEFAULT_DB_NAME' AS Database"
        }
        if (upper.startsWith("SHOW TABLES")) {
            return "SELECT name AS Tables_in_db FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
        }
        return sql
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

