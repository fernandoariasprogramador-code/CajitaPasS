'use strict';

require('dotenv').config();

const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const multer = require('multer');
const AdmZip = require('adm-zip');
const simpleGit = require('simple-git');
const pm2 = require('pm2');
const mysql = require('mysql2/promise');
const path = require('path');
const fs = require('fs/promises');
const net = require('net');
const os = require('os');
const { execFile, spawn } = require('child_process');
const { promisify } = require('util');

const execFileAsync = promisify(execFile);

// ============================================================================
// 1. VALIDACIÓN ESTRICTA DE SECRETOS (.env)
// ============================================================================
const REQUIRED_ENV = [
  'JWT_SECRET',
  'MASTER_API_KEY',
  'DB_HOST',
  'DB_USER',
  'DB_PASSWORD',
  'DB_NAME'
];

for (const key of REQUIRED_ENV) {
  if (!process.env[key]) {
    console.error(`[FATAL] Variable obligatoria faltante en .env: ${key}`);
    process.exit(1);
  }
}

const PORT = Number(process.env.CONTROL_PLANE_PORT || 4000);
const CADDY_ADMIN_URL = process.env.CADDY_ADMIN_URL || 'http://127.0.0.1:2019';
const APPS_BASE_DIR = path.resolve(process.env.APPS_BASE_DIR || '/opt/cajitapaas/apps');
const UPLOADS_DIR = path.resolve(process.env.UPLOADS_DIR || '/opt/cajitapaas/uploads');
const ADMIN_AUTH_FILE = path.resolve(__dirname, '.admin-auth.json');

// Estado persistente de la contraseña de administrador creada en el primer ingreso
let adminAuthState = {
  initialized: false,
  salt: null,
  passwordHash: null,
  createdAt: null
};

async function loadAdminAuthState() {
  try {
    const raw = await fs.readFile(ADMIN_AUTH_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    if (parsed && parsed.initialized && parsed.salt && parsed.passwordHash) {
      adminAuthState = parsed;
    }
  } catch (_e) {
    adminAuthState = { initialized: false, salt: null, passwordHash: null, createdAt: null };
  }
}

function hashAdminPassword(password, saltHex) {
  return crypto.scryptSync(String(password), Buffer.from(saltHex, 'hex'), 64).toString('hex');
}

function verifyAdminPassword(password) {
  if (!adminAuthState.initialized || !adminAuthState.salt || !adminAuthState.passwordHash) {
    return false;
  }
  const candidateHex = hashAdminPassword(password, adminAuthState.salt);
  return safeCompare(candidateHex, adminAuthState.passwordHash);
}

// Pool de conexiones MariaDB (exclusivamente desde .env)
const dbPool = mysql.createPool({
  host: process.env.DB_HOST,
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME,
  waitForConnections: true,
  connectionLimit: 5,
  enableKeepAlive: true
});

// Estado en memoria sincronizado con BD para slots Blue/Green por aplicación (Multi-App)
// Estructura:
// {
//   [appName]: {
//     appRole: 'frontend' | 'backend' | 'fullstack',
//     routePath: '/' | '/api/*' | '/app2/*',
//     bluePort: number,
//     greenPort: number,
//     activeColor: 'blue' | 'green',
//     activePort: number
//   }
// }
const deploymentRegistry = new Map();
const BASE_APP_PORT = 3001; // Cada app recibe un par exclusivo: (3001,3002), (3003,3004), (3005,3006)...

function allocatePortPairForApp(appName) {
  const existing = deploymentRegistry.get(appName);
  if (existing && existing.bluePort && existing.greenPort) {
    return { bluePort: existing.bluePort, greenPort: existing.greenPort };
  }
  const usedPorts = new Set();
  for (const info of deploymentRegistry.values()) {
    usedPorts.add(info.bluePort);
    usedPorts.add(info.greenPort);
  }
  let candidate = BASE_APP_PORT;
  while (usedPorts.has(candidate) || usedPorts.has(candidate + 1)) {
    candidate += 2;
  }
  return { bluePort: candidate, greenPort: candidate + 1 };
}

function sanitizeRoutePath(rawPath) {
  const p = String(rawPath || '/').trim();
  if (!/^\/[a-zA-Z0-9/_*-]*$/.test(p)) {
    throw new Error('Ruta de Ingress inválida. Ejemplos válidos: /, /api/*, /ventas/*');
  }
  return p;
}

// ============================================================================
// 2. CONFIGURACIÓN EXPRESS + DEVSECOPS (HELMET, RATE-LIMIT, AUTH)
// ============================================================================
const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1); // Necesario detrás de Caddy / Cloudflared

// Cabeceras de seguridad estrictas
app.use(
  helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        imgSrc: ["'self'", 'data:'],
        styleSrc: ["'self'", "'unsafe-inline'"],
        scriptSrc: ["'self'"],
        frameAncestors: ["'none'"]
      }
    },
    hsts: {
      maxAge: 31536000,
      includeSubDomains: true,
      preload: true
    }
  })
);

app.use(express.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Limitador global y limitador estricto para despliegues/autenticación
const globalLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiadas peticiones desde esta IP. Intente más tarde.' }
});

const strictDeployLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Límite de despliegues/autenticación excedido. Bloqueo preventivo activo.' }
});

app.use(globalLimiter);

/**
 * Comparación en tiempo constante para prevenir Timing Attacks en API Keys
 */
function safeCompare(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * Middleware de Autenticación Estricta:
 * Soporta Bearer JWT o cabecera X-API-Key con comparación criptográfica en tiempo constante.
 */
function requireStrictAuth(req, res, next) {
  const apiKeyHeader = req.headers['x-api-key'];
  if (
    apiKeyHeader &&
    (verifyAdminPassword(apiKeyHeader) || safeCompare(apiKeyHeader, process.env.MASTER_API_KEY))
  ) {
    req.user = { sub: 'paas-admin', role: 'admin' };
    return next();
  }

  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'No autorizado. Se requiere sesión activa.' });
  }

  const token = authHeader.slice(7);
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET, {
      algorithms: ['HS256'],
      issuer: 'cajitapaas-control-plane'
    });
    req.user = decoded;
    return next();
  } catch (err) {
    return res.status(403).json({ error: 'Token JWT inválido o expirado.' });
  }
}

// Config de subida de artefactos .zip (máx 100MB, sanitizado)
const upload = multer({
  dest: UPLOADS_DIR,
  limits: { fileSize: 100 * 1024 * 1024 },
  fileFilter: (_req, file, cb) => {
    if (!file.originalname.endsWith('.zip')) {
      return cb(new Error('Solo se permiten paquetes .zip'));
    }
    cb(null, true);
  }
});

// ============================================================================
// 3. HELPERS DE ORQUESTACIÓN (PM2 + CADDY + HEALTH CHECK + BUILDPACKS)
// ============================================================================

function sanitizeAppName(name) {
  if (!/^[a-z0-9-]{2,32}$/.test(name)) {
    throw new Error('Nombre de aplicación inválido. Use solo [a-z0-9-] (2 a 32 caracteres).');
  }
  return name;
}

/**
 * Detecta automáticamente el lenguaje del proyecto y devuelve el comando de build/start
 */
async function resolveRuntimeCommand(appDir, port) {
  const files = await fs.readdir(appDir);

  if (files.includes('package.json')) {
    const pkgRaw = await fs.readFile(path.join(appDir, 'package.json'), 'utf8');
    const pkg = JSON.parse(pkgRaw);
    await execFileAsync('npm', ['ci', '--include=dev'], { cwd: appDir });

    // Si tiene script de build (ej. React/Vite/Vue/Next/Nest), compilar primero
    if (pkg.scripts && pkg.scripts.build) {
      await execFileAsync('npm', ['run', 'build'], { cwd: appDir });
    }

    // Si tras el build existe dist/index.html o build/index.html y no hay server start propio,
    // servir como SPA Frontend estático con Caddy/npx serve
    const distDir = files.includes('dist') ? 'dist' : files.includes('build') ? 'build' : null;
    if (distDir && (!pkg.scripts || !pkg.scripts.start)) {
      return {
        interpreter: 'none',
        script: 'npx',
        args: ['serve', '-s', distDir, '-l', String(port)],
        env: { PORT: String(port) }
      };
    }

    const entryFile = pkg.main || 'index.js';
    return { interpreter: 'node', script: entryFile, args: [], env: { PORT: String(port) } };
  }
  // Soporte directo para Frontends Estáticos compilados (HTML/JS/CSS en ZIP)
  if (files.includes('index.html')) {
    return {
      interpreter: 'none',
      script: 'npx',
      args: ['serve', '-s', '.', '-l', String(port)],
      env: { PORT: String(port) }
    };
  }
  if (files.includes('go.mod')) {
    const binPath = path.join(appDir, 'app-bin');
    await execFileAsync('go', ['build', '-o', binPath, '.'], { cwd: appDir });
    return { interpreter: 'none', script: binPath, args: [], env: { PORT: String(port) } };
  }
  if (files.includes('requirements.txt')) {
    await execFileAsync('pip3', ['install', '-r', 'requirements.txt'], { cwd: appDir });
    return { interpreter: 'python3', script: 'app.py', args: [], env: { PORT: String(port) } };
  }
  if (files.some((f) => f.endsWith('.dll'))) {
    const dll = files.find((f) => f.endsWith('.dll'));
    return {
      interpreter: 'dotnet',
      script: path.join(appDir, dll),
      args: [],
      env: { ASPNETCORE_URLS: `http://127.0.0.1:${port}`, PORT: String(port) }
    };
  }
  throw new Error('No se detectó un runtime soportado (package.json, go.mod, requirements.txt o .dll)');
}

function pm2StartAsync(config) {
  return new Promise((resolve, reject) => {
    pm2.connect((err) => {
      if (err) return reject(err);
      pm2.start(config, (startErr, proc) => {
        pm2.disconnect();
        if (startErr) return reject(startErr);
        resolve(proc);
      });
    });
  });
}

function pm2DeleteAsync(processName) {
  return new Promise((resolve) => {
    pm2.connect((err) => {
      if (err) return resolve();
      pm2.delete(processName, () => {
        pm2.disconnect();
        resolve();
      });
    });
  });
}

/**
 * Ejecuta Health Check HTTP contra la nueva versión (v2) antes de conmutar el tráfico
 */
async function waitForHealthyInstance(port, healthPath = '/health', retries = 15, delayMs = 1000) {
  const targetUrl = `http://127.0.0.1:${port}${healthPath}`;
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 1500);
      const response = await fetch(targetUrl, { signal: controller.signal });
      clearTimeout(timeout);

      if (response.ok) {
        return true;
      }
    } catch (_err) {
      // Sigue esperando a que el proceso abra el socket
    }
    await new Promise((r) => setTimeout(r, delayMs));
  }
  return false;
}

/**
 * Regenera el Caddyfile combinando TODOS los proyectos activos (Frontends + Backends + Microservicios)
 * y ejecuta Zero-Downtime Reload en Caddy.
 */
async function rebuildCaddyMultiAppConfig() {
  const entries = Array.from(deploymentRegistry.entries()).sort((a, b) => {
    const pathA = a[1].routePath || '/';
    const pathB = b[1].routePath || '/';
    if (pathA === '/') return 1;
    if (pathB === '/') return -1;
    return pathB.length - pathA.length;
  });

  const routeBlocks = entries
    .map(([appName, info]) => {
      const route = info.routePath || '/';
      if (route === '/' || route === '/*') {
        return `    # App [${appName}] (${info.appRole || 'app'} - slot ${info.activeColor} :${info.activePort})
    handle {
        reverse_proxy 127.0.0.1:${info.activePort} {
            health_uri ${info.healthPath || '/health'}
            health_interval 10s
        }
    }`;
      }
      return `    # App [${appName}] (${info.appRole || 'app'} - slot ${info.activeColor} :${info.activePort})
    handle ${route} {
        reverse_proxy 127.0.0.1:${info.activePort} {
            health_uri ${info.healthPath || '/health'}
            health_interval 10s
        }
    }`;
    })
    .join('\n\n');

  const caddyfileContent = `{
    admin 127.0.0.1:2019
    auto_https disable_redirects
}

:8080 {
    encode zstd gzip
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        -Server
    }

    handle_path /_control/* {
        reverse_proxy 127.0.0.1:${PORT}
    }

${routeBlocks}
}
`;

  // Recarga en caliente mediante la API administrativa local de Caddy (0ms de caída)
  const res = await fetch(`${CADDY_ADMIN_URL}/load`, {
    method: 'POST',
    headers: {
      'Content-Type': 'text/caddyfile',
      'Cache-Control': 'must-revalidate'
    },
    body: caddyfileContent
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Fallo al recargar Caddy Zero-Downtime: ${errText}`);
  }
}

/**
 * Orquestador central de despliegue Blue/Green Multi-App (Zero-Downtime)
 */
async function executeBlueGreenDeployment({
  appName,
  appRole = 'backend',
  routePath = '/',
  sourceSetupFn,
  healthPath = '/health'
}) {
  const cleanRoute = sanitizeRoutePath(routePath);
  const { bluePort, greenPort } = allocatePortPairForApp(appName);
  const currentState = deploymentRegistry.get(appName) || {
    activeColor: 'green',
    activePort: greenPort,
    bluePort,
    greenPort
  };

  // 1. Determinar el entorno candidato (v2) en el par de puertos exclusivo de esta app
  const nextColor = currentState.activeColor === 'blue' ? 'green' : 'blue';
  const nextPort = nextColor === 'blue' ? bluePort : greenPort;
  const prevColor = currentState.activeColor;
  const prevProcessName = `${appName}-${prevColor}`;
  const nextProcessName = `${appName}-${nextColor}`;

  const nextReleaseDir = path.join(APPS_BASE_DIR, appName, nextColor);

  // Limpiar y preparar directorio del slot candidato
  await fs.rm(nextReleaseDir, { recursive: true, force: true });
  await fs.mkdir(nextReleaseDir, { recursive: true });

  // Poblar el código fuente (vía ZIP o Git Pull/Clone)
  await sourceSetupFn(nextReleaseDir);

  // Detectar runtime e instalar dependencias
  const runtimeSpec = await resolveRuntimeCommand(nextReleaseDir, nextPort);

  // Asegurar que no exista un proceso zombi previo en el slot candidato
  await pm2DeleteAsync(nextProcessName);

  // 2. Levantar la nueva versión (v2) en el puerto alternativo
  await pm2StartAsync({
    name: nextProcessName,
    cwd: nextReleaseDir,
    script: runtimeSpec.script,
    interpreter: runtimeSpec.interpreter,
    args: runtimeSpec.args,
    env: {
      ...runtimeSpec.env,
      NODE_ENV: 'production',
      DB_HOST: process.env.DB_HOST,
      DB_PORT: process.env.DB_PORT || '3306',
      DB_NAME: process.env.DB_NAME,
      DB_USER: process.env.DB_USER,
      DB_PASSWORD: process.env.DB_PASSWORD
    }
  });

  // 3. Realizar Health Check interno HTTP
  const isHealthy = await waitForHealthyInstance(nextPort, healthPath);
  if (!isHealthy) {
    // Rollback automático inmediato: destruir v2 fallida y mantener v1 intacta
    await pm2DeleteAsync(nextProcessName);
    const rollbackError = new Error(
      `Health Check falló en ${nextProcessName} (127.0.0.1:${nextPort}${healthPath}). Rollback ejecutado; versión anterior sigue activa.`
    );
    rollbackError.code = 'HEALTHCHECK_ROLLBACK';
    rollbackError.rolledBack = true;
    throw rollbackError;
  }

  // 4. Si está OK, actualizar registro Multi-App, reescribir reglas de Caddy y hacer reload sin caídas
  const newState = {
    appRole,
    routePath: cleanRoute,
    healthPath,
    bluePort,
    greenPort,
    activeColor: nextColor,
    activePort: nextPort,
    updatedAt: new Date().toISOString()
  };
  deploymentRegistry.set(appName, newState);

  await rebuildCaddyMultiAppConfig();

  // 5. Apagar y limpiar la versión antigua (v1)
  if (currentState.activeColor) {
    await pm2DeleteAsync(prevProcessName);
    const prevReleaseDir = path.join(APPS_BASE_DIR, appName, prevColor);
    await fs.rm(prevReleaseDir, { recursive: true, force: true }).catch(() => {});
  }

  await dbPool
    .execute(
      `INSERT INTO deployments_audit (app_name, active_color, active_port, deployed_at)
       VALUES (?, ?, ?, NOW())`,
      [appName, nextColor, nextPort]
    )
    .catch((err) => console.warn('[WARN] No se pudo registrar auditoría en MariaDB:', err.message));

  return {
    appName,
    appRole,
    routePath: cleanRoute,
    deployedSlot: nextColor,
    activePort: nextPort,
    portPair: { blue: bluePort, green: greenPort },
    terminatedSlot: prevColor
  };
}

// ============================================================================
// 4. ENDPOINTS DEL CONTROL PLANE
// ============================================================================

/**
 * Verificar si es la primera vez que se abre la app (Onboarding de contraseña Admin)
 */
app.get('/api/auth/setup-status', async (_req, res) => {
  await loadAdminAuthState();
  return res.json({ initialized: Boolean(adminAuthState.initialized) });
});

/**
 * Configurar la contraseña de administrador por primera vez
 */
app.post('/api/auth/setup', strictDeployLimiter, async (req, res) => {
  await loadAdminAuthState();
  if (adminAuthState.initialized) {
    return res.status(400).json({ error: 'La contraseña de administrador ya fue configurada anteriormente.' });
  }

  const password = String(req.body?.password || '').trim();
  if (password.length < 6) {
    return res.status(400).json({ error: 'La contraseña debe tener al menos 6 caracteres.' });
  }

  const salt = crypto.randomBytes(16).toString('hex');
  const passwordHash = hashAdminPassword(password, salt);

  adminAuthState = {
    initialized: true,
    salt,
    passwordHash,
    createdAt: new Date().toISOString()
  };

  await fs.writeFile(ADMIN_AUTH_FILE, JSON.stringify(adminAuthState, null, 2), { mode: 0o600 });

  const token = jwt.sign(
    { sub: 'paas-admin', role: 'cluster-admin' },
    process.env.JWT_SECRET,
    { algorithm: 'HS256', expiresIn: '12h', issuer: 'cajitapaas-control-plane' }
  );

  return res.status(201).json({
    message: 'Contraseña de administrador creada con éxito.',
    token,
    expiresIn: 43200
  });
});

/**
 * Emisión de JWT para administradores del panel web
 */
app.post(['/api/v1/auth/token', '/api/auth/login', '/api/login'], strictDeployLimiter, async (req, res) => {
  await loadAdminAuthState();
  const secretInput = String(req.body?.apiKey || req.body?.password || '').trim();

  const isValidUserPass = verifyAdminPassword(secretInput);
  const isValidMasterKey = secretInput && safeCompare(secretInput, process.env.MASTER_API_KEY);

  if (!isValidUserPass && !isValidMasterKey) {
    return res.status(401).json({ error: 'La contraseña ingresada no es correcta.' });
  }

  const token = jwt.sign(
    { sub: 'paas-admin', role: 'cluster-admin' },
    process.env.JWT_SECRET,
    { algorithm: 'HS256', expiresIn: '12h', issuer: 'cajitapaas-control-plane' }
  );

  return res.json({ token, expiresIn: 43200 });
});

/**
 * Estado de salud del Control Plane y conexión a MariaDB
 */
app.get(['/api/v1/status', '/api/status'], requireStrictAuth, async (_req, res) => {
  let dbHealthy = false;
  try {
    const conn = await dbPool.getConnection();
    await conn.ping();
    conn.release();
    dbHealthy = true;
  } catch (_e) {
    dbHealthy = false;
  }

  return res.json({
    system: 'CPS (CajitaPaaS)',
    status: 'online',
    mariadbConnected: dbHealthy,
    activeDeployments: Object.fromEntries(deploymentRegistry)
  });
});

/**
 * Listar historial de despliegues y procesos activos en PM2
 */
app.get('/api/v1/projects', requireStrictAuth, async (_req, res) => {
  try {
    const [auditRows] = await dbPool
      .query('SELECT id, app_name, active_color, active_port, deployed_at FROM deployments_audit ORDER BY id DESC LIMIT 25')
      .catch(() => [[]]);

    const pm2Processes = await new Promise((resolve) => {
      pm2.connect((err) => {
        if (err) return resolve([]);
        pm2.list((listErr, list) => {
          pm2.disconnect();
          if (listErr || !Array.isArray(list)) return resolve([]);
          resolve(
            list.map((p) => ({
              name: p.name,
              pid: p.pid,
              status: p.pm2_env?.status,
              cpu: p.monit?.cpu,
              memoryMb: p.monit?.memory ? Math.round(p.monit.memory / (1024 * 1024)) : 0,
              uptimeMs: p.pm2_env?.pm_uptime ? Date.now() - p.pm2_env.pm_uptime : 0
            }))
          );
        });
      });
    });

    return res.json({
      activeSlots: Object.fromEntries(deploymentRegistry),
      pm2Processes,
      recentAudit: auditRows
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/**
 * Detener/Eliminar un proyecto desplegado
 */
app.delete('/api/v1/projects/:appName', strictDeployLimiter, requireStrictAuth, async (req, res) => {
  try {
    const appName = sanitizeAppName(req.params.appName);
    await pm2DeleteAsync(`${appName}-blue`);
    await pm2DeleteAsync(`${appName}-green`);
    deploymentRegistry.delete(appName);
    return res.json({ message: `Proyecto ${appName} detenido en ambos slots.` });
  } catch (err) {
    return res.status(400).json({ error: err.message });
  }
});

// ============================================================================
// 5. ADMINISTRACIÓN WEB DE BASE DE DATOS MARIADB (CPS DB STUDIO)
// ============================================================================

const SYSTEM_DATABASES = new Set(['information_schema', 'performance_schema', 'mysql', 'sys']);

function sanitizeIdentifier(id) {
  if (!/^[a-zA-Z0-9_]{1,64}$/.test(String(id || ''))) {
    throw new Error('Identificador SQL inválido. Solo se permite [a-zA-Z0-9_] (máx 64 caracteres).');
  }
  return id;
}

/**
 * Listar todas las bases de datos y sus tablas en MariaDB
 */
app.get('/api/v1/db/databases', requireStrictAuth, async (_req, res) => {
  try {
    const [dbs] = await dbPool.query('SHOW DATABASES');
    const result = [];

    for (const row of dbs) {
      const dbName = Object.values(row)[0];
      const isSystem = SYSTEM_DATABASES.has(dbName);
      let tables = [];
      if (!isSystem) {
        try {
          const [tblRows] = await dbPool.query(`SHOW TABLES FROM \`${dbName}\``);
          tables = tblRows.map((t) => Object.values(t)[0]);
        } catch (_e) {
          tables = [];
        }
      }
      result.push({ name: dbName, isSystem, tables });
    }

    return res.json({ databases: result });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/**
 * Crear una nueva base de datos y opcionalmente un usuario aislado para una app
 */
app.post('/api/v1/db/databases', strictDeployLimiter, requireStrictAuth, async (req, res) => {
  try {
    const dbName = sanitizeIdentifier(req.body.dbName);
    await dbPool.query(`CREATE DATABASE IF NOT EXISTS \`${dbName}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);

    let createdUser = null;
    if (req.body.appUser && req.body.appPassword) {
      const appUser = sanitizeIdentifier(req.body.appUser);
      const appPassword = String(req.body.appPassword);
      if (appPassword.length < 8) {
        return res.status(400).json({ error: 'La contraseña del usuario de BD debe tener al menos 8 caracteres.' });
      }
      await dbPool.query(`CREATE USER IF NOT EXISTS ?@'127.0.0.1' IDENTIFIED BY ?`, [appUser, appPassword]);
      await dbPool.query(`GRANT ALL PRIVILEGES ON \`${dbName}\`.* TO ?@'127.0.0.1'`, [appUser]);
      await dbPool.query('FLUSH PRIVILEGES');
      createdUser = `${appUser}@127.0.0.1`;
    }

    return res.status(201).json({
      message: `Base de datos '${dbName}' aprovisionada con éxito.`,
      database: dbName,
      user: createdUser
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/**
 * Consola SQL Administrativa (Permite consultar y administrar tablas de una BD seleccionada)
 */
app.post('/api/v1/db/query', strictDeployLimiter, requireStrictAuth, async (req, res) => {
  let conn;
  try {
    const database = sanitizeIdentifier(req.body.database || process.env.DB_NAME);
    const sql = String(req.body.sql || '').trim();

    if (!sql) {
      return res.status(400).json({ error: 'La consulta SQL no puede estar vacía.' });
    }

    conn = await dbPool.getConnection();
    await conn.changeUser({ database });
    const [rows, fields] = await conn.query(sql);

    return res.json({
      database,
      columns: Array.isArray(fields) ? fields.map((f) => f.name) : [],
      rows: Array.isArray(rows) ? rows.slice(0, 200) : rows
    });
  } catch (err) {
    return res.status(400).json({ error: err.message });
  } finally {
    if (conn) conn.release();
  }
});

// ============================================================================
// 6. PUENTE TCP EFÍMERO (JUST-IN-TIME) PARA DBEAVER / CLIENTES EXTERNOS
// ============================================================================
// Mantiene MariaDB escuchando solo en 127.0.0.1:3306 y abre bajo demanda un
// proxy TCP autenticado por IP/Tiempo o vinculado al Heartbeat de la Web Admin.

const DBEAVER_BRIDGE_PORT = Number(process.env.DBEAVER_BRIDGE_PORT || 13306);
const SESSION_HEARTBEAT_TIMEOUT_MS = 45 * 1000; // Si la web se cierra, en máx 45s cae el puente

const dbeaverBridge = {
  enabled: false,
  mode: 'session', // 'session' (mientras la web esté abierta) | 'ttl' (por tiempo fijo)
  allowedIp: null, // null = cualquier IP en la red, o IP específica del administrador
  expiresAt: null,
  lastHeartbeatAt: null,
  server: null,
  sockets: new Set()
};

function getDeviceLanIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const netInfo of nets[name] || []) {
      if (netInfo.family === 'IPv4' && !netInfo.internal) {
        return netInfo.address;
      }
    }
  }
  return '127.0.0.1';
}

function normalizeIp(rawIp) {
  if (!rawIp) return '';
  return String(rawIp).replace(/^::ffff:/, '').trim();
}

function stopDbeaverBridge(reason = 'manual') {
  if (dbeaverBridge.server) {
    try {
      dbeaverBridge.server.close();
    } catch (_e) {}
    dbeaverBridge.server = null;
  }
  for (const sock of dbeaverBridge.sockets) {
    try {
      sock.destroy();
    } catch (_e) {}
  }
  dbeaverBridge.sockets.clear();
  dbeaverBridge.enabled = false;
  dbeaverBridge.expiresAt = null;
  dbeaverBridge.lastHeartbeatAt = null;
  dbeaverBridge.allowedIp = null;
  console.log(`[DBEAVER-BRIDGE] Puente TCP (:${DBEAVER_BRIDGE_PORT}) CERRADO. Motivo: ${reason}`);
}

function startDbeaverBridge({ mode, ttlMinutes, allowedIp }) {
  stopDbeaverBridge('reconfiguracion');

  const server = net.createServer((clientSocket) => {
    const remoteIp = normalizeIp(clientSocket.remoteAddress);

    // Verificar filtro de IP si el administrador lo activó
    if (
      dbeaverBridge.allowedIp &&
      remoteIp !== dbeaverBridge.allowedIp &&
      remoteIp !== '127.0.0.1' &&
      remoteIp !== '::1'
    ) {
      console.warn(`[DBEAVER-BRIDGE] Conexión rechazada desde IP no autorizada: ${remoteIp}`);
      clientSocket.destroy();
      return;
    }

    const dbSocket = net.connect(
      Number(process.env.DB_PORT || 3306),
      process.env.DB_HOST || '127.0.0.1'
    );

    dbeaverBridge.sockets.add(clientSocket);
    dbeaverBridge.sockets.add(dbSocket);

    clientSocket.pipe(dbSocket);
    dbSocket.pipe(clientSocket);

    const cleanup = () => {
      dbeaverBridge.sockets.delete(clientSocket);
      dbeaverBridge.sockets.delete(dbSocket);
      clientSocket.destroy();
      dbSocket.destroy();
    };

    clientSocket.on('error', cleanup);
    clientSocket.on('close', cleanup);
    dbSocket.on('error', cleanup);
    dbSocket.on('close', cleanup);
  });

  server.listen(DBEAVER_BRIDGE_PORT, '0.0.0.0', () => {
    console.log(
      `[DBEAVER-BRIDGE] Escuchando en 0.0.0.0:${DBEAVER_BRIDGE_PORT} -> MariaDB 127.0.0.1:3306 (Modo: ${mode})`
    );
  });

  dbeaverBridge.server = server;
  dbeaverBridge.enabled = true;
  dbeaverBridge.mode = mode === 'ttl' ? 'ttl' : 'session';
  dbeaverBridge.allowedIp = allowedIp || null;
  dbeaverBridge.lastHeartbeatAt = Date.now();
  dbeaverBridge.expiresAt =
    dbeaverBridge.mode === 'ttl'
      ? Date.now() + Math.min(Math.max(Number(ttlMinutes) || 15, 1), 240) * 60 * 1000
      : null;
}

// Watchdog cada 5 segundos: apaga el puente si venció el tiempo o si se cerró la web administrativa
setInterval(() => {
  if (!dbeaverBridge.enabled) return;

  const now = Date.now();
  if (dbeaverBridge.mode === 'ttl' && dbeaverBridge.expiresAt && now >= dbeaverBridge.expiresAt) {
    stopDbeaverBridge('tiempo_expirado_ttl');
  } else if (
    dbeaverBridge.mode === 'session' &&
    dbeaverBridge.lastHeartbeatAt &&
    now - dbeaverBridge.lastHeartbeatAt > SESSION_HEARTBEAT_TIMEOUT_MS
  ) {
    stopDbeaverBridge('web_administrativa_cerrada');
  }
}, 5000).unref();

function getBridgeStatusPayload() {
  const now = Date.now();
  let remainingSeconds = null;
  if (dbeaverBridge.enabled) {
    if (dbeaverBridge.mode === 'ttl' && dbeaverBridge.expiresAt) {
      remainingSeconds = Math.max(0, Math.round((dbeaverBridge.expiresAt - now) / 1000));
    } else if (dbeaverBridge.mode === 'session' && dbeaverBridge.lastHeartbeatAt) {
      remainingSeconds = Math.max(
        0,
        Math.round((dbeaverBridge.lastHeartbeatAt + SESSION_HEARTBEAT_TIMEOUT_MS - now) / 1000)
      );
    }
  }

  return {
    enabled: dbeaverBridge.enabled,
    mode: dbeaverBridge.mode,
    host: getDeviceLanIp(),
    port: DBEAVER_BRIDGE_PORT,
    allowedIp: dbeaverBridge.allowedIp,
    remainingSeconds,
    activeConnections: Math.floor(dbeaverBridge.sockets.size / 2)
  };
}

/**
 * Consultar estado del Switch DBeaver
 */
app.get('/api/v1/db/bridge/status', requireStrictAuth, (_req, res) => {
  return res.json(getBridgeStatusPayload());
});

/**
 * Activar o Desactivar el Switch de acceso externo para DBeaver
 */
app.post('/api/v1/db/bridge/toggle', strictDeployLimiter, requireStrictAuth, (req, res) => {
  const { enabled, mode = 'session', ttlMinutes = 15, restrictToMyIp = false } = req.body || {};

  if (!enabled) {
    stopDbeaverBridge('apagado_por_usuario');
    return res.json({
      message: 'Acceso externo para DBeaver desactivado. Puerto cerrado.',
      bridge: getBridgeStatusPayload()
    });
  }

  const callerIp = restrictToMyIp ? normalizeIp(req.ip || req.socket?.remoteAddress) : null;
  startDbeaverBridge({
    mode,
    ttlMinutes,
    allowedIp: callerIp
  });

  return res.json({
    message:
      mode === 'session'
        ? 'Acceso DBeaver activo mientras mantenga abierta la Web Administrativa.'
        : `Acceso DBeaver activo por ${ttlMinutes} minutos.`,
    bridge: getBridgeStatusPayload()
  });
});

/**
 * Heartbeat periódico enviado por la Web Administrativa para mantener vivo el modo 'session'
 */
app.post('/api/v1/db/bridge/heartbeat', requireStrictAuth, (req, res) => {
  if (req.body?.closeNow) {
    stopDbeaverBridge('cierre_de_pestana_web');
    return res.json({ bridge: getBridgeStatusPayload() });
  }
  if (dbeaverBridge.enabled) {
    dbeaverBridge.lastHeartbeatAt = Date.now();
  }
  return res.json({ bridge: getBridgeStatusPayload() });
});

// ============================================================================
// 7. GESTIÓN DEL TÚNEL CLOUDFLARE (QUICK TUNNEL GRATIS vs TOKEN DOMINIO FIJO)
// ============================================================================

const CLOUDFLARED_LOG_PATH = process.env.CLOUDFLARED_LOG_PATH || '/var/log/cloudflared.log';

const tunnelState = {
  mode: process.env.CLOUDFLARE_TUNNEL_TOKEN ? 'token' : 'quick',
  customDomain: process.env.CLOUDFLARE_CUSTOM_DOMAIN || '',
  publicUrl: process.env.CLOUDFLARE_CUSTOM_DOMAIN
    ? `https://${process.env.CLOUDFLARE_CUSTOM_DOMAIN.replace(/^https?:\/\//, '')}`
    : null,
  proc: null,
  status: 'starting'
};

/**
 * Lee el log de cloudflared para extraer la URL *.trycloudflare.com generada
 */
async function detectQuickTunnelUrlFromLog() {
  try {
    const content = await fs.readFile(CLOUDFLARED_LOG_PATH, 'utf8');
    const matches = content.match(/https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/g);
    if (matches && matches.length > 0) {
      tunnelState.publicUrl = matches[matches.length - 1];
      tunnelState.status = 'online';
      return tunnelState.publicUrl;
    }
  } catch (_e) {}
  return tunnelState.publicUrl;
}

/**
 * Reinicia cloudflared en modo Quick Tunnel (gratis) o en modo Token (dominio propio)
 */
async function restartCloudflaredTunnel({ mode, token = '', customDomain = '' }) {
  try {
    await execFileAsync('pkill', ['-f', 'cloudflared']).catch(() => {});
  } catch (_e) {}

  tunnelState.mode = mode === 'token' ? 'token' : 'quick';
  tunnelState.status = 'connecting';

  if (tunnelState.mode === 'token' && token) {
    tunnelState.customDomain = customDomain.replace(/^https?:\/\//, '').replace(/\/+$/, '');
    tunnelState.publicUrl = tunnelState.customDomain
      ? `https://${tunnelState.customDomain}`
      : 'https://tu-dominio-configurado.cloudflareaccess.com';

    const child = spawn('cloudflared', ['tunnel', '--no-autoupdate', 'run', '--token', token], {
      detached: true,
      stdio: 'ignore'
    });
    child.unref();
    tunnelState.proc = child;
    tunnelState.status = 'online';
  } else {
    tunnelState.publicUrl = null;
    await fs.writeFile(CLOUDFLARED_LOG_PATH, '', 'utf8').catch(() => {});
    const outStream = await fs.open(CLOUDFLARED_LOG_PATH, 'a').catch(() => null);
    const stdioCfg = outStream ? ['ignore', outStream.fd, outStream.fd] : 'ignore';

    const child = spawn(
      'cloudflared',
      ['tunnel', '--no-autoupdate', '--url', 'http://127.0.0.1:8080'],
      { detached: true, stdio: stdioCfg }
    );
    child.unref();
    tunnelState.proc = child;

    // Esperar hasta 8 segundos a que cloudflared imprima la URL *.trycloudflare.com
    for (let i = 0; i < 8; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      const found = await detectQuickTunnelUrlFromLog();
      if (found) break;
    }
  }
}

app.get('/api/v1/tunnel/status', requireStrictAuth, async (_req, res) => {
  if (tunnelState.mode === 'quick' && !tunnelState.publicUrl) {
    await detectQuickTunnelUrlFromLog();
  }
  const lanIp = getDeviceLanIp();
  return res.json({
    mode: tunnelState.mode,
    status: tunnelState.publicUrl ? 'online' : tunnelState.status,
    publicUrl: tunnelState.publicUrl,
    adminUrl: tunnelState.publicUrl ? `${tunnelState.publicUrl}/_control/` : null,
    lanUrl: `http://${lanIp}:8080`,
    lanAdminUrl: `http://${lanIp}:8080/_control/`,
    customDomain: tunnelState.customDomain || null
  });
});

app.post('/api/v1/tunnel/config', strictDeployLimiter, requireStrictAuth, async (req, res) => {
  try {
    const { mode = 'quick', token = '', customDomain = '' } = req.body || {};
    if (mode === 'token' && !token.trim()) {
      return res.status(400).json({ error: 'Debes pegar el Token del túnel de Cloudflare.' });
    }

    await restartCloudflaredTunnel({
      mode,
      token: token.trim(),
      customDomain: customDomain.trim()
    });

    const lanIp = getDeviceLanIp();
    return res.json({
      message:
        mode === 'token'
          ? 'Túnel con Dominio Propio conectado.'
          : 'Nuevo Link Público gratuito (*.trycloudflare.com) generado.',
      tunnel: {
        mode: tunnelState.mode,
        status: tunnelState.publicUrl ? 'online' : 'connecting',
        publicUrl: tunnelState.publicUrl,
        adminUrl: tunnelState.publicUrl ? `${tunnelState.publicUrl}/_control/` : null,
        lanUrl: `http://${lanIp}:8080`
      }
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/**
 * Despliegue Blue/Green mediante subida de paquete .zip
 */
app.post(
  ['/api/v1/deploy/zip', '/api/deploy/zip'],
  strictDeployLimiter,
  requireStrictAuth,
  upload.single('artifact'),
  async (req, res) => {
    const zipPath = req.file?.path;
    try {
      const appName = sanitizeAppName(req.body?.appName || 'mi-app');
      const healthPath = req.body?.healthPath || '/health';

      if (!zipPath) {
        return res.status(400).json({ error: 'Archivo .zip (artifact) requerido.' });
      }

      const result = await executeBlueGreenDeployment({
        appName,
        healthPath,
        sourceSetupFn: async (targetDir) => {
          const zip = new AdmZip(zipPath);
          // Prevención de Zip-Slip (Path Traversal)
          for (const entry of zip.getEntries()) {
            const resolvedPath = path.resolve(targetDir, entry.entryName);
            if (!resolvedPath.startsWith(path.resolve(targetDir) + path.sep)) {
              throw new Error(`Entrada maliciosa detectada en ZIP (Zip-Slip): ${entry.entryName}`);
            }
          }
          zip.extractAllTo(targetDir, true);
        }
      });

      return res.status(200).json({
        status: 'success',
        message: '¡Sistema actualizado con éxito!',
        deployment: result
      });
    } catch (err) {
      const isRollback = Boolean(err.rolledBack || err.code === 'HEALTHCHECK_ROLLBACK');
      return res.status(isRollback ? 422 : 500).json({
        status: isRollback ? 'rollback' : 'error',
        rolledBack: isRollback,
        code: err.code || 'DEPLOY_ERROR',
        error: err.message
      });
    } finally {
      if (zipPath) {
        await fs.rm(zipPath, { force: true }).catch(() => {});
      }
    }
  }
);

/**
 * Despliegue Blue/Green mediante Git Clone / Pull
 */
app.post('/api/v1/deploy/git', strictDeployLimiter, requireStrictAuth, async (req, res) => {
  try {
    const appName = sanitizeAppName(req.body.appName);
    const appRole = String(req.body.appRole || 'backend');
    const routePath = String(req.body.routePath || '/');
    const repoUrl = String(req.body.repoUrl || '');
    const branch = String(req.body.branch || 'main');
    const healthPath = req.body.healthPath || '/health';

    if (!/^https:\/\/[a-zA-Z0-9._/-]+\.git$/.test(repoUrl)) {
      return res.status(400).json({ error: 'URL de repositorio Git HTTPS inválida.' });
    }

    const result = await executeBlueGreenDeployment({
      appName,
      appRole,
      routePath,
      healthPath,
      sourceSetupFn: async (targetDir) => {
        const git = simpleGit();
        await git.clone(repoUrl, targetDir, ['--depth', '1', '--branch', branch]);
      }
    });

    return res.status(200).json({
      message: 'Despliegue Git Blue/Green completado con éxito (Zero-Downtime).',
      deployment: result
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

// Inicialización de tabla de auditoría y arranque del servidor solo en Loopback
async function bootstrap() {
  await fs.mkdir(APPS_BASE_DIR, { recursive: true });
  await fs.mkdir(UPLOADS_DIR, { recursive: true });

  try {
    await dbPool.execute(`
      CREATE TABLE IF NOT EXISTS deployments_audit (
        id INT AUTO_INCREMENT PRIMARY KEY,
        app_name VARCHAR(64) NOT NULL,
        active_color VARCHAR(16) NOT NULL,
        active_port INT NOT NULL,
        deployed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    `);
  } catch (err) {
    console.warn('[BOOTSTRAP] MariaDB aún inicializando o tabla pendiente:', err.message);
  }

  // Escucha exclusivamente en 127.0.0.1 para impedir acceso directo saltándose Caddy
  app.listen(PORT, '127.0.0.1', () => {
    console.log(`[CONTROL-PLANE] Escuchando de forma segura en http://127.0.0.1:${PORT}`);
  });
}

bootstrap();

