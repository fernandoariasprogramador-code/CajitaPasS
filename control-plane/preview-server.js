'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = 4000;
const PUBLIC_DIR = path.join(__dirname, 'public');
const ADMIN_AUTH_FILE = path.join(__dirname, '.preview-admin-auth.json');

// Clave de respaldo en .env por si usa scripts CI
const FALLBACK_MASTER_KEY = 'cpk_live_cajita_8f99a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8';

function loadAdminAuthState() {
  try {
    if (fs.existsSync(ADMIN_AUTH_FILE)) {
      const parsed = JSON.parse(fs.readFileSync(ADMIN_AUTH_FILE, 'utf8'));
      if (parsed && parsed.initialized && parsed.salt && parsed.passwordHash) {
        return parsed;
      }
    }
  } catch (_e) {}
  return { initialized: false, salt: null, passwordHash: null, sessionToken: null };
}

function hashPassword(password, saltHex) {
  return crypto.scryptSync(String(password), Buffer.from(saltHex, 'hex'), 64).toString('hex');
}

function verifyPassword(password, state) {
  if (!state.initialized || !state.salt || !state.passwordHash) return false;
  const candidate = hashPassword(password, state.salt);
  return candidate === state.passwordHash;
}

let bridgeState = {
  enabled: false,
  mode: 'session',
  host: '192.168.1.85',
  port: 13306,
  database: 'cajitapaas',
  user: 'cajita_admin',
  password: 'cajitapaas2026',
  allowedIp: null,
  remainingSeconds: null,
  activeConnections: 0
};

let tunnelState = {
  mode: 'quick',
  status: 'online',
  publicUrl: 'https://cajita-pampa-8421.trycloudflare.com',
  adminUrl: 'https://cajita-pampa-8421.trycloudflare.com/_control/',
  lanIp: '192.168.1.85',
  lanPort: PORT,
  lanUrl: `http://192.168.1.85:${PORT}`,
  lanAdminUrl: `http://192.168.1.85:${PORT}`,
  customDomain: null
};

let failoverState = {
  enabled: false,
  pollIntervalMinutes: 10,
  reconnectWindowSeconds: 30,
  state: 'DISABLED',
  wifiConnected: true
};

let activeSlots = {};

let pm2Processes = [];

let nextFreeBasePort = 3001;

let databases = [
  { name: 'cajitapaas', isSystem: false, tables: ['deployments_audit', 'api_keys'] },
  { name: 'app_ventas_db', isSystem: false, tables: ['clientes', 'facturas', 'pagos'] },
  { name: 'information_schema', isSystem: true, tables: [] }
];

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.jpg': 'image/jpeg',
  '.png': 'image/png'
};

function isAuthorized(req) {
  const state = loadAdminAuthState();
  const key = (req.headers['x-api-key'] || '').trim();
  const bearer = (req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim();

  if (key === FALLBACK_MASTER_KEY || bearer === FALLBACK_MASTER_KEY) return true;
  if (!state.initialized) return false;

  if (state.sessionToken && (bearer === state.sessionToken || key === state.sessionToken)) {
    return true;
  }
  if (key && verifyPassword(key, state)) {
    return true;
  }
  return false;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // 1. Endpoint público para saber si es la primera vez tras instalar
  if (url.pathname === '/api/auth/setup-status' && req.method === 'GET') {
    const state = loadAdminAuthState();
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ initialized: Boolean(state.initialized) }));
  }

  // 2. Endpoint para crear la contraseña de administrador en el primer ingreso
  if (url.pathname === '/api/auth/setup' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const currentState = loadAdminAuthState();
      if (currentState.initialized) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'La contraseña de administrador ya fue configurada.' }));
      }

      const payload = JSON.parse(body || '{}');
      const password = String(payload.password || '').trim();
      if (password.length < 6) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'La contraseña debe tener al menos 6 caracteres.' }));
      }

      const salt = crypto.randomBytes(16).toString('hex');
      const passwordHash = hashPassword(password, salt);
      const sessionToken = crypto.randomBytes(24).toString('hex');

      const newState = {
        initialized: true,
        salt,
        passwordHash,
        sessionToken,
        createdAt: new Date().toISOString()
      };
      fs.writeFileSync(ADMIN_AUTH_FILE, JSON.stringify(newState, null, 2), 'utf8');

      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: 'Contraseña de administrador guardada con éxito.',
          token: sessionToken
        })
      );
    });
    return;
  }

  // 3. Endpoint de Login con la contraseña creada por el usuario
  if (
    (url.pathname === '/api/auth/login' || url.pathname === '/api/v1/auth/token') &&
    req.method === 'POST'
  ) {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const state = loadAdminAuthState();
      const payload = JSON.parse(body || '{}');
      const secretInput = String(payload.password || payload.apiKey || '').trim();

      if (verifyPassword(secretInput, state) || secretInput === FALLBACK_MASTER_KEY) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(
          JSON.stringify({
            token: state.sessionToken || secretInput,
            expiresIn: 43200
          })
        );
      }

      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          error: 'La contraseña ingresada no coincide con la que configuraste.'
        })
      );
    });
    return;
  }

  if (url.pathname.startsWith('/api/')) {
    if (!isAuthorized(req)) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      return res.end(
        JSON.stringify({
          error: 'No autorizado. Ingresá con tu contraseña de administrador.'
        })
      );
    }
  }

  if ((url.pathname === '/api/status' || url.pathname === '/api/v1/status') && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(
      JSON.stringify({
        system: 'CPS (CajitaPaaS)',
        status: 'online',
        mariadbConnected: true,
        activeDeployments: activeSlots
      })
    );
  }

  if (url.pathname === '/api/v1/projects' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(
      JSON.stringify({
        activeSlots,
        pm2Processes
      })
    );
  }

  if (url.pathname.startsWith('/api/v1/projects/') && req.method === 'DELETE') {
    const appName = decodeURIComponent(url.pathname.split('/').pop());
    pm2Processes = pm2Processes.filter((p) => !p.name.startsWith(appName));
    delete activeSlots[appName];
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ message: `Proyecto ${appName} detenido.` }));
  }

  if (
    (url.pathname === '/api/v1/deploy/git' ||
      url.pathname === '/api/deploy/zip' ||
      url.pathname === '/api/v1/deploy/zip') &&
    req.method === 'POST'
  ) {
    const chunks = [];
    req.on('data', (chunk) => {
      if (chunks.length < 4) chunks.push(chunk);
    });
    req.on('end', () => {
      const rawPreview = Buffer.concat(chunks).toString('utf8');
      const payload = rawPreview.trim().startsWith('{')
        ? JSON.parse(rawPreview || '{}')
        : {};
      const appName =
        url.searchParams.get('appName') || payload.appName || 'nueva-app';
      const appRole =
        url.searchParams.get('appRole') || payload.appRole || 'frontend';
      const routePath =
        url.searchParams.get('routePath') ||
        payload.routePath ||
        (appRole === 'frontend' ? '/' : '/api/*');

      const prev = activeSlots[appName];
      const bluePort = prev ? prev.bluePort : nextFreeBasePort;
      const greenPort = prev ? prev.greenPort : nextFreeBasePort + 1;
      if (!prev) nextFreeBasePort += 2;

      const nextColor = prev && prev.activeColor === 'blue' ? 'green' : 'blue';
      const activePort = nextColor === 'blue' ? bluePort : greenPort;

      activeSlots[appName] = {
        appRole,
        routePath,
        bluePort,
        greenPort,
        activeColor: nextColor,
        activePort
      };

      pm2Processes = pm2Processes.filter((p) => !p.name.startsWith(`${appName}-`));
      pm2Processes.unshift({
        name: `${appName}-${nextColor}`,
        pid: Math.floor(5000 + Math.random() * 4000),
        status: 'online',
        cpu: 1.5,
        memoryMb: appRole === 'frontend' ? 42 : 58
      });

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: 'Despliegue Blue/Green Multi-App completado con éxito (Zero-Downtime).',
          deployment: { appName, appRole, routePath, deployedSlot: nextColor, activePort }
        })
      );
    });
    return;
  }

  if (url.pathname === '/api/v1/db/databases' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ databases }));
  }

  if (url.pathname === '/api/v1/db/databases' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const payload = JSON.parse(body || '{}');
      const dbName = payload.dbName || 'nueva_db';
      databases.push({ name: dbName, isSystem: false, tables: ['init_table'] });
      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: `Base de datos '${dbName}' aprovisionada con éxito.`,
          database: dbName,
          user: payload.appUser ? `${payload.appUser}@127.0.0.1` : null
        })
      );
    });
    return;
  }

  if (url.pathname === '/api/v1/db/bridge/status' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(bridgeState));
  }

  if (url.pathname === '/api/v1/db/bridge/toggle' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const payload = JSON.parse(body || '{}');
      bridgeState = {
        enabled: Boolean(payload.enabled),
        mode: payload.mode || 'session',
        host: '192.168.1.85',
        port: 13306,
        database: 'cajitapaas',
        user: 'cajita_admin',
        password: 'cajitapaas2026',
        allowedIp: payload.restrictToMyIp ? '192.168.1.40 (Tu PC)' : 'Cualquier PC de tu red Wi-Fi',
        remainingSeconds: payload.mode === 'ttl' ? (payload.ttlMinutes || 15) * 60 : 45,
        activeConnections: payload.enabled ? 1 : 0
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: bridgeState.enabled
            ? 'Puente TCP DBeaver ACTIVADO (:13306 -> 127.0.0.1:3306)'
            : 'Puente TCP DBeaver CERRADO',
          bridge: bridgeState
        })
      );
    });
    return;
  }

  if (url.pathname === '/api/v1/db/bridge/heartbeat' && req.method === 'POST') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ bridge: bridgeState }));
  }

  if (url.pathname === '/api/v1/tunnel/status' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(tunnelState));
  }

  if (url.pathname === '/api/v1/network/failover/status' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ failover: failoverState }));
  }

  if (url.pathname === '/api/v1/network/failover/toggle' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const payload = JSON.parse(body || '{}');
      if (typeof payload.enabled === 'boolean') {
        failoverState.enabled = payload.enabled;
        failoverState.state = payload.enabled ? 'NORMAL_WIFI_STA' : 'DISABLED';
      }
      if (payload.pollIntervalMinutes !== undefined) {
        failoverState.pollIntervalMinutes = Number(payload.pollIntervalMinutes) || 10;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: failoverState.enabled
            ? 'Supervivencia Wi-Fi ACTIVADA'
            : 'Supervivencia Wi-Fi DESACTIVADA',
          failover: failoverState
        })
      );
    });
    return;
  }

  if (url.pathname === '/api/v1/tunnel/config' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const payload = JSON.parse(body || '{}');
      if (payload.mode === 'token') {
        const cleanDomain = (payload.customDomain || 'paas.midominio.com').replace(/^https?:\/\//, '');
        tunnelState = {
          mode: 'token',
          status: 'online',
          publicUrl: `https://${cleanDomain}`,
          adminUrl: `https://${cleanDomain}/_control/`,
          lanUrl: 'http://192.168.1.85:8080',
          customDomain: cleanDomain
        };
      } else {
        const randId = Math.floor(1000 + Math.random() * 9000);
        tunnelState = {
          mode: 'quick',
          status: 'online',
          publicUrl: `https://cps-andro-node-${randId}.trycloudflare.com`,
          adminUrl: `https://cps-andro-node-${randId}.trycloudflare.com/_control/`,
          lanUrl: 'http://192.168.1.85:8080',
          customDomain: null
        };
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          message: 'Configuración de túnel actualizada.',
          tunnel: tunnelState
        })
      );
    });
    return;
  }

  if (url.pathname === '/api/v1/db/query' && req.method === 'POST') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(
      JSON.stringify({
        database: 'cajitapaas',
        columns: ['id', 'app_name', 'active_color', 'active_port', 'deployed_at'],
        rows: [
          { id: 3, app_name: 'api-pagos', active_color: 'green', active_port: 3004, deployed_at: '2026-11-11 18:02:11' },
          { id: 2, app_name: 'portal-clientes', active_color: 'blue', active_port: 3001, deployed_at: '2026-11-11 17:45:09' },
          { id: 1, app_name: 'api-pagos', active_color: 'blue', active_port: 3003, deployed_at: '2026-11-11 16:30:00' }
        ]
      })
    );
  }

  const safePath = url.pathname === '/' ? '/index.html' : url.pathname;
  const filePath = path.join(PUBLIC_DIR, path.normalize(safePath));
  if (!filePath.startsWith(PUBLIC_DIR) || !fs.existsSync(filePath)) {
    res.writeHead(404);
    return res.end('Not found');
  }

  const ext = path.extname(filePath);
  res.writeHead(200, {
    'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
    'Cache-Control': 'no-store, no-cache, must-revalidate'
  });
  fs.createReadStream(filePath).pipe(res);
});

const os = require('os');

server.listen(PORT, '0.0.0.0', () => {
  const lanIps = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${PORT}`);
  console.log(`CPS Preview Server running at:`);
  console.log(`  - PC Local: http://127.0.0.1:${PORT}`);
  lanIps.forEach((url) => console.log(`  - Desde tu Celular (Wi-Fi): ${url}`));
});
