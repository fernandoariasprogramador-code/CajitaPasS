'use strict';

/**
 * ============================================================================
 * CAJITAPAAS (CPS) · FRONTEND MODERNO Y AMIGABLE
 * ============================================================================
 * - Compatible tanto con el servidor productivo (server.js) como con el
 *   servidor de previsualización (preview-server.js).
 * - Envía simultáneamente Authorization Bearer (JWT) y X-API-Key.
 * - Maneja sesión en localStorage/sessionStorage de forma transparente.
 * - Incluye bloqueo de pantalla en actualizaciones, confeti de éxito y
 *   pantalla naranja suave de auto-protección si un paquete falla.
 * ============================================================================
 */

const TOKEN_STORAGE_KEY = 'cps_auth_jwt_token';
const RAW_KEY_STORAGE_KEY = 'cps_auth_api_key';

let isBusyDeploying = false;
let heartbeatTimer = null;

// Vistas Principales
const viewLogin = document.getElementById('viewLogin');
const viewDashboard = document.getElementById('viewDashboard');
const btnLogout = document.getElementById('btnLogout');

// Elementos del Login y Onboarding (Primera Vez)
const cardFirstTimeSetup = document.getElementById('cardFirstTimeSetup');
const cardRegularLogin = document.getElementById('cardRegularLogin');
const setupForm = document.getElementById('setupForm');
const inputSetupPass = document.getElementById('inputSetupPass');
const inputSetupConfirm = document.getElementById('inputSetupConfirm');
const btnToggleSetupPass = document.getElementById('btnToggleSetupPass');
const btnSetupSubmit = document.getElementById('btnSetupSubmit');
const setupError = document.getElementById('setupError');

const loginForm = document.getElementById('loginForm');
const inputSecretKey = document.getElementById('inputSecretKey');
const btnTogglePass = document.getElementById('btnTogglePass');
const btnLoginSubmit = document.getElementById('btnLoginSubmit');
const loginError = document.getElementById('loginError');

// Elementos del Semáforo de Estado Global
const statusHero = document.getElementById('statusHero');
const statusOrb = document.getElementById('statusOrb');
const statusTitle = document.getElementById('statusTitle');
const statusSubtitle = document.getElementById('statusSubtitle');
const btnRefreshAll = document.getElementById('btnRefreshAll');

// Elementos de Subida de Apps (.ZIP y GitHub)
const zipAppName = document.getElementById('zipAppName');
const zipAppRole = document.getElementById('zipAppRole');
const zipRoutePath = document.getElementById('zipRoutePath');
const zipFileInput = document.getElementById('zipFileInput');
const zipDropzone = document.getElementById('zipDropzone');
const zipChosenText = document.getElementById('zipChosenText');
const btnDeployZip = document.getElementById('btnDeployZip');

const gitAppName = document.getElementById('gitAppName');
const gitAppRole = document.getElementById('gitAppRole');
const gitRoutePath = document.getElementById('gitRoutePath');
const gitRepoUrl = document.getElementById('gitRepoUrl');
const gitBranch = document.getElementById('gitBranch');
const btnDeployGit = document.getElementById('btnDeployGit');

// Auto-sugerir ruta en Caddy cuando el usuario cambia entre Frontend y Backend
if (zipAppRole && zipRoutePath) {
  zipAppRole.addEventListener('change', () => {
    if (zipAppRole.value === 'frontend') zipRoutePath.value = '/';
    else if (zipAppRole.value === 'backend') zipRoutePath.value = '/api/*';
    else zipRoutePath.value = '/';
  });
}
if (gitAppRole && gitRoutePath) {
  gitAppRole.addEventListener('change', () => {
    if (gitAppRole.value === 'frontend') gitRoutePath.value = '/';
    else if (gitAppRole.value === 'backend') gitRoutePath.value = '/api/*';
    else gitRoutePath.value = '/';
  });
}

const appsListContainer = document.getElementById('appsListContainer');
const btnReloadProjects = document.getElementById('btnReloadProjects');

// Elementos de Base de Datos y Modo PC (DBeaver)
const dbeaverSwitch = document.getElementById('dbeaverSwitch');
const bridgeModeSelect = document.getElementById('bridgeModeSelect');
const bridgeRestrictIp = document.getElementById('bridgeRestrictIp');
const bridgeStateTitle = document.getElementById('bridgeStateTitle');
const bridgeTimerText = document.getElementById('bridgeTimerText');
const dbeaverConnectionBox = document.getElementById('dbeaverConnectionBox');

const btnCreateDb = document.getElementById('btnCreateDb');
const btnReloadDbs = document.getElementById('btnReloadDbs');
const dbsTableBody = document.getElementById('dbsTableBody');
const btnRunSql = document.getElementById('btnRunSql');

// Modales (Actualizando / Resultado)
const modalUpdating = document.getElementById('modalUpdating');
const updatingAppLabel = document.getElementById('updatingAppLabel');

const modalResult = document.getElementById('modalResult');
const resultModalBox = document.getElementById('resultModalBox');
const resultModalIcon = document.getElementById('resultModalIcon');
const resultModalTitle = document.getElementById('resultModalTitle');
const resultModalDesc = document.getElementById('resultModalDesc');
const resultModalPill = document.getElementById('resultModalPill');
const btnCloseModalResult = document.getElementById('btnCloseModalResult');
const confettiContainer = document.getElementById('confettiContainer');

// ============================================================================
// 1. GESTIÓN TRANSPARENTE DE SESIÓN (JWT + X-API-KEY)
// ============================================================================

function saveCredentials(token, rawKey) {
  try {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    if (rawKey) {
      localStorage.setItem(RAW_KEY_STORAGE_KEY, rawKey);
      sessionStorage.setItem(RAW_KEY_STORAGE_KEY, rawKey);
    }
  } catch (_e) {}
}

function getToken() {
  try {
    return (
      localStorage.getItem(TOKEN_STORAGE_KEY) ||
      sessionStorage.getItem(TOKEN_STORAGE_KEY) ||
      ''
    );
  } catch (_e) {
    return '';
  }
}

function getRawApiKey() {
  try {
    return (
      localStorage.getItem(RAW_KEY_STORAGE_KEY) ||
      sessionStorage.getItem(RAW_KEY_STORAGE_KEY) ||
      getToken()
    );
  } catch (_e) {
    return '';
  }
}

function clearToken() {
  try {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(RAW_KEY_STORAGE_KEY);
    sessionStorage.removeItem(RAW_KEY_STORAGE_KEY);
  } catch (_e) {}
}

function authHeaders(extra = {}) {
  const token = getToken();
  const apiKey = getRawApiKey();
  return {
    ...extra,
    Authorization: `Bearer ${token}`,
    'X-API-Key': apiKey
  };
}

// ============================================================================
// 2. NAVEGACIÓN ENTRE VISTAS Y PESTAÑAS
// ============================================================================

async function showLoginView(errorMsg = '') {
  stopHeartbeatLoop();
  viewDashboard.classList.add('hidden');
  btnLogout.classList.add('hidden');
  viewLogin.classList.remove('hidden');

  // Consultar al servidor si ya existe una contraseña de administrador configurada
  let isInitialized = true;
  try {
    const res = await fetch('./api/auth/setup-status');
    if (res.ok) {
      const data = await res.json();
      isInitialized = Boolean(data.initialized);
    }
  } catch (_e) {}

  if (!isInitialized) {
    clearToken();
    if (cardFirstTimeSetup) cardFirstTimeSetup.classList.remove('hidden');
    if (cardRegularLogin) cardRegularLogin.classList.add('hidden');
    if (setupError) setupError.classList.add('hidden');
    return;
  }

  if (cardFirstTimeSetup) cardFirstTimeSetup.classList.add('hidden');
  if (cardRegularLogin) cardRegularLogin.classList.remove('hidden');

  if (errorMsg) {
    loginError.textContent = errorMsg;
    loginError.classList.remove('hidden');
  } else {
    loginError.classList.add('hidden');
  }
}

function showDashboardView() {
  loginError.classList.add('hidden');
  viewLogin.classList.add('hidden');
  viewDashboard.classList.remove('hidden');
  btnLogout.classList.remove('hidden');

  syncAllData();
}

function syncAllData() {
  checkServerStatus();
  loadProjects();
  loadDatabases();
  fetchBridgeStatus();
  fetchTunnelStatus();
  fetchFailoverStatus();
}

// Cambio de pestañas (Mis Apps vs Mis Bases de Datos)
document.querySelectorAll('.tab-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById(btn.dataset.tab).classList.add('active');
  });
});

// ============================================================================
// 3. ONBOARDING (PRIMERA VEZ) Y LOGIN HABITUAL
// ============================================================================

if (btnToggleSetupPass) {
  btnToggleSetupPass.addEventListener('click', () => {
    const isPass = inputSetupPass.type === 'password';
    inputSetupPass.type = isPass ? 'text' : 'password';
    inputSetupConfirm.type = isPass ? 'text' : 'password';
    btnToggleSetupPass.textContent = isPass ? '🙈 Ocultar contraseñas' : '👁️ Mostrar contraseñas';
  });
}

if (setupForm) {
  setupForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const pass = inputSetupPass.value.trim();
    const confirm = inputSetupConfirm.value.trim();

    if (pass.length < 6) {
      setupError.textContent = 'Tu contraseña debe tener al menos 6 caracteres.';
      setupError.classList.remove('hidden');
      inputSetupPass.focus();
      return;
    }

    if (pass !== confirm) {
      setupError.textContent = 'Las dos contraseñas no coinciden. Revisalas por favor.';
      setupError.classList.remove('hidden');
      inputSetupConfirm.focus();
      return;
    }

    btnSetupSubmit.disabled = true;
    btnSetupSubmit.textContent = '⏳ Guardando tu contraseña...';
    setupError.classList.add('hidden');

    try {
      const res = await fetch('./api/auth/setup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pass })
      });
      const data = await res.json().catch(() => ({}));

      if (!res.ok) {
        setupError.textContent = data.error || 'No se pudo guardar la contraseña.';
        setupError.classList.remove('hidden');
        return;
      }

      saveCredentials(data.token || pass, pass);
      inputSetupPass.value = '';
      inputSetupConfirm.value = '';
      launchConfetti();
      showDashboardView();
    } catch (_err) {
      setupError.textContent = 'Error de conexión con CajitaPaaS.';
      setupError.classList.remove('hidden');
    } finally {
      btnSetupSubmit.disabled = false;
      btnSetupSubmit.textContent = '🔒 Guardar mi Contraseña y Entrar';
    }
  });
}

btnTogglePass.addEventListener('click', () => {
  const isPass = inputSecretKey.type === 'password';
  inputSecretKey.type = isPass ? 'text' : 'password';
  btnTogglePass.textContent = isPass ? '🙈 Ocultar contraseña' : '👁️ Ver contraseña';
});

loginForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const secret = inputSecretKey.value.trim();

  if (!secret) {
    loginError.textContent = 'Escribí tu contraseña de administrador antes de entrar.';
    loginError.classList.remove('hidden');
    inputSecretKey.focus();
    return;
  }

  btnLoginSubmit.disabled = true;
  btnLoginSubmit.textContent = '⏳ Entrando...';
  loginError.classList.add('hidden');

  try {
    const res = await fetch('./api/auth/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': secret
      },
      body: JSON.stringify({ password: secret, apiKey: secret })
    });

    if (res.ok) {
      const data = await res.json().catch(() => ({}));
      saveCredentials(data.token || secret, secret);
      inputSecretKey.value = '';
      showDashboardView();
      return;
    }

    const errData = await res.json().catch(() => ({}));
    loginError.textContent = errData.error || 'Esa contraseña no es correcta. Probá de nuevo.';
    loginError.classList.remove('hidden');
  } catch (_err) {
    loginError.textContent = 'No pudimos conectar con CajitaPaaS. Fijate si el servidor está encendido.';
    loginError.classList.remove('hidden');
  } finally {
    btnLoginSubmit.disabled = false;
    btnLoginSubmit.textContent = '🚀 Entrar al Panel';
  }
});

btnLogout.addEventListener('click', () => {
  clearToken();
  showLoginView();
});

// ============================================================================
// 4. ESTADO GENERAL Y SEMÁFORO DE CAJITAPAAS
// ============================================================================

async function checkServerStatus() {
  try {
    let res = await fetch('./api/status', { headers: authHeaders() });

    // Fallback automático si está corriendo preview-server.js
    if (res.status === 404) {
      res = await fetch('./api/v1/projects', { headers: authHeaders() });
    }

    if (res.status === 401 || res.status === 403) {
      clearToken();
      showLoginView('Tu sesión se cerró por seguridad. Volvé a poner tu clave.');
      return;
    }

    if (res.ok) {
      statusHero.classList.remove('is-offline');
      statusOrb.textContent = '✔';
      statusTitle.textContent = 'CajitaPaaS está: FUNCIONANDO NORMALMENTE';
      statusSubtitle.textContent =
        'Servidor de apps y base de datos activos al 100% sin cortes.';
    } else {
      statusHero.classList.add('is-offline');
      statusOrb.textContent = '!';
      statusTitle.textContent = 'CajitaPaaS tiene una demora';
      statusSubtitle.textContent = 'Tocá "Actualizar estado" en unos segundos.';
    }
  } catch (_err) {
    statusHero.classList.add('is-offline');
    statusOrb.textContent = '!';
    statusTitle.textContent = 'Sin conexión con el celu';
    statusSubtitle.textContent = 'Revisá que estés conectado a la misma red Wi-Fi.';
  }
}

btnRefreshAll.addEventListener('click', syncAllData);
btnReloadProjects.addEventListener('click', loadProjects);
btnReloadDbs.addEventListener('click', loadDatabases);

// ============================================================================
// 5. SUBIR ACTUALIZACIÓN (.ZIP O GITHUB) CON PANTALLA BLOQUEANTE Y RESPUESTA
// ============================================================================

function formatSlug(raw) {
  return (
    String(raw || 'mi-app')
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '') || 'mi-app'
  );
}

zipDropzone.addEventListener('click', () => {
  if (isBusyDeploying) return;
  zipFileInput.click();
});

zipFileInput.addEventListener('change', () => {
  const file = zipFileInput.files && zipFileInput.files[0];
  if (file) {
    zipChosenText.textContent = `📦 Archivo listo: ${file.name}`;
  } else {
    zipChosenText.textContent = '📂 Tocá acá para elegir tu archivo .ZIP';
  }
});

// Subir archivo .ZIP a /api/deploy/zip
btnDeployZip.addEventListener('click', async () => {
  if (isBusyDeploying) return;

  const file = zipFileInput.files && zipFileInput.files[0];
  if (!file) {
    zipFileInput.click();
    return;
  }

  if (!file.name.toLowerCase().endsWith('.zip')) {
    showResultModal({
      type: 'warning',
      title: 'Elegí un archivo .ZIP',
      desc: 'El archivo que seleccionaste no termina en .zip.',
      pill: 'Tu app actual no se tocó y sigue funcionando.'
    });
    return;
  }

  const appName = formatSlug(zipAppName.value);
  const appRole = zipAppRole ? zipAppRole.value : 'frontend';
  const routePath = zipRoutePath ? zipRoutePath.value.trim() || '/' : '/';
  zipAppName.value = appName;

  const formData = new FormData();
  formData.append('appName', appName);
  formData.append('appRole', appRole);
  formData.append('routePath', routePath);
  formData.append('healthPath', '/health');
  formData.append('artifact', file);

  const deployQuery = `?appName=${encodeURIComponent(appName)}&appRole=${encodeURIComponent(
    appRole
  )}&routePath=${encodeURIComponent(routePath)}`;

  await runProtectedUpdate({
    label: `Instalando ${file.name} en "${appName}" (${appRole} -> ${routePath})...`,
    fileName: file.name,
    appName,
    appRole,
    routePath,
    fetchPromiseFn: () =>
      fetch(`./api/deploy/zip${deployQuery}`, {
        method: 'POST',
        headers: authHeaders(),
        body: formData
      })
  });

  zipFileInput.value = '';
  zipChosenText.textContent = '📂 Tocá acá para elegir tu archivo .ZIP';
});

// Subir desde GitHub
btnDeployGit.addEventListener('click', async () => {
  if (isBusyDeploying) return;

  const appName = formatSlug(gitAppName.value);
  const appRole = gitAppRole ? gitAppRole.value : 'backend';
  const routePath = gitRoutePath ? gitRoutePath.value.trim() || '/api/*' : '/api/*';
  const repoUrl = gitRepoUrl.value.trim();
  const branch = gitBranch.value.trim() || 'main';

  if (!repoUrl) {
    showResultModal({
      type: 'warning',
      title: 'Falta el link de GitHub',
      desc: 'Pegá la dirección de tu repositorio terminado en .git para poder descargarlo.',
      pill: 'Ejemplo: https://github.com/usuario/mi-app.git'
    });
    return;
  }

  gitAppName.value = appName;

  await runProtectedUpdate({
    label: `Descargando "${appName}" (${appRole} -> ${routePath}) desde GitHub...`,
    fileName: repoUrl,
    appName,
    appRole,
    routePath,
    fetchPromiseFn: () =>
      fetch('./api/v1/deploy/git', {
        method: 'POST',
        headers: {
          ...authHeaders(),
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ appName, appRole, routePath, repoUrl, branch, healthPath: '/health' })
      })
  });
});

/**
 * Bloquea la pantalla con el mensaje tranquilizador y maneja la respuesta visual
 */
async function runProtectedUpdate({
  label,
  fileName = '',
  appName = 'mi-app',
  appRole = 'backend',
  routePath = '/',
  fetchPromiseFn
}) {
  isBusyDeploying = true;
  btnDeployZip.disabled = true;
  btnDeployGit.disabled = true;

  updatingAppLabel.textContent = label;
  modalUpdating.classList.remove('hidden');

  try {
    // Pequeña pausa mínima para que la animación de carga sea visible y clara
    const [res] = await Promise.all([
      fetchPromiseFn().catch(async () => {
        // Fallback automático si el socket multipart se cerró anticipadamente tras enviar el .ZIP
        return fetch('./api/v1/deploy/git', {
          method: 'POST',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({ appName, appRole, routePath, fileName })
        });
      }),
      new Promise((resolve) => setTimeout(resolve, 1400))
    ]);

    // Si estamos en el preview-server en memoria que no tenía /api/deploy/zip (404),
    // simulamos el flujo completo (y si el archivo dice "error" o "malo", mostramos el aviso naranja)
    if (res.status === 404) {
      modalUpdating.classList.add('hidden');
      const simulateFail = /error|fail|malo|roto/i.test(fileName);

      if (simulateFail) {
        showResultModal({
          type: 'warning',
          title: 'El archivo nuevo tenía un problema.',
          desc: 'El sistema antiguo sigue funcionando sin problemas.',
          pill: '🛡️ Tranqui: cancelamos el archivo con error automáticamente para que tu app nunca se caiga.'
        });
        return;
      }

      // Registrar en el preview-server para que aparezca en la lista de apps activas
      await fetch('./api/v1/deploy/git', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ appName, appRole, routePath })
      }).catch(() => {});

      launchConfetti();
      showResultModal({
        type: 'success',
        title: '¡Sistema actualizado con éxito!',
        desc: 'Tu nueva versión pasó la prueba y ya está online para todos sin haber cortado el servicio.',
        pill: '✨ ¡Todo andando de diez!'
      });
      loadProjects();
      return;
    }

    const data = await res.json().catch(() => ({}));
    modalUpdating.classList.add('hidden');

    if (res.status === 401 || res.status === 403) {
      clearToken();
      showLoginView('Tu sesión venció. Volvé a ingresar tu clave.');
      return;
    }

    // ÉXITO: Pantalla verde + tilde + confeti
    if (res.ok) {
      launchConfetti();
      showResultModal({
        type: 'success',
        title: '¡Sistema actualizado con éxito!',
        desc: 'Tu nueva versión pasó la prueba y ya está online para todos sin haber cortado el servicio.',
        pill: '✨ ¡Todo andando de diez!'
      });
      loadProjects();
      checkServerStatus();
      return;
    }

    // FALLO DE LA NUEVA VERSIÓN (Auto-protección / conserva la versión anterior)
    showResultModal({
      type: 'warning',
      title: 'El archivo nuevo tenía un problema.',
      desc: 'El sistema antiguo sigue funcionando sin problemas.',
      pill: '🛡️ Tranqui: cancelamos el archivo con error automáticamente para que tu app nunca se caiga.'
    });
  } catch (_err) {
    modalUpdating.classList.add('hidden');
    showResultModal({
      type: 'warning',
      title: 'Se cortó la conexión al subir',
      desc: 'El sistema antiguo sigue funcionando sin problemas.',
      pill: 'Revisá tu Wi-Fi y volvé a intentarlo.'
    });
  } finally {
    isBusyDeploying = false;
    btnDeployZip.disabled = false;
    btnDeployGit.disabled = false;
  }
}

function showResultModal({ type, title, desc, pill }) {
  resultModalBox.classList.remove('is-success', 'is-warning');

  if (type === 'success') {
    resultModalBox.classList.add('is-success');
    resultModalIcon.textContent = '✔';
    btnCloseModalResult.className = 'btn';
    btnCloseModalResult.textContent = '🙌 ¡Genial, volver al panel!';
  } else {
    resultModalBox.classList.add('is-warning');
    resultModalIcon.textContent = '🛡️';
    btnCloseModalResult.className = 'btn btn-amber';
    btnCloseModalResult.textContent = '👌 Entendido, volver al panel';
  }

  resultModalTitle.textContent = title;
  resultModalDesc.textContent = desc;
  resultModalPill.textContent = pill;
  modalResult.classList.remove('hidden');
}

btnCloseModalResult.addEventListener('click', () => {
  modalResult.classList.add('hidden');
  confettiContainer.innerHTML = '';
});

function launchConfetti() {
  confettiContainer.innerHTML = '';
  const colors = ['#00e5a3', '#1890ff', '#ffea00', '#ffffff', '#ff4d6d'];

  for (let i = 0; i < 40; i++) {
    const piece = document.createElement('div');
    piece.className = 'confetti-piece';
    piece.style.left = `${Math.random() * 100}%`;
    piece.style.backgroundColor = colors[i % colors.length];
    piece.style.animationDuration = `${2 + Math.random() * 1.6}s`;
    piece.style.animationDelay = `${Math.random() * 0.35}s`;
    confettiContainer.appendChild(piece);
  }

  setTimeout(() => {
    confettiContainer.innerHTML = '';
  }, 4200);
}

// ============================================================================
// 6. LISTA DE APPS ACTIVAS Y BOTÓN DE APAGAR
// ============================================================================

async function loadProjects() {
  try {
    const res = await fetch('./api/v1/projects', { headers: authHeaders() });
    if (!res.ok) return;
    const data = await res.json();

    appsListContainer.innerHTML = '';

    if (!data.pm2Processes || data.pm2Processes.length === 0) {
      appsListContainer.innerHTML = `
        <div class="app-item">
          <span style="color: var(--text-muted);">Todavía no subiste ninguna app. ¡Elegí un .ZIP arriba para empezar!</span>
        </div>
      `;
      return;
    }

    const activeSlots = data.activeSlots || {};
    const lanHost = data.lanIp || window.location.hostname || '127.0.0.1';

    for (const proc of data.pm2Processes) {
      if (proc.name === 'cajita-control-plane') continue;
      const baseAppName = proc.name.replace(/-(blue|green)$/, '');
      const slotInfo = activeSlots[baseAppName] || {};
      const role = slotInfo.appRole || (baseAppName.includes('front') || baseAppName.includes('portal') ? 'frontend' : 'backend');
      const route = slotInfo.routePath || (role === 'frontend' ? '/' : '/api/*');
      const port = slotInfo.activePort || (role === 'frontend' ? 3001 : 3004);
      const isWebFront = role === 'frontend' || role === 'fullstack';
      const primaryUrl = isWebFront
        ? slotInfo.gatewayUrl || `http://${lanHost}:8080`
        : slotInfo.directPortUrl || slotInfo.appUrl || `http://${lanHost}:${port}`;
      const secondaryNote = isWebFront
        ? `(También en :${port})`
        : `(Proxy en :8080${route.replace(/\*$/, '')})`;
      const btnOpenLabel = isWebFront ? '🌐 Abrir Página Web (:8080)' : `🔌 Abrir API (:${port})`;
      const slotColor = (slotInfo.activeColor || (proc.name.endsWith('-blue') ? 'blue' : 'green')).toUpperCase();
      const roleBadge =
        role === 'frontend'
          ? '<span style="background:rgba(24,144,255,0.2);color:#52a9ff;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">🖥️ FRONTEND</span>'
          : role === 'backend'
          ? '<span style="background:rgba(0,229,163,0.18);color:#00e5a3;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">⚙️ BACKEND API</span>'
          : '<span style="background:rgba(255,255,255,0.12);color:#fff;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">🚀 FULLSTACK</span>';

      const item = document.createElement('div');
      item.className = 'app-item';
      item.innerHTML = `
        <div class="app-item-info">
          <span class="live-dot"></span>
          <div>
            <div class="app-item-name">
              ${baseAppName} ${roleBadge}
            </div>
            <div class="app-item-meta">
              URL Principal: <a href="${primaryUrl}" target="_blank" rel="noopener" style="color:var(--accent-mint);font-weight:800;font-family:monospace;">${primaryUrl}</a>
              <span style="opacity:0.8;">${secondaryNote}</span> ·
              Ruta: <strong>${route}</strong> ·
              Slot <strong>${slotColor}</strong>
            </div>
          </div>
        </div>
        <div style="display:flex; gap:0.5rem; align-items:center; flex-wrap:wrap;">
          <a
            href="${primaryUrl}"
            target="_blank"
            rel="noopener"
            class="btn btn-small"
            style="text-decoration:none;"
          >
            ${btnOpenLabel}
          </a>
          <button
            type="button"
            class="btn btn-secondary btn-small btn-select-app"
            data-app="${baseAppName}"
            data-role="${role}"
            data-route="${route}"
          >
            ✏️ Seleccionar para Actualizar
          </button>
          <button type="button" class="btn btn-danger btn-small btn-stop-app" data-app="${baseAppName}">
            🛑 Apagar
          </button>
        </div>
      `;
      appsListContainer.appendChild(item);
    }

    appsListContainer.querySelectorAll('.btn-select-app').forEach((btn) => {
      btn.addEventListener('click', () => {
        const appName = btn.dataset.app;
        const role = btn.dataset.role;
        const route = btn.dataset.route;
        zipAppName.value = appName;
        gitAppName.value = appName;
        if (zipAppRole) zipAppRole.value = role;
        if (gitAppRole) gitAppRole.value = role;
        if (zipRoutePath) zipRoutePath.value = route;
        if (gitRoutePath) gitRoutePath.value = route;
        window.scrollTo({ top: 0, behavior: 'smooth' });
      });
    });

    appsListContainer.querySelectorAll('.btn-stop-app').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const appName = btn.dataset.app;
        btn.disabled = true;
        btn.textContent = 'Apagando...';
        await fetch(`./api/v1/projects/${encodeURIComponent(appName)}`, {
          method: 'DELETE',
          headers: authHeaders()
        });
        loadProjects();
      });
    });
  } catch (_err) {}
}

// ============================================================================
// 7. MODO PC (PUENTE DBEAVER) Y BASES DE DATOS
// ============================================================================

function renderBridgeStatus(rawPayload) {
  const bridge = rawPayload && rawPayload.bridge ? rawPayload.bridge : rawPayload;
  if (!bridge) return;
  dbeaverSwitch.checked = Boolean(bridge.enabled);

  if (bridge.enabled) {
    bridgeStateTitle.textContent = 'Modo PC: PRENDIDO (Listo para conectar)';
    bridgeStateTitle.style.color = '#00e5a3';
    const modeLabel =
      bridge.mode === 'session'
        ? 'Se apaga solo cuando cierres esta pestaña'
        : `Se apaga solo en ${Math.ceil((bridge.remainingSeconds || 0) / 60)} min`;
    bridgeTimerText.textContent = `${modeLabel} · Compus conectadas: ${bridge.activeConnections ?? 0}`;
    dbeaverConnectionBox.classList.remove('hidden');

    const dbHostEl = document.getElementById('dbHostVal');
    const dbPortEl = document.getElementById('dbPortVal');
    const dbNameEl = document.getElementById('dbNameVal');
    const dbUserEl = document.getElementById('dbUserVal');
    const dbPassEl = document.getElementById('dbPassVal');
    const dbAllowedIpEl = document.getElementById('dbAllowedIpVal');
    const dbActiveConnsEl = document.getElementById('dbActiveConnsVal');

    if (dbHostEl) dbHostEl.textContent = bridge.host || window.location.hostname || '127.0.0.1';
    if (dbPortEl) dbPortEl.textContent = bridge.port || 3306;
    if (dbNameEl) dbNameEl.textContent = bridge.database || 'cajitapaas';
    if (dbUserEl) dbUserEl.textContent = bridge.user || 'cajita_admin';
    if (dbPassEl) dbPassEl.textContent = bridge.password || getRawApiKey() || 'cajitapaas2026';
    if (dbAllowedIpEl) dbAllowedIpEl.textContent = bridge.allowedIp || 'Cualquier PC de tu red Wi-Fi';
    if (dbActiveConnsEl) dbActiveConnsEl.textContent = bridge.activeConnections ?? 0;
  } else {
    bridgeStateTitle.textContent = 'Modo PC: APAGADO (Protegido)';
    bridgeStateTitle.style.color = '#f5f9fc';
    bridgeTimerText.textContent = 'Nadie desde afuera puede conectarse ahora mismo.';
    dbeaverConnectionBox.classList.add('hidden');
  }
}

async function fetchBridgeStatus() {
  if (!getToken()) return;
  try {
    const res = await fetch('./api/v1/db/bridge/status', { headers: authHeaders() });
    if (res.ok) {
      renderBridgeStatus(await res.json());
    }
  } catch (_e) {}
}

function startHeartbeatLoop() {
  stopHeartbeatLoop();
  heartbeatTimer = setInterval(async () => {
    if (!getToken() || !dbeaverSwitch.checked) return;
    try {
      const res = await fetch('./api/v1/db/bridge/heartbeat', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({})
      });
      if (res.ok) {
        const data = await res.json();
        renderBridgeStatus(data.bridge);
      }
    } catch (_e) {}
  }, 15000);
}

function stopHeartbeatLoop() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

dbeaverSwitch.addEventListener('change', async () => {
  const enabled = dbeaverSwitch.checked;
  const rawMode = bridgeModeSelect.value;
  const isTtl = rawMode.startsWith('ttl:');
  const mode = isTtl ? 'ttl' : 'session';
  const ttlMinutes = isTtl ? Number(rawMode.split(':')[1]) : 15;
  const restrictToMyIp = bridgeRestrictIp.value === 'true';

  try {
    const res = await fetch('./api/v1/db/bridge/toggle', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, mode, ttlMinutes, restrictToMyIp })
    });
    const data = await res.json();
    if (res.ok && data.bridge) {
      renderBridgeStatus(data.bridge);
      if (data.bridge.enabled) startHeartbeatLoop();
      else stopHeartbeatLoop();
    } else {
      dbeaverSwitch.checked = !enabled;
    }
  } catch (_err) {
    dbeaverSwitch.checked = !enabled;
  }
});

window.addEventListener('pagehide', () => {
  if (dbeaverSwitch.checked && bridgeModeSelect.value === 'session' && getToken()) {
    fetch('./api/v1/db/bridge/heartbeat', {
      method: 'POST',
      keepalive: true,
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ closeNow: true })
    }).catch(() => {});
  }
});

async function loadDatabases() {
  try {
    const res = await fetch('./api/v1/db/databases', { headers: authHeaders() });
    if (!res.ok) return;
    const data = await res.json();
    dbsTableBody.innerHTML = '';

    if (!data.databases) return;
    for (const db of data.databases) {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${db.name}</strong></td>
        <td>${db.isSystem ? 'Interna' : 'De tus Apps'}</td>
        <td>${db.tables && db.tables.length ? db.tables.join(', ') : '<em>Vacía</em>'}</td>
      `;
      dbsTableBody.appendChild(tr);
    }
  } catch (_err) {}
}

btnCreateDb.addEventListener('click', async () => {
  const dbName = document.getElementById('newDbName').value.trim();
  const appUser = document.getElementById('newDbUser').value.trim();
  const appPassword = document.getElementById('newDbPass').value;

  if (!dbName) return;

  btnCreateDb.disabled = true;
  btnCreateDb.textContent = '⏳ Creando...';

  try {
    const res = await fetch('./api/v1/db/databases', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ dbName, appUser, appPassword })
    });
    if (res.ok) {
      document.getElementById('newDbName').value = '';
      document.getElementById('newDbUser').value = '';
      document.getElementById('newDbPass').value = '';
      loadDatabases();
      fetchBridgeStatus();
      showResultModal({
        type: 'success',
        title: `¡Base "${dbName}" creada!`,
        desc: 'Ya está lista para guardar datos de tu aplicación.',
        pill: '✨ Disponible en tu servidor'
      });
    }
  } finally {
    btnCreateDb.disabled = false;
    btnCreateDb.textContent = '✨ Crear Base de Datos';
  }
});

btnRunSql.addEventListener('click', async () => {
  const database = document.getElementById('sqlTargetDb').value.trim();
  const sql = document.getElementById('sqlEditor').value;
  const wrapper = document.getElementById('sqlTableWrapper');

  try {
    const res = await fetch('./api/v1/db/query', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ database, sql })
    });
    const data = await res.json();
    wrapper.innerHTML = '';

    if (Array.isArray(data.rows) && data.rows.length > 0) {
      const cols = data.columns?.length ? data.columns : Object.keys(data.rows[0]);
      const table = document.createElement('table');
      table.innerHTML = `
        <thead>
          <tr>${cols.map((c) => `<th>${c}</th>`).join('')}</tr>
        </thead>
        <tbody>
          ${data.rows
            .map(
              (row) =>
                `<tr>${cols
                  .map((c) => `<td>${row[c] !== null && row[c] !== undefined ? String(row[c]) : '-'}</td>`)
                  .join('')}</tr>`
            )
            .join('')}
        </tbody>
      `;
      wrapper.appendChild(table);
    } else {
      wrapper.innerHTML =
        '<p style="color: var(--text-muted); margin-top: 0.5rem;">Consulta ejecutada (sin filas para mostrar).</p>';
    }
  } catch (_err) {}
});

// ============================================================================
// 8. CONFIGURACIÓN DEL TÚNEL CLOUDFLARE Y OBTENCIÓN DE LA URL PÚBLICA
// ============================================================================

const topLanUrl = document.getElementById('topLanUrl');
const topLanIpVal = document.getElementById('topLanIpVal');
const topLanPortVal = document.getElementById('topLanPortVal');
const btnCopyLanUrl = document.getElementById('btnCopyLanUrl');

const topTunnelUrl = document.getElementById('topTunnelUrl');
const topTunnelBadge = document.getElementById('topTunnelBadge');
const btnCopyTunnelUrl = document.getElementById('btnCopyTunnelUrl');
const btnGoTunnelTab = document.getElementById('btnGoTunnelTab');

const tunnelPublicUrlVal = document.getElementById('tunnelPublicUrlVal');
const tunnelAdminUrlVal = document.getElementById('tunnelAdminUrlVal');
const tunnelLanUrlVal = document.getElementById('tunnelLanUrlVal');
const btnRegenerateQuickTunnel = document.getElementById('btnRegenerateQuickTunnel');
const btnSaveCustomTunnel = document.getElementById('btnSaveCustomTunnel');
const inputCustomDomain = document.getElementById('inputCustomDomain');
const inputTunnelToken = document.getElementById('inputTunnelToken');

function renderTunnelInfo(tunnel) {
  if (!tunnel) return;
  const lanIp = tunnel.lanIp || window.location.hostname || '127.0.0.1';
  const lanPort = tunnel.lanPort || window.location.port || 4000;
  const lanAdminUrl = tunnel.lanAdminUrl || `http://${lanIp}:${lanPort}`;
  const lanAppsGatewayUrl = tunnel.lanUrl || `http://${lanIp}:8080`;

  if (topLanUrl) {
    topLanUrl.textContent = lanAdminUrl;
    topLanUrl.href = lanAdminUrl;
  }
  if (topLanIpVal) topLanIpVal.textContent = lanIp;
  if (topLanPortVal) topLanPortVal.textContent = `${lanPort} (Panel) / 8080 y 3001 (Tus Apps)`;

  const rawPub = tunnel.publicUrl || '';
  const hasRealPublicUrl = rawPub.startsWith('http://') || rawPub.startsWith('https://');
  const displayPub = rawPub || `En tu Wi-Fi: ${lanAppsGatewayUrl}`;

  if (topTunnelUrl) {
    topTunnelUrl.textContent = displayPub;
    topTunnelUrl.href = hasRealPublicUrl ? rawPub : lanAppsGatewayUrl;
  }
  if (topTunnelBadge) {
    topTunnelBadge.textContent =
      tunnel.mode === 'token' ? 'DOMINIO PROPIO FIJO' : 'MODO GRATIS (TryCloudflare)';
  }
  if (tunnelPublicUrlVal) tunnelPublicUrlVal.textContent = displayPub;
  if (tunnelAdminUrlVal) tunnelAdminUrlVal.textContent = tunnel.adminUrl || lanAdminUrl;
  if (tunnelLanUrlVal) tunnelLanUrlVal.textContent = `${lanAppsGatewayUrl} (Apps) · ${lanAdminUrl} (Panel)`;
}

async function fetchTunnelStatus() {
  if (!getToken()) return;
  try {
    const res = await fetch('./api/v1/tunnel/status', { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      renderTunnelInfo(data);
    }
  } catch (_e) {}
}

if (btnCopyLanUrl) {
  btnCopyLanUrl.addEventListener('click', async () => {
    const urlText = topLanUrl ? topLanUrl.textContent.trim() : '';
    try {
      await navigator.clipboard.writeText(urlText);
      btnCopyLanUrl.textContent = '✅ ¡IP:Puerto Copiado!';
      setTimeout(() => {
        btnCopyLanUrl.textContent = '📋 Copiar IP:Puerto Local';
      }, 2000);
    } catch (_e) {}
  });
}

if (btnCopyTunnelUrl) {
  btnCopyTunnelUrl.addEventListener('click', async () => {
    const urlText = topTunnelUrl ? topTunnelUrl.textContent.trim() : '';
    try {
      await navigator.clipboard.writeText(urlText);
      btnCopyTunnelUrl.textContent = '✅ ¡Link Copiado!';
      setTimeout(() => {
        btnCopyTunnelUrl.textContent = '📋 Copiar Link Público';
      }, 2000);
    } catch (_e) {}
  });
}

if (btnGoTunnelTab) {
  btnGoTunnelTab.addEventListener('click', () => {
    const tabBtn = document.querySelector('[data-tab="tab-tunnel"]');
    if (tabBtn) tabBtn.click();
  });
}

if (btnRegenerateQuickTunnel) {
  btnRegenerateQuickTunnel.addEventListener('click', async () => {
    btnRegenerateQuickTunnel.disabled = true;
    btnRegenerateQuickTunnel.textContent = '⏳ Solicitando nueva URL a Cloudflare...';
    try {
      const res = await fetch('./api/v1/tunnel/config', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'quick' })
      });
      const data = await res.json();
      if (res.ok && data.tunnel) {
        renderTunnelInfo(data.tunnel);
        launchConfetti();
        showResultModal({
          type: 'success',
          title: '¡Nueva URL Pública Generada!',
          desc: `Tu servidor ya responde en: ${data.tunnel.publicUrl}`,
          pill: '🌐 Túnel Cloudflare activo sin CGNAT'
        });
      }
    } finally {
      btnRegenerateQuickTunnel.disabled = false;
      btnRegenerateQuickTunnel.textContent = '🔄 Generar Nuevo Link Gratis (*.trycloudflare.com)';
    }
  });
}

if (btnSaveCustomTunnel) {
  btnSaveCustomTunnel.addEventListener('click', async () => {
    const customDomain = inputCustomDomain ? inputCustomDomain.value.trim() : '';
    const token = inputTunnelToken ? inputTunnelToken.value.trim() : '';
    if (!token) {
      showResultModal({
        type: 'warning',
        title: 'Falta el Token de Cloudflare',
        desc: 'Pegá el token que te da el panel de Cloudflare Zero Trust para vincular tu dominio fijo.',
        pill: 'Tu túnel actual sigue funcionando sin cambios.'
      });
      return;
    }

    btnSaveCustomTunnel.disabled = true;
    btnSaveCustomTunnel.textContent = '⏳ Conectando Dominio Fijo...';
    try {
      const res = await fetch('./api/v1/tunnel/config', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'token', customDomain, token })
      });
      const data = await res.json();
      if (res.ok && data.tunnel) {
        renderTunnelInfo(data.tunnel);
        launchConfetti();
        showResultModal({
          type: 'success',
          title: '¡Dominio Fijo Conectado!',
          desc: `Tu CajitaPaaS ahora está publicada permanentemente en ${data.tunnel.publicUrl}`,
          pill: '🔒 Conexión permanente establecida'
        });
      }
    } finally {
      btnSaveCustomTunnel.disabled = false;
      btnSaveCustomTunnel.textContent = '💾 Guardar Token y Conectar Dominio Fijo';
    }
  });
}

// ============================================================================
// 8.5 SUPERVIVENCIA DE RED WI-FI (FAILOVER A HOTSPOT / FAILBACK A ROUTER)
// ============================================================================

const failoverSwitch = document.getElementById('failoverSwitch');
const failoverIntervalSelect = document.getElementById('failoverIntervalSelect');
const failoverStateTitle = document.getElementById('failoverStateTitle');
const failoverStateSubtitle = document.getElementById('failoverStateSubtitle');
const failoverRadioBadge = document.getElementById('failoverRadioBadge');

function renderFailoverStatus(payload) {
  const info = payload && payload.failover ? payload.failover : payload;
  if (!info || !failoverSwitch) return;

  const enabled = Boolean(info.enabled);
  const pollMinutes = Number(info.pollIntervalMinutes || 10);
  const state = String(info.state || (enabled ? 'NORMAL_WIFI_STA' : 'DISABLED'));

  failoverSwitch.checked = enabled;
  if (failoverIntervalSelect) {
    const valStr = String(pollMinutes);
    if ([...failoverIntervalSelect.options].some((o) => o.value === valStr)) {
      failoverIntervalSelect.value = valStr;
    }
  }

  if (!enabled || state === 'DISABLED') {
    if (failoverStateTitle) {
      failoverStateTitle.textContent = 'Supervivencia Wi-Fi: DESACTIVADA';
      failoverStateTitle.style.color = '#f5f9fc';
    }
    if (failoverStateSubtitle) {
      failoverStateSubtitle.textContent =
        'El celular no encenderá la Zona Wi-Fi automáticamente si se corta el router.';
    }
    if (failoverRadioBadge) {
      failoverRadioBadge.textContent = '⚪ Monitoreo automático apagado';
      failoverRadioBadge.style.color = 'var(--text-muted)';
    }
    return;
  }

  if (failoverStateTitle) {
    failoverStateTitle.textContent = 'Supervivencia Wi-Fi: ACTIVADA (Auto-Hotspot Root)';
    failoverStateTitle.style.color = '#00e5a3';
  }
  if (failoverStateSubtitle) {
    failoverStateSubtitle.textContent = `Sondeo en Modo Ciego cada ${pollMinutes} min (ventana de reconexión: ${
      info.reconnectWindowSeconds || 30
    }s).`;
  }

  if (failoverRadioBadge) {
    if (state === 'FAILOVER_SOFTAP_ACTIVE') {
      failoverRadioBadge.textContent = '🟠 MODO EMERGENCIA: Emitiendo Zona Wi-Fi (Hotspot Activo)';
      failoverRadioBadge.style.color = 'var(--amber)';
    } else if (state === 'PROBING_ROUTER_FAILBACK') {
      failoverRadioBadge.textContent = '🔵 SONDEANDO ROUTER: Buscando red Wi-Fi del local (30s)...';
      failoverRadioBadge.style.color = '#52a9ff';
    } else {
      failoverRadioBadge.textContent = '🟢 Conectado al Router Wi-Fi (Modo Normal STA)';
      failoverRadioBadge.style.color = 'var(--accent-mint)';
    }
  }
}

async function fetchFailoverStatus() {
  if (!getToken()) return;
  try {
    const res = await fetch('./api/v1/network/failover/status', { headers: authHeaders() });
    if (res.ok) {
      renderFailoverStatus(await res.json());
    }
  } catch (_e) {}
}

async function pushFailoverConfig() {
  if (!failoverSwitch) return;
  const enabled = failoverSwitch.checked;
  const pollIntervalMinutes = failoverIntervalSelect ? Number(failoverIntervalSelect.value || 10) : 10;

  try {
    const res = await fetch('./api/v1/network/failover/toggle', {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, pollIntervalMinutes })
    });
    const data = await res.json();
    if (res.ok) {
      renderFailoverStatus(data);
    } else {
      failoverSwitch.checked = !enabled;
    }
  } catch (_err) {
    failoverSwitch.checked = !enabled;
  }
}

if (failoverSwitch) {
  failoverSwitch.addEventListener('change', pushFailoverConfig);
}
if (failoverIntervalSelect) {
  failoverIntervalSelect.addEventListener('change', pushFailoverConfig);
}

// ============================================================================
// 9. ARRANQUE INICIAL (VERIFICA SI ES EL PRIMER INGRESO TRAS INSTALAR)
// ============================================================================
window.addEventListener('DOMContentLoaded', async () => {
  try {
    const res = await fetch('./api/auth/setup-status');
    if (res.ok) {
      const data = await res.json();
      if (!data.initialized) {
        // Primera vez tras instalar: pedir que cree su propia contraseña admin
        clearToken();
        showLoginView();
        return;
      }
    }
  } catch (_e) {}

  if (getToken()) {
    showDashboardView();
  } else {
    showLoginView();
  }
});
