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
const zipDbName = document.getElementById('zipDbName');
const zipFileInput = document.getElementById('zipFileInput');
const zipDropzone = document.getElementById('zipDropzone');
const zipChosenText = document.getElementById('zipChosenText');
const btnDeployZip = document.getElementById('btnDeployZip');

const gitAppName = document.getElementById('gitAppName');
const gitAppRole = document.getElementById('gitAppRole');
const gitRoutePath = document.getElementById('gitRoutePath');
const gitDbName = document.getElementById('gitDbName');
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

/**
 * Copia texto al portapapeles de forma universal:
 * - Usa Clipboard API cuando hay contexto seguro (HTTPS / localhost).
 * - Usa respaldo con textarea + document.execCommand('copy') para HTTP en IP LAN (http://192.168.x.x:4000)
 *   o WebViews Android donde navigator.clipboard no está disponible.
 */
async function copyTextToClipboard(rawText) {
  const text = String(rawText ?? '');
  if (!text) return false;

  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_e) {}
  }

  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('autocomplete', 'off');
    ta.setAttribute('autocorrect', 'off');
    ta.style.position = 'fixed';
    ta.style.top = '0';
    ta.style.left = '0';
    ta.style.width = '2em';
    ta.style.height = '2em';
    ta.style.padding = '0';
    ta.style.border = 'none';
    ta.style.outline = 'none';
    ta.style.boxShadow = 'none';
    ta.style.background = 'transparent';
    ta.style.opacity = '0.01';
    ta.style.zIndex = '25000';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length);
    const copied = document.execCommand('copy');
    ta.remove();
    return Boolean(copied);
  } catch (_err) {
    return false;
  }
}

function stripJsonCommentsClient(raw) {
  let noComments = '';
  let inStr = false;
  let esc = false;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    const next = i + 1 < raw.length ? raw[i + 1] : '';
    if (inStr) {
      noComments += c;
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      noComments += c;
      continue;
    }
    if (c === '/' && next === '/') {
      i += 2;
      while (i < raw.length && raw[i] !== '\n') i++;
      i--;
      continue;
    }
    if (c === '/' && next === '*') {
      i += 2;
      while (i + 1 < raw.length && !(raw[i] === '*' && raw[i + 1] === '/')) i++;
      i++;
      continue;
    }
    noComments += c;
  }
  let res = '';
  inStr = false;
  esc = false;
  for (let i = 0; i < noComments.length; i++) {
    const c = noComments[i];
    if (inStr) {
      res += c;
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      res += c;
      continue;
    }
    if (c === ',') {
      let j = i + 1;
      while (j < noComments.length && /\s/.test(noComments[j])) j++;
      if (j < noComments.length && (noComments[j] === '}' || noComments[j] === ']')) {
        continue;
      }
    }
    res += c;
  }
  return res;
}

function flattenJsonVarsClient(obj, prefix = '', out = []) {
  if (!obj || typeof obj !== 'object') return out;
  for (const [k, v] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}__${k}` : k;
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      flattenJsonVarsClient(v, fullKey, out);
    } else if (Array.isArray(v)) {
      out.push({ key: fullKey, value: JSON.stringify(v) });
    } else if (v !== undefined && v !== null) {
      out.push({ key: fullKey, value: String(v) });
    }
  }
  return out;
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
  fetchExpertConfig();
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
  const dbName = zipDbName ? zipDbName.value.trim() : '';
  zipAppName.value = appName;

  const formData = new FormData();
  formData.append('appName', appName);
  formData.append('appRole', appRole);
  formData.append('routePath', routePath);
  formData.append('healthPath', '/health');
  if (dbName) formData.append('dbName', dbName);
  formData.append('artifact', file);

  const deployQuery = `?appName=${encodeURIComponent(appName)}&appRole=${encodeURIComponent(
    appRole
  )}&routePath=${encodeURIComponent(routePath)}${dbName ? `&dbName=${encodeURIComponent(dbName)}` : ''}`;

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
  const dbName = gitDbName ? gitDbName.value.trim() : '';

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
        body: JSON.stringify({ appName, appRole, routePath, repoUrl, branch, healthPath: '/health', dbName })
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
        await new Promise((r) => setTimeout(r, 500));
        return fetchPromiseFn();
      }),
      new Promise((resolve) => setTimeout(resolve, 900))
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
      const missingDeps = data?.deployment?.missingDependencies || [];
      loadProjects();
      checkServerStatus();
      fetchExpertConfig();

      if (Array.isArray(missingDeps) && missingDeps.length > 0) {
        openDependenciesModal(appName, true);
      } else {
        showResultModal({
          type: 'success',
          title: '¡Sistema actualizado con éxito!',
          desc: 'Tu nueva versión pasó la prueba y ya está online para todos sin haber cortado el servicio.',
          pill: '✨ ¡Todo andando de diez!'
        });
      }
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
// 6. LISTA DE APPS ACTIVAS, CONSOLA EN VIVO, EDITOR .ENV, DEPENDENCIAS Y RAM
// ============================================================================

async function loadProjects() {
  try {
    const res = await fetch('./api/v1/projects', { headers: authHeaders() });
    if (!res.ok) return;
    const data = await res.json();

    appsListContainer.innerHTML = '';
    const expertPerAppRamList = document.getElementById('expertPerAppRamList');
    if (expertPerAppRamList) expertPerAppRamList.innerHTML = '';

    if (!data.pm2Processes || data.pm2Processes.length === 0) {
      appsListContainer.innerHTML = `
        <div class="app-item">
          <span style="color: var(--text-muted);">Todavía no subiste ninguna app. ¡Elegí un .ZIP arriba para empezar!</span>
        </div>
      `;
      if (expertPerAppRamList) {
        expertPerAppRamList.innerHTML = `<div style="color: var(--text-muted); font-size: 0.85rem;">No hay apps activas todavía.</div>`;
      }
      return;
    }

    const activeSlots = data.activeSlots || {};
    const lanHost = data.lanIp || window.location.hostname || '127.0.0.1';
    const defaultMaxRam = data.defaultMaxRamMb || 256;

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
      const btnOpenLabel = isWebFront ? '🌐 Abrir Web (:8080)' : `🔌 Abrir API (:${port})`;
      const slotColor = (slotInfo.activeColor || (proc.name.endsWith('-blue') ? 'blue' : 'green')).toUpperCase();
      const maxRamMb = slotInfo.maxRamMb || defaultMaxRam;
      const restartCount = slotInfo.restartCount || 0;
      const missingDeps = Array.isArray(slotInfo.missingDependencies) ? slotInfo.missingDependencies : [];

      const roleBadge =
        role === 'frontend'
          ? '<span style="background:rgba(24,144,255,0.2);color:#52a9ff;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">🖥️ FRONTEND</span>'
          : role === 'backend'
          ? '<span style="background:rgba(0,229,163,0.18);color:#00e5a3;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">⚙️ BACKEND API</span>'
          : '<span style="background:rgba(255,255,255,0.12);color:#fff;padding:0.15rem 0.5rem;border-radius:6px;font-size:0.75rem;font-weight:800;margin-left:0.4rem;">🚀 FULLSTACK</span>';

      const missingDepsBadge =
        missingDeps.length > 0
          ? `<button type="button" class="btn-open-deps" data-app="${baseAppName}" style="background:rgba(255,179,0,0.2);border:1px solid var(--amber);color:#ffd54f;padding:0.15rem 0.55rem;border-radius:6px;font-size:0.74rem;font-weight:800;margin-left:0.4rem;cursor:pointer;">⚠️ Faltan ${missingDeps.length} dependencias (Autorizar)</button>`
          : '';

      const item = document.createElement('div');
      item.className = 'app-item';
      item.innerHTML = `
        <div class="app-item-info">
          <span class="live-dot"></span>
          <div>
            <div class="app-item-name" style="display:flex;align-items:center;flex-wrap:wrap;gap:0.25rem;">
              ${baseAppName} ${roleBadge} ${missingDepsBadge}
            </div>
            <div class="app-item-meta">
              URL Principal: <a href="${primaryUrl}" target="_blank" rel="noopener" style="color:var(--accent-mint);font-weight:800;font-family:monospace;">${primaryUrl}</a>
              <span style="opacity:0.8;">${secondaryNote}</span> ·
              Ruta: <strong>${route}</strong> ·
              Slot <strong>${slotColor}</strong>
              ${role !== 'frontend' ? ` · BD: <strong style="color:var(--accent-mint); font-family:monospace;">${slotInfo.dbName || slotInfo.database || 'cajitapaas'}</strong> · RAM máx: <strong>${maxRamMb} MB</strong>${restartCount > 0 ? ` · Reinicios: <strong style="color:var(--amber);">${restartCount}</strong>` : ''}` : ''}
            </div>
          </div>
        </div>
        <div style="display:flex; gap:0.45rem; align-items:center; flex-wrap:wrap;">
          <a
            href="${primaryUrl}"
            target="_blank"
            rel="noopener"
            class="btn btn-small"
            style="text-decoration:none;"
          >
            ${btnOpenLabel}
          </a>
          ${role !== 'frontend' ? `
          <button
            type="button"
            class="btn btn-secondary btn-small btn-open-console"
            data-app="${baseAppName}"
            title="Ver consola y logs en vivo"
          >
            📜 Consola
          </button>
          <button
            type="button"
            class="btn btn-secondary btn-small btn-open-env"
            data-app="${baseAppName}"
            title="Editar variables de entorno .env"
          >
            ⚙️ Variables .env
          </button>
          <button
            type="button"
            class="btn btn-secondary btn-small btn-open-deps"
            data-app="${baseAppName}"
            style="${missingDeps.length > 0 ? 'border-color:var(--amber);color:#ffd54f;' : ''}"
            title="Ver o autorizar dependencias NPM"
          >
            📦 Dependencias${missingDeps.length > 0 ? ` (${missingDeps.length})` : ''}
          </button>
          <button
            type="button"
            class="btn btn-small btn-change-db"
            data-app="${baseAppName}"
            data-db="${slotInfo.dbName || slotInfo.database || 'cajitapaas'}"
            style="background:rgba(0, 229, 163, 0.15); border: 1px solid rgba(0, 229, 163, 0.4); color: var(--accent-mint);"
          >
            🗄️ Cambiar BD
          </button>
          ` : ''}
          <button
            type="button"
            class="btn btn-secondary btn-small btn-select-app"
            data-app="${baseAppName}"
            data-role="${role}"
            data-route="${route}"
            data-db="${slotInfo.dbName || slotInfo.database || ''}"
          >
            ✏️ Actualizar
          </button>
          <button type="button" class="btn btn-danger btn-small btn-stop-app" data-app="${baseAppName}">
            🛑 Apagar
          </button>
        </div>
      `;
      appsListContainer.appendChild(item);

      // Añadir fila en el panel de Modo Experto para ajustar la RAM individual de esta app
      if (expertPerAppRamList && role !== 'frontend') {
        const ramRow = document.createElement('div');
        ramRow.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:0.75rem;padding:0.55rem 0.85rem;background:var(--bg-input);border:1px solid var(--border);border-radius:10px;flex-wrap:wrap;';
        ramRow.innerHTML = `
          <div>
            <strong style="color:var(--accent-mint);font-family:monospace;">${baseAppName}</strong>
            <span style="font-size:0.8rem;color:var(--text-muted);margin-left:0.4rem;">(Puerto :${port})</span>
          </div>
          <div style="display:flex;align-items:center;gap:0.5rem;">
            <span style="font-size:0.82rem;color:var(--text-muted);">RAM máx:</span>
            <select class="expert-app-ram-input" data-app="${baseAppName}" style="width:auto;min-height:38px;padding:0.35rem 0.7rem;font-size:0.88rem;">
              <option value="128" ${maxRamMb === 128 ? 'selected' : ''}>128 MB</option>
              <option value="192" ${maxRamMb === 192 ? 'selected' : ''}>192 MB</option>
              <option value="256" ${maxRamMb === 256 ? 'selected' : ''}>256 MB</option>
              <option value="384" ${maxRamMb === 384 ? 'selected' : ''}>384 MB</option>
              <option value="512" ${maxRamMb === 512 ? 'selected' : ''}>512 MB</option>
              <option value="768" ${maxRamMb === 768 ? 'selected' : ''}>768 MB</option>
              <option value="1024" ${maxRamMb === 1024 ? 'selected' : ''}>1024 MB</option>
            </select>
          </div>
        `;
        expertPerAppRamList.appendChild(ramRow);
      }
    }

    if (expertPerAppRamList && expertPerAppRamList.children.length === 0) {
      expertPerAppRamList.innerHTML = `<div style="color: var(--text-muted); font-size: 0.85rem;">Subí un Backend API para asignarle un límite de memoria específico.</div>`;
    }

    appsListContainer.querySelectorAll('.btn-open-console').forEach((btn) => {
      btn.addEventListener('click', () => openLiveConsoleModal(btn.dataset.app));
    });

    appsListContainer.querySelectorAll('.btn-open-env').forEach((btn) => {
      btn.addEventListener('click', () => openEnvEditorModal(btn.dataset.app));
    });

    appsListContainer.querySelectorAll('.btn-open-deps').forEach((btn) => {
      btn.addEventListener('click', () => openDependenciesModal(btn.dataset.app, false));
    });

    appsListContainer.querySelectorAll('.btn-change-db').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const app = btn.dataset.app;
        const currentDb = btn.dataset.db || 'cajitapaas';
        const newDb = prompt(`Ingresá el nombre de la base de datos para "${app}":`, currentDb);
        if (!newDb || newDb.trim() === currentDb) return;
        try {
          const res = await fetch(`./api/v1/projects/${encodeURIComponent(app)}/database`, {
            method: 'POST',
            headers: { ...authHeaders(), 'Content-Type': 'application/json' },
            body: JSON.stringify({ database: newDb.trim() })
          });
          const d = await res.json();
          if (res.ok) {
            loadProjects();
            loadDatabases();
            showResultModal({
              type: 'success',
              title: '¡Base de Datos Actualizada!',
              desc: `El servicio "${app}" ahora está conectado dinámicamente a la base "${newDb.trim()}".`,
              pill: '🗄️ Cambio aplicado en caliente y sincronizado con .env'
            });
          } else {
            alert(d.error || 'Error al cambiar la base de datos');
          }
        } catch (_err) {
          alert('Error de conexión al cambiar la base de datos');
        }
      });
    });

    appsListContainer.querySelectorAll('.btn-select-app').forEach((btn) => {
      btn.addEventListener('click', () => {
        const appName = btn.dataset.app;
        const role = btn.dataset.role;
        const route = btn.dataset.route;
        const db = btn.dataset.db || '';
        zipAppName.value = appName;
        gitAppName.value = appName;
        if (zipAppRole) zipAppRole.value = role;
        if (gitAppRole) gitAppRole.value = role;
        if (zipRoutePath) zipRoutePath.value = route;
        if (gitRoutePath) gitRoutePath.value = route;
        if (zipDbName) zipDbName.value = db;
        if (gitDbName) gitDbName.value = db;
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
// 6.1 MODAL DE DEPENDENCIAS NPM (CON AUTORIZACIÓN PREVIA DEL USUARIO)
// ============================================================================

const modalDependencies = document.getElementById('modalDependencies');
const depModalAppName = document.getElementById('depModalAppName');
const depAuthWarningBox = document.getElementById('depAuthWarningBox');
const depListContainer = document.getElementById('depListContainer');
const inputExtraNpmPkg = document.getElementById('inputExtraNpmPkg');
const btnInstallSinglePkg = document.getElementById('btnInstallSinglePkg');
const btnAuthorizeInstallDeps = document.getElementById('btnAuthorizeInstallDeps');
const btnCancelDepModal = document.getElementById('btnCancelDepModal');
const btnCloseDepModalTop = document.getElementById('btnCloseDepModalTop');

let activeDepAppName = '';

async function openDependenciesModal(appName, isAutoPrompt = false) {
  activeDepAppName = appName;
  if (!modalDependencies) return;
  depModalAppName.textContent = appName;
  depListContainer.innerHTML = `<div style="color:var(--text-muted);padding:0.5rem;">🔍 Escaneando package.json de "${appName}"...</div>`;
  modalDependencies.classList.remove('hidden');

  try {
    const res = await fetch(`./api/v1/projects/${encodeURIComponent(appName)}/dependencies`, {
      headers: authHeaders()
    });
    const data = await res.json();
    renderDependenciesModalContent(data, isAutoPrompt);
  } catch (_err) {
    depListContainer.innerHTML = `<div style="color:var(--danger);padding:0.5rem;">No se pudo leer las dependencias del proyecto.</div>`;
  }
}

function renderDependenciesModalContent(data, isAutoPrompt = false) {
  const deps = Array.isArray(data?.dependencies) ? data.dependencies : [];
  const missing = Array.isArray(data?.missing) ? data.missing : [];

  if (missing.length > 0) {
    depAuthWarningBox.classList.remove('hidden');
    depAuthWarningBox.innerHTML = `
      ⚠️ <strong>Autorización requerida:</strong> El proyecto <strong>${data.appName}</strong> necesita instalar <strong>${missing.length} dependencia(s)</strong> (${missing.map((m) => `<code>${m.name}</code>`).join(', ')}).<br/>
      ¿Autorizás a CajitaPaaS a descargarlas e instalarlas en el almacén compartido del teléfono?
    `;
    btnAuthorizeInstallDeps.disabled = false;
    btnAuthorizeInstallDeps.textContent = `✅ Autorizar e Instalar (${missing.length})`;
  } else {
    depAuthWarningBox.classList.add('hidden');
    btnAuthorizeInstallDeps.disabled = true;
    btnAuthorizeInstallDeps.textContent = '✔ Todas las dependencias están instaladas';
  }

  if (deps.length === 0) {
    depListContainer.innerHTML = `
      <div style="color:var(--text-muted);padding:0.5rem;">
        ${data?.hasPackageJson ? 'Este proyecto no declara dependencias adicionales en su package.json.' : 'Este proyecto no incluye archivo package.json (usa los módulos globales de CajitaPaaS).'}
      </div>
    `;
    return;
  }

  depListContainer.innerHTML = deps
    .map((d) => {
      let badge = '';
      if (d.status === 'preinstalled') {
        badge = '<span style="color:var(--accent-mint);font-weight:800;font-size:0.8rem;">🟢 Incluida de fábrica</span>';
      } else if (d.status === 'shared') {
        badge = '<span style="color:#52a9ff;font-weight:800;font-size:0.8rem;">📦 En almacén compartido</span>';
      } else if (d.status === 'local') {
        badge = '<span style="color:#b388ff;font-weight:800;font-size:0.8rem;">📂 En carpeta local</span>';
      } else if (d.status === 'builtin') {
        badge = '<span style="color:var(--text-muted);font-weight:700;font-size:0.8rem;">⚙️ Nativa Node.js</span>';
      } else {
        badge = '<span style="color:var(--amber);font-weight:800;font-size:0.8rem;">🟠 Falta instalar (Requiere autorización)</span>';
      }
      return `
        <div style="display:flex;justify-content:space-between;align-items:center;padding:0.45rem 0.5rem;border-bottom:1px solid var(--border);gap:0.5rem;flex-wrap:wrap;">
          <div>
            <strong style="font-family:monospace;color:var(--text-main);">${d.name}</strong>
            <span style="color:var(--text-muted);font-size:0.8rem;margin-left:0.35rem;">${d.version || ''}</span>
          </div>
          <div>${badge}</div>
        </div>
      `;
    })
    .join('');
}

async function triggerAuthorizedNpmInstall(customPackages = null) {
  if (!activeDepAppName) return;
  btnAuthorizeInstallDeps.disabled = true;
  if (btnInstallSinglePkg) btnInstallSinglePkg.disabled = true;
  const origBtnText = btnAuthorizeInstallDeps.textContent;
  btnAuthorizeInstallDeps.textContent = '⏳ Descargando e instalando en almacén compartido...';

  try {
    const bodyPayload = customPackages ? { packages: customPackages } : {};
    const res = await fetch(`./api/v1/projects/${encodeURIComponent(activeDepAppName)}/dependencies/install`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(bodyPayload)
    });
    const data = await res.json();
    if (res.ok) {
      if (inputExtraNpmPkg) inputExtraNpmPkg.value = '';
      if (data.dependencyReport) {
        renderDependenciesModalContent(data.dependencyReport, false);
      }
      loadProjects();
      fetchExpertConfig();
      launchConfetti();
    } else {
      alert(data.error || 'No se pudieron instalar las dependencias.');
      btnAuthorizeInstallDeps.textContent = origBtnText;
      btnAuthorizeInstallDeps.disabled = false;
    }
  } catch (_err) {
    alert('Error de red al descargar paquetes desde NPM.');
    btnAuthorizeInstallDeps.textContent = origBtnText;
    btnAuthorizeInstallDeps.disabled = false;
  } finally {
    if (btnInstallSinglePkg) btnInstallSinglePkg.disabled = false;
  }
}

if (btnAuthorizeInstallDeps) {
  btnAuthorizeInstallDeps.addEventListener('click', () => triggerAuthorizedNpmInstall(null));
}
if (btnInstallSinglePkg) {
  btnInstallSinglePkg.addEventListener('click', () => {
    const pkg = inputExtraNpmPkg ? inputExtraNpmPkg.value.trim() : '';
    if (!pkg) return;
    triggerAuthorizedNpmInstall([pkg]);
  });
}
[btnCancelDepModal, btnCloseDepModalTop].forEach((b) => {
  if (b) b.addEventListener('click', () => modalDependencies.classList.add('hidden'));
});

// ============================================================================
// 6.2 MODAL EDITOR VISUAL DE VARIABLES DE ENTORNO (.ENV)
// ============================================================================

const modalEnvEditor = document.getElementById('modalEnvEditor');
const envModalAppName = document.getElementById('envModalAppName');
const envModalSubtitle = document.getElementById('envModalSubtitle');
const envRowsContainer = document.getElementById('envRowsContainer');
const envRawContainer = document.getElementById('envRawContainer');
const envRawTextarea = document.getElementById('envRawTextarea');
const btnAddEnvRow = document.getElementById('btnAddEnvRow');
const btnFillDefaultDbEnv = document.getElementById('btnFillDefaultDbEnv');
const btnToggleRawEnvMode = document.getElementById('btnToggleRawEnvMode');
const btnSaveEnvModal = document.getElementById('btnSaveEnvModal');
const btnCancelEnvModal = document.getElementById('btnCancelEnvModal');
const btnCloseEnvModalTop = document.getElementById('btnCloseEnvModalTop');

let activeEnvAppName = '';
let activeEnvInjectedDefaults = {};
let activeEnvConfigType = 'env';
let activeEnvRawContent = '';
let isRawEnvMode = false;

async function openEnvEditorModal(appName) {
  activeEnvAppName = appName;
  isRawEnvMode = false;
  if (!modalEnvEditor) return;

  envModalAppName.textContent = appName;
  envRowsContainer.classList.remove('hidden');
  envRawContainer.classList.add('hidden');
  if (btnToggleRawEnvMode) btnToggleRawEnvMode.textContent = '📝 Modo Texto .env';
  envRowsContainer.innerHTML = `<div style="color:var(--text-muted);padding:0.5rem;">Cargando configuración de "${appName}"...</div>`;
  modalEnvEditor.classList.remove('hidden');

  try {
    const res = await fetch(`./api/v1/projects/${encodeURIComponent(appName)}/env`, {
      headers: authHeaders()
    });
    const data = await res.json();
    activeEnvInjectedDefaults = data.injectedDefaults || {};
    activeEnvConfigType = data.configType || 'env';
    activeEnvRawContent = data.rawContent || '';

    if (btnToggleRawEnvMode) {
      btnToggleRawEnvMode.textContent =
        activeEnvConfigType === 'appsettings' ? '📝 Modo JSON (appsettings.json)' : '📝 Modo Texto .env';
    }

    if (envModalSubtitle) {
      if (activeEnvConfigType === 'appsettings') {
        envModalSubtitle.innerHTML = `🧩 Proyecto <strong>.NET</strong> · Mostrando las claves de <code>${data.envFileName || 'appsettings.json'}</code> (usa <code>__</code> para secciones anidadas). Al guardar se actualiza <code>appsettings.json</code> y se reinicia el servicio.`;
      } else {
        envModalSubtitle.innerHTML = data.hasEnvFile
          ? `📂 Mostrando los valores actuales del archivo <code>${data.envFileName || '.env'}</code> incluido en tu proyecto.`
          : `⚡ Tu proyecto no incluía un archivo <code>.env</code>; mostrando las variables activas inyectadas por CajitaPaaS.`;
      }
    }
    renderEnvRows(Array.isArray(data.variables) ? data.variables : []);
    if (envRawTextarea) envRawTextarea.value = activeEnvRawContent;
  } catch (_err) {
    envRowsContainer.innerHTML = `<div style="color:var(--danger);padding:0.5rem;">Error cargando las variables de configuración</div>`;
  }
}

function renderEnvRows(variables) {
  envRowsContainer.innerHTML = '';
  if (variables.length === 0) {
    addEnvRowToDom('', '');
    return;
  }
  for (const item of variables) {
    addEnvRowToDom(item.key || '', item.value ?? '');
  }
}

function addEnvRowToDom(key = '', value = '') {
  const row = document.createElement('div');
  row.className = 'env-kv-row';
  row.style.cssText = 'display:flex;gap:0.45rem;align-items:center;';
  row.innerHTML = `
    <input type="text" class="env-key-input" placeholder="CLAVE (ej. ConnectionStrings__CadenaSQL)" value="${String(key).replace(/"/g, '&quot;')}" style="flex:1;min-height:40px;padding:0.4rem 0.65rem;font-family:monospace;font-size:0.88rem;font-weight:700;color:var(--accent-mint);" />
    <span style="color:var(--text-muted);font-weight:800;">=</span>
    <input type="text" class="env-val-input" placeholder="Valor..." value="${String(value).replace(/"/g, '&quot;')}" style="flex:1.6;min-height:40px;padding:0.4rem 0.65rem;font-family:monospace;font-size:0.88rem;" />
    <button type="button" class="btn btn-danger btn-small btn-remove-env-row" style="min-height:40px;padding:0.35rem 0.65rem;" title="Eliminar variable">🗑️</button>
  `;
  row.querySelector('.btn-remove-env-row').addEventListener('click', () => {
    row.remove();
  });
  envRowsContainer.appendChild(row);
}

function collectEnvVariablesFromRows() {
  const vars = [];
  envRowsContainer.querySelectorAll('.env-kv-row').forEach((row) => {
    const k = row.querySelector('.env-key-input')?.value.trim() || '';
    const v = row.querySelector('.env-val-input')?.value.trim() ?? '';
    if (k) vars.push({ key: k, value: v });
  });
  return vars;
}

if (btnAddEnvRow) {
  btnAddEnvRow.addEventListener('click', () => {
    if (isRawEnvMode) {
      if (activeEnvConfigType === 'appsettings' && envRawTextarea.value.trim().startsWith('{')) {
        alert('En modo JSON edita directamente dentro de las llaves { } o cambia a "Modo Filas Visual" para añadir una fila.');
      } else {
        envRawTextarea.value += (envRawTextarea.value.endsWith('\n') || !envRawTextarea.value ? '' : '\n') + 'NUEVA_VARIABLE=valor\n';
      }
    } else {
      addEnvRowToDom('', '');
      envRowsContainer.scrollTop = envRowsContainer.scrollHeight;
    }
  });
}

if (btnFillDefaultDbEnv) {
  btnFillDefaultDbEnv.addEventListener('click', () => {
    const current = collectEnvVariablesFromRows();
    const map = new Map(current.map((i) => [i.key, i.value]));
    const dbHost = activeEnvInjectedDefaults.DB_HOST || '127.0.0.1';
    const dbPort = activeEnvInjectedDefaults.DB_PORT || '3306';
    const dbUser = activeEnvInjectedDefaults.DB_USER || 'cajita_admin';
    const dbPass = activeEnvInjectedDefaults.DB_PASSWORD || 'cajitapaas2026';
    const dbName = activeEnvInjectedDefaults.DB_NAME || 'cajitapaas';

    const defaults =
      activeEnvConfigType === 'appsettings'
        ? {
            ConnectionStrings__DefaultConnection: `Server=${dbHost};Port=${dbPort};Database=${dbName};Uid=${dbUser};Pwd=${dbPass};`,
            DB_HOST: dbHost,
            DB_PORT: dbPort,
            DB_USER: dbUser,
            DB_PASSWORD: dbPass,
            DB_NAME: dbName
          }
        : {
            DB_HOST: dbHost,
            DB_PORT: dbPort,
            DB_USER: dbUser,
            DB_PASSWORD: dbPass,
            DB_NAME: dbName
          };
    for (const [k, v] of Object.entries(defaults)) {
      map.set(k, v);
    }
    const merged = Array.from(map.entries()).map(([key, value]) => ({ key, value }));
    renderEnvRows(merged);
    if (envRawTextarea && activeEnvConfigType !== 'appsettings') {
      envRawTextarea.value = merged.map((i) => `${i.key}=${i.value}`).join('\n');
    }
  });
}

if (btnToggleRawEnvMode) {
  btnToggleRawEnvMode.addEventListener('click', () => {
    isRawEnvMode = !isRawEnvMode;
    if (isRawEnvMode) {
      if (activeEnvConfigType === 'appsettings') {
        try {
          const clean = stripJsonCommentsClient(activeEnvRawContent || '{}');
          const baseObj = clean.trim().startsWith('{') ? JSON.parse(clean) : {};
          for (const item of collectEnvVariablesFromRows()) {
            const segs = item.key.split(/__|:/).filter(Boolean);
            let cur = baseObj;
            for (let i = 0; i < segs.length - 1; i++) {
              if (!cur[segs[i]] || typeof cur[segs[i]] !== 'object' || Array.isArray(cur[segs[i]])) {
                cur[segs[i]] = {};
              }
              cur = cur[segs[i]];
            }
            const leaf = segs[segs.length - 1];
            const prev = cur[leaf];
            if (typeof prev === 'boolean' && /^(true|false)$/i.test(item.value)) {
              cur[leaf] = /^true$/i.test(item.value);
            } else if (typeof prev === 'number' && item.value !== '' && !Number.isNaN(Number(item.value))) {
              cur[leaf] = Number(item.value);
            } else {
              cur[leaf] = item.value;
            }
          }
          envRawTextarea.value = JSON.stringify(baseObj, null, 2);
        } catch (_e) {
          envRawTextarea.value = activeEnvRawContent || '{}';
        }
      } else {
        const vars = collectEnvVariablesFromRows();
        envRawTextarea.value = vars.map((i) => `${i.key}=${i.value}`).join('\n');
      }
      envRowsContainer.classList.add('hidden');
      envRawContainer.classList.remove('hidden');
      btnToggleRawEnvMode.textContent = '🔢 Modo Filas Visual';
    } else {
      const rawVal = envRawTextarea.value.trim();
      let parsed = [];
      if (rawVal.startsWith('{')) {
        try {
          const obj = JSON.parse(stripJsonCommentsClient(rawVal));
          parsed = flattenJsonVarsClient(obj, '', []);
          activeEnvRawContent = rawVal;
        } catch (err) {
          alert(`El JSON tiene un error de sintaxis antes de cambiar de vista: ${err.message}`);
          isRawEnvMode = true;
          return;
        }
      } else {
        envRawTextarea.value.split('\n').forEach((line) => {
          const t = line.trim();
          if (t && !t.startsWith('#') && t.includes('=')) {
            const idx = t.indexOf('=');
            parsed.push({
              key: t.slice(0, idx).trim(),
              value: t.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '')
            });
          }
        });
      }
      renderEnvRows(parsed);
      envRawContainer.classList.add('hidden');
      envRowsContainer.classList.remove('hidden');
      btnToggleRawEnvMode.textContent =
        activeEnvConfigType === 'appsettings' ? '📝 Modo JSON (appsettings.json)' : '📝 Modo Texto .env';
    }
  });
}

if (btnSaveEnvModal) {
  btnSaveEnvModal.addEventListener('click', async () => {
    if (!activeEnvAppName) return;
    btnSaveEnvModal.disabled = true;
    btnSaveEnvModal.textContent = '⏳ Guardando y reiniciando...';
    try {
      const payload = isRawEnvMode
        ? { rawContent: envRawTextarea.value }
        : { variables: collectEnvVariablesFromRows() };
      const res = await fetch(`./api/v1/projects/${encodeURIComponent(activeEnvAppName)}/env`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok) {
        modalEnvEditor.classList.add('hidden');
        loadProjects();
        loadDatabases();
        launchConfetti();
        showResultModal({
          type: 'success',
          title: '¡Configuración Actualizada!',
          desc: `Se guardaron los cambios de "${activeEnvAppName}" y el servicio se reinició con la nueva configuración.`,
          pill: '⚙️ Aplicado en caliente sin resubir el .ZIP'
        });
      } else {
        alert(data.error || 'No se pudo guardar la configuración');
      }
    } catch (_err) {
      alert('Error de conexión al guardar la configuración');
    } finally {
      btnSaveEnvModal.disabled = false;
      btnSaveEnvModal.textContent = '💾 Guardar .env y Reiniciar App';
    }
  });
}

[btnCancelEnvModal, btnCloseEnvModalTop].forEach((b) => {
  if (b) b.addEventListener('click', () => modalEnvEditor.classList.add('hidden'));
});

// ============================================================================
// 6.3 MODAL CONSOLA DE LOGS EN TIEMPO REAL
// ============================================================================

const modalLiveConsole = document.getElementById('modalLiveConsole');
const consoleModalAppName = document.getElementById('consoleModalAppName');
const consoleModalMeta = document.getElementById('consoleModalMeta');
const liveConsolePre = document.getElementById('liveConsolePre');
const btnConsoleRestartApp = document.getElementById('btnConsoleRestartApp');
const btnConsoleClearLogs = document.getElementById('btnConsoleClearLogs');
const btnConsoleCopyLogs = document.getElementById('btnConsoleCopyLogs');
const btnCloseConsoleModal = document.getElementById('btnCloseConsoleModal');

let activeConsoleAppName = '';
let liveConsolePollTimer = null;

async function openLiveConsoleModal(appName) {
  activeConsoleAppName = appName;
  if (!modalLiveConsole) return;
  consoleModalAppName.textContent = appName;
  liveConsolePre.textContent = 'Conectando con la salida estándar de la aplicación...';
  modalLiveConsole.classList.remove('hidden');

  await refreshLiveConsoleLogs();
  if (liveConsolePollTimer) clearInterval(liveConsolePollTimer);
  liveConsolePollTimer = setInterval(refreshLiveConsoleLogs, 1500);
}

function closeLiveConsoleModal() {
  if (liveConsolePollTimer) {
    clearInterval(liveConsolePollTimer);
    liveConsolePollTimer = null;
  }
  if (modalLiveConsole) modalLiveConsole.classList.add('hidden');
}

async function refreshLiveConsoleLogs() {
  if (!activeConsoleAppName || modalLiveConsole.classList.contains('hidden')) return;
  try {
    const res = await fetch(`./api/v1/projects/${encodeURIComponent(activeConsoleAppName)}/logs`, {
      headers: authHeaders()
    });
    if (!res.ok) return;
    const data = await res.json();
    const stateLabel = data.nodeRuntimeActive
      ? '<span style="color:var(--accent-mint);font-weight:800;">🟢 Proceso Activo</span>'
      : `<span style="color:var(--amber);font-weight:800;">🟠 Estado: ${data.watchdogState || 'Inactivo'}</span>`;
    consoleModalMeta.innerHTML = `${stateLabel} · Puerto: <strong>:${data.port || 3001}</strong> · Límite RAM: <strong>${data.maxRamMb || 256} MB</strong> · Reinicios Watchdog: <strong>${data.restartCount || 0}</strong>`;

    const lines = Array.isArray(data.lines) ? data.lines : [];
    const wasAtBottom = liveConsolePre.scrollHeight - liveConsolePre.scrollTop - liveConsolePre.clientHeight < 40;
    liveConsolePre.textContent = lines.length > 0
      ? lines.join('\n')
      : 'Sin registros en consola todavía. Las salidas y errores aparecerán aquí en vivo.';
    if (wasAtBottom) {
      liveConsolePre.scrollTop = liveConsolePre.scrollHeight;
    }
  } catch (_err) {}
}

if (btnConsoleRestartApp) {
  btnConsoleRestartApp.addEventListener('click', async () => {
    if (!activeConsoleAppName) return;
    btnConsoleRestartApp.disabled = true;
    btnConsoleRestartApp.textContent = '⏳ Reiniciando...';
    try {
      await fetch(`./api/v1/projects/${encodeURIComponent(activeConsoleAppName)}/restart`, {
        method: 'POST',
        headers: authHeaders()
      });
      await new Promise((r) => setTimeout(r, 600));
      await refreshLiveConsoleLogs();
      loadProjects();
    } finally {
      btnConsoleRestartApp.disabled = false;
      btnConsoleRestartApp.textContent = '🔄 Reiniciar App';
    }
  });
}

if (btnConsoleClearLogs) {
  btnConsoleClearLogs.addEventListener('click', async () => {
    if (!activeConsoleAppName) return;
    await fetch(`./api/v1/projects/${encodeURIComponent(activeConsoleAppName)}/logs/clear`, {
      method: 'POST',
      headers: authHeaders()
    });
    await refreshLiveConsoleLogs();
  });
}

if (btnConsoleCopyLogs) {
  btnConsoleCopyLogs.addEventListener('click', async () => {
    const text = liveConsolePre ? liveConsolePre.textContent || '' : '';
    const ok = await copyTextToClipboard(text);
    btnConsoleCopyLogs.textContent = ok ? '✅ ¡Copiado!' : '⚠️ Error al copiar';
    setTimeout(() => {
      btnConsoleCopyLogs.textContent = '📋 Copiar';
    }, 1800);
  });
}

if (btnCloseConsoleModal) {
  btnCloseConsoleModal.addEventListener('click', closeLiveConsoleModal);
}

// ============================================================================
// 6.4 MODO EXPERTO (LÍMITES DE RAM POR APP, WATCHDOG Y BUFFER INNODB)
// ============================================================================

const expertModeSwitch = document.getElementById('expertModeSwitch');
const expertModeBody = document.getElementById('expertModeBody');
const expertDefaultRamSelect = document.getElementById('expertDefaultRamSelect');
const expertInnoDbBufferSelect = document.getElementById('expertInnoDbBufferSelect');
const expertWatchdogSwitch = document.getElementById('expertWatchdogSwitch');
const expertWatchdogMaxRestarts = document.getElementById('expertWatchdogMaxRestarts');
const expertSharedStoreInfo = document.getElementById('expertSharedStoreInfo');
const btnSaveExpertConfig = document.getElementById('btnSaveExpertConfig');

if (expertModeSwitch && expertModeBody) {
  expertModeSwitch.addEventListener('change', () => {
    if (expertModeSwitch.checked) {
      expertModeBody.classList.remove('hidden');
      fetchExpertConfig();
    } else {
      expertModeBody.classList.add('hidden');
    }
  });
}

async function fetchExpertConfig() {
  if (!getToken()) return;
  try {
    const res = await fetch('./api/v1/runtime/expert-config', { headers: authHeaders() });
    if (!res.ok) return;
    const cfg = await res.json();
    if (expertDefaultRamSelect && cfg.defaultMaxRamMb) {
      expertDefaultRamSelect.value = String(cfg.defaultMaxRamMb);
    }
    if (expertInnoDbBufferSelect && cfg.innoDbBufferMb) {
      expertInnoDbBufferSelect.value = String(cfg.innoDbBufferMb);
    }
    if (expertWatchdogSwitch && cfg.watchdogEnabled !== undefined) {
      expertWatchdogSwitch.value = String(Boolean(cfg.watchdogEnabled));
    }
    if (expertWatchdogMaxRestarts && cfg.watchdogMaxRestarts) {
      expertWatchdogMaxRestarts.value = String(cfg.watchdogMaxRestarts);
    }
    if (expertSharedStoreInfo) {
      expertSharedStoreInfo.textContent = `📦 Paquetes en almacén compartido: ${cfg.sharedPackagesCount || 0}`;
    }
  } catch (_e) {}
}

if (btnSaveExpertConfig) {
  btnSaveExpertConfig.addEventListener('click', async () => {
    btnSaveExpertConfig.disabled = true;
    btnSaveExpertConfig.textContent = '⏳ Aplicando ajustes de experto...';
    try {
      const appRamOverrides = {};
      document.querySelectorAll('.expert-app-ram-input').forEach((sel) => {
        const app = sel.dataset.app;
        const val = Number(sel.value || 256);
        if (app && val >= 64) appRamOverrides[app] = val;
      });

      const payload = {
        defaultMaxRamMb: Number(expertDefaultRamSelect ? expertDefaultRamSelect.value : 256),
        innoDbBufferMb: Number(expertInnoDbBufferSelect ? expertInnoDbBufferSelect.value : 32),
        watchdogEnabled: expertWatchdogSwitch ? expertWatchdogSwitch.value === 'true' : true,
        watchdogMaxRestarts: Number(expertWatchdogMaxRestarts ? expertWatchdogMaxRestarts.value : 5),
        appRamOverrides
      };

      const res = await fetch('./api/v1/runtime/expert-config', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok) {
        loadProjects();
        launchConfetti();
        showResultModal({
          type: 'success',
          title: '¡Configuración Experta Aplicada!',
          desc: `Límite de RAM por defecto: ${payload.defaultMaxRamMb} MB · Buffer InnoDB: ${payload.innoDbBufferMb} MB · Watchdog: ${payload.watchdogEnabled ? 'Activo' : 'Inactivo'}.`,
          pill: '🧠 Tus procesos Node.js ya corren con los nuevos límites'
        });
      } else {
        alert(data.error || 'No se pudo guardar la configuración experta');
      }
    } catch (_err) {
      alert('Error de conexión guardando configuración experta');
    } finally {
      btnSaveExpertConfig.disabled = false;
      btnSaveExpertConfig.textContent = '💾 Guardar Ajustes de Experto y Aplicar';
    }
  });
}

// ============================================================================
// 7. MODO PC (PUENTE DBEAVER), BASES DE DATOS Y EXPORTAR/IMPORTAR .SQL
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
        <td><strong style="font-family:monospace;color:var(--accent-mint);">${db.name}</strong></td>
        <td>${db.isSystem ? 'Interna' : 'De tus Apps'}</td>
        <td>${db.tables && db.tables.length ? db.tables.join(', ') : '<em>Vacía</em>'}</td>
        <td>
          <button type="button" class="btn btn-secondary btn-small btn-row-export-sql" data-db="${db.name}" style="padding:0.3rem 0.65rem;min-height:34px;font-size:0.8rem;">
            📥 Backup .SQL
          </button>
        </td>
      `;
      dbsTableBody.appendChild(tr);
    }

    dbsTableBody.querySelectorAll('.btn-row-export-sql').forEach((btn) => {
      btn.addEventListener('click', () => exportDatabaseToSqlFile(btn.dataset.db));
    });
  } catch (_err) {}
}

const exportDbNameInput = document.getElementById('exportDbNameInput');
const btnExportDbSql = document.getElementById('btnExportDbSql');
const importDbNameInput = document.getElementById('importDbNameInput');
const sqlFileInput = document.getElementById('sqlFileInput');
const sqlFileDropzone = document.getElementById('sqlFileDropzone');
const sqlFileChosenText = document.getElementById('sqlFileChosenText');
const btnImportDbSql = document.getElementById('btnImportDbSql');

async function exportDatabaseToSqlFile(dbName) {
  const targetDb = (dbName || (exportDbNameInput ? exportDbNameInput.value : '') || 'cajitapaas').trim();
  if (!targetDb) return;
  if (btnExportDbSql) {
    btnExportDbSql.disabled = true;
    btnExportDbSql.textContent = `⏳ Generando backup de "${targetDb}"...`;
  }
  try {
    const res = await fetch(`./api/v1/db/export?database=${encodeURIComponent(targetDb)}`, {
      headers: authHeaders()
    });
    const data = await res.json();
    if (!res.ok || !data.sql) {
      alert(data.error || `No se pudo exportar la base "${targetDb}".`);
      return;
    }
    const blob = new Blob([data.sql], { type: 'application/sql;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = data.filename || `${targetDb}_backup.sql`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (_err) {
    alert('Error de conexión al exportar la base de datos.');
  } finally {
    if (btnExportDbSql) {
      btnExportDbSql.disabled = false;
      btnExportDbSql.textContent = '📥 Descargar Backup (.SQL)';
    }
  }
}

if (btnExportDbSql) {
  btnExportDbSql.addEventListener('click', () => {
    exportDatabaseToSqlFile(exportDbNameInput ? exportDbNameInput.value : 'cajitapaas');
  });
}

if (sqlFileDropzone && sqlFileInput) {
  sqlFileDropzone.addEventListener('click', () => sqlFileInput.click());
  sqlFileInput.addEventListener('change', () => {
    const f = sqlFileInput.files && sqlFileInput.files[0];
    if (f) {
      sqlFileChosenText.textContent = `📄 Archivo listo: ${f.name}`;
    } else {
      sqlFileChosenText.textContent = '📄 Tocá para elegir tu archivo .SQL';
    }
  });
}

if (btnImportDbSql) {
  btnImportDbSql.addEventListener('click', async () => {
    const file = sqlFileInput && sqlFileInput.files && sqlFileInput.files[0];
    if (!file) {
      if (sqlFileInput) sqlFileInput.click();
      return;
    }
    const targetDb = (importDbNameInput ? importDbNameInput.value.trim() : '') || 'cajitapaas';
    btnImportDbSql.disabled = true;
    btnImportDbSql.textContent = `⏳ Importando en "${targetDb}"...`;
    try {
      const sqlContent = await file.text();
      const res = await fetch('./api/v1/db/import', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ database: targetDb, sql: sqlContent })
      });
      const data = await res.json();
      if (res.ok) {
        sqlFileInput.value = '';
        if (sqlFileChosenText) sqlFileChosenText.textContent = '📄 Tocá para elegir tu archivo .SQL';
        loadDatabases();
        launchConfetti();
        showResultModal({
          type: 'success',
          title: `¡Archivo .SQL Importado en "${targetDb}"!`,
          desc: data.message || 'Todas las tablas y registros del archivo fueron cargados en MariaDB.',
          pill: '🗄️ Base de datos lista para tus aplicaciones'
        });
      } else {
        alert(data.error || 'Error al importar el archivo .SQL');
      }
    } catch (_err) {
      alert('Error leyendo o enviando el archivo .SQL');
    } finally {
      btnImportDbSql.disabled = false;
      btnImportDbSql.textContent = '📤 Subir e Importar .SQL';
    }
  });
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
const tunnelRealStatusBadge = document.getElementById('tunnelRealStatusBadge');
const tunnelEnabledSwitch = document.getElementById('tunnelEnabledSwitch');
const tunnelToggleTitle = document.getElementById('tunnelToggleTitle');
const tunnelToggleSubtitle = document.getElementById('tunnelToggleSubtitle');
const tunnelErrorBanner = document.getElementById('tunnelErrorBanner');
const tunnelTokenSavedBadge = document.getElementById('tunnelTokenSavedBadge');
const tunnelLogsPre = document.getElementById('tunnelLogsPre');
const btnRefreshTunnelStatus = document.getElementById('btnRefreshTunnelStatus');
const btnCopyOrigin8080 = document.getElementById('btnCopyOrigin8080');

const btnRegenerateQuickTunnel = document.getElementById('btnRegenerateQuickTunnel');
const btnSaveCustomTunnel = document.getElementById('btnSaveCustomTunnel');
const inputCustomDomain = document.getElementById('inputCustomDomain');
const inputTunnelToken = document.getElementById('inputTunnelToken');
const inputTunnelProtocol = document.getElementById('inputTunnelProtocol');

let tunnelHasSavedToken = false;

function renderTunnelInfo(tunnel) {
  if (!tunnel) return;
  const lanIp = tunnel.lanIp || window.location.hostname || '127.0.0.1';
  const lanPort = tunnel.lanPort || window.location.port || 4000;
  const lanAdminUrl = tunnel.lanAdminUrl || `http://${lanIp}:${lanPort}`;
  const lanAppsGatewayUrl = tunnel.lanUrl || `http://${lanIp}:8080`;

  const enabled = tunnel.enabled !== false;
  const status = String(tunnel.status || (enabled ? 'connecting' : 'disabled'));
  tunnelHasSavedToken = Boolean(tunnel.hasToken);

  if (tunnelEnabledSwitch) {
    tunnelEnabledSwitch.checked = enabled;
  }
  if (tunnelToggleTitle) {
    tunnelToggleTitle.textContent = enabled
      ? 'Túnel Público a Internet: ENCENDIDO'
      : 'Túnel Público a Internet: APAGADO (Solo Red Local)';
    tunnelToggleTitle.style.color = enabled ? 'var(--accent-mint)' : '#f5f9fc';
  }
  if (tunnelToggleSubtitle) {
    tunnelToggleSubtitle.textContent = enabled
      ? 'Apagalo si querés trabajar únicamente dentro de tu red Wi-Fi local sin exponer nada a Internet.'
      : 'Tus apps siguen funcionando en tu Wi-Fi local, pero no están expuestas a Internet.';
  }

  if (tunnelRealStatusBadge) {
    if (!enabled || status === 'disabled') {
      tunnelRealStatusBadge.textContent = '⚪ APAGADO (SOLO LAN)';
      tunnelRealStatusBadge.style.color = 'var(--text-muted)';
      tunnelRealStatusBadge.style.borderColor = 'var(--border)';
      tunnelRealStatusBadge.style.background = 'rgba(255,255,255,0.06)';
    } else if (status === 'online') {
      tunnelRealStatusBadge.textContent = '🟢 CONECTADO A CLOUDFLARE';
      tunnelRealStatusBadge.style.color = 'var(--accent-mint)';
      tunnelRealStatusBadge.style.borderColor = 'rgba(0, 229, 163, 0.45)';
      tunnelRealStatusBadge.style.background = 'rgba(0, 229, 163, 0.14)';
    } else if (status === 'error' || status === 'binary_missing') {
      tunnelRealStatusBadge.textContent = '🔴 ERROR DE CONEXIÓN';
      tunnelRealStatusBadge.style.color = '#ffb3c1';
      tunnelRealStatusBadge.style.borderColor = 'var(--danger)';
      tunnelRealStatusBadge.style.background = 'var(--danger-bg)';
    } else {
      tunnelRealStatusBadge.textContent = status === 'reconnecting' ? '🟠 RECONECTANDO...' : '🟡 CONECTANDO...';
      tunnelRealStatusBadge.style.color = 'var(--amber)';
      tunnelRealStatusBadge.style.borderColor = 'var(--amber)';
      tunnelRealStatusBadge.style.background = 'var(--amber-bg)';
    }
  }

  if (tunnelErrorBanner) {
    if (enabled && tunnel.lastError) {
      tunnelErrorBanner.textContent = `⚠️ Último error de Cloudflared: ${tunnel.lastError}`;
      tunnelErrorBanner.classList.remove('hidden');
    } else {
      tunnelErrorBanner.textContent = '';
      tunnelErrorBanner.classList.add('hidden');
    }
  }

  if (topLanUrl) {
    topLanUrl.textContent = lanAdminUrl;
    topLanUrl.href = lanAdminUrl;
  }
  if (topLanIpVal) topLanIpVal.textContent = lanIp;
  if (topLanPortVal) topLanPortVal.textContent = `${lanPort} (Panel) / 8080 y 3001 (Tus Apps)`;

  const rawPub = tunnel.publicUrl || '';
  const hasRealPublicUrl = enabled && (rawPub.startsWith('http://') || rawPub.startsWith('https://'));
  const displayPub = rawPub || `En tu Wi-Fi: ${lanAppsGatewayUrl}`;

  if (topTunnelUrl) {
    topTunnelUrl.textContent = displayPub;
    topTunnelUrl.href = hasRealPublicUrl ? rawPub : lanAppsGatewayUrl;
  }
  if (topTunnelBadge) {
    if (!enabled) {
      topTunnelBadge.textContent = 'SOLO RED LOCAL (APAGADO)';
    } else {
      topTunnelBadge.textContent =
        tunnel.mode === 'token' ? 'DOMINIO PROPIO FIJO' : 'MODO GRATIS (TryCloudflare)';
    }
  }
  if (tunnelPublicUrlVal) tunnelPublicUrlVal.textContent = displayPub;
  if (tunnelAdminUrlVal) tunnelAdminUrlVal.textContent = tunnel.adminUrl || lanAdminUrl;
  if (tunnelLanUrlVal) tunnelLanUrlVal.textContent = `${lanAppsGatewayUrl} (Apps) · ${lanAdminUrl} (Panel)`;

  if (inputCustomDomain && tunnel.customDomain && !inputCustomDomain.value && document.activeElement !== inputCustomDomain) {
    inputCustomDomain.value = tunnel.customDomain;
  }
  if (inputTunnelProtocol && tunnel.protocol) {
    inputTunnelProtocol.value = tunnel.protocol;
  }
  if (tunnelTokenSavedBadge) {
    if (tunnel.hasToken) {
      tunnelTokenSavedBadge.textContent = `🔒 Token guardado (${tunnel.tokenPreview || '••••'})`;
      tunnelTokenSavedBadge.classList.remove('hidden');
    } else {
      tunnelTokenSavedBadge.classList.add('hidden');
    }
  }
  if (tunnelLogsPre && typeof tunnel.logs === 'string') {
    tunnelLogsPre.textContent = tunnel.logs.trim()
      ? tunnel.logs
      : enabled
      ? 'Esperando salida de cloudflared...'
      : 'Túnel apagado (Modo Solo Red Local Wi-Fi).';
    tunnelLogsPre.scrollTop = tunnelLogsPre.scrollHeight;
  }
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

if (tunnelEnabledSwitch) {
  tunnelEnabledSwitch.addEventListener('change', async () => {
    const enabled = tunnelEnabledSwitch.checked;
    try {
      const res = await fetch('./api/v1/tunnel/toggle', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled })
      });
      const data = await res.json();
      if (res.ok && data.tunnel) {
        renderTunnelInfo(data.tunnel);
      } else {
        tunnelEnabledSwitch.checked = !enabled;
      }
    } catch (_err) {
      tunnelEnabledSwitch.checked = !enabled;
    }
  });
}

const btnCopyTunnelLogs = document.getElementById('btnCopyTunnelLogs');

if (btnRefreshTunnelStatus) {
  btnRefreshTunnelStatus.addEventListener('click', async () => {
    btnRefreshTunnelStatus.disabled = true;
    btnRefreshTunnelStatus.textContent = '⏳ Actualizando...';
    await fetchTunnelStatus();
    btnRefreshTunnelStatus.disabled = false;
    btnRefreshTunnelStatus.textContent = '🔄 Actualizar Estado / Logs';
  });
}

if (btnCopyTunnelLogs) {
  btnCopyTunnelLogs.addEventListener('click', async () => {
    const text = tunnelLogsPre ? tunnelLogsPre.textContent || '' : '';
    const ok = await copyTextToClipboard(text);
    btnCopyTunnelLogs.textContent = ok ? '✅ ¡Logs Copiados!' : '⚠️ Error al copiar';
    setTimeout(() => {
      btnCopyTunnelLogs.textContent = '📋 Copiar Logs';
    }, 1800);
  });
}

if (btnCopyOrigin8080) {
  btnCopyOrigin8080.addEventListener('click', async () => {
    const ok = await copyTextToClipboard('http://127.0.0.1:8080');
    btnCopyOrigin8080.textContent = ok ? '✅ ¡Copiado!' : '⚠️ Error al copiar';
    setTimeout(() => {
      btnCopyOrigin8080.textContent = '📋 Copiar';
    }, 1500);
  });
}

if (btnCopyLanUrl) {
  btnCopyLanUrl.addEventListener('click', async () => {
    const urlText = topLanUrl ? topLanUrl.textContent.trim() : '';
    const ok = await copyTextToClipboard(urlText);
    btnCopyLanUrl.textContent = ok ? '✅ ¡IP:Puerto Copiado!' : '⚠️ Error al copiar';
    setTimeout(() => {
      btnCopyLanUrl.textContent = '📋 Copiar IP:Puerto Local';
    }, 2000);
  });
}

if (btnCopyTunnelUrl) {
  btnCopyTunnelUrl.addEventListener('click', async () => {
    const urlText = topTunnelUrl ? topTunnelUrl.textContent.trim() : '';
    const ok = await copyTextToClipboard(urlText);
    btnCopyTunnelUrl.textContent = ok ? '✅ ¡Link Copiado!' : '⚠️ Error al copiar';
    setTimeout(() => {
      btnCopyTunnelUrl.textContent = '📋 Copiar Link Público';
    }, 2000);
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
    const protocol = inputTunnelProtocol ? inputTunnelProtocol.value : 'http2';
    try {
      const res = await fetch('./api/v1/tunnel/config', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: 'quick', protocol })
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
    const protocol = inputTunnelProtocol ? inputTunnelProtocol.value : 'http2';
    if (!token && !tunnelHasSavedToken) {
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
        body: JSON.stringify({ mode: 'token', customDomain, token, protocol })
      });
      const data = await res.json();
      if (res.ok && data.tunnel) {
        if (inputTunnelToken) inputTunnelToken.value = '';
        renderTunnelInfo(data.tunnel);
        if (data.tunnel.status === 'error' || data.tunnel.lastError) {
          showResultModal({
            type: 'warning',
            title: 'Hubo un problema al conectar el Token',
            desc: data.tunnel.lastError || 'Cloudflare rechazó el token ingresado.',
            pill: 'Revisá el visor de Diagnóstico abajo para más detalles.'
          });
        } else {
          launchConfetti();
          showResultModal({
            type: 'success',
            title: '¡Dominio Fijo Configurado!',
            desc: `Tu CajitaPaaS se está conectando en ${data.tunnel.publicUrl}. Estado: ${data.tunnel.status === 'online' ? 'CONECTADO' : 'Conectando...'}`,
            pill: '🔒 Verificá el estado verde en el panel de diagnóstico'
          });
        }
      } else {
        showResultModal({
          type: 'warning',
          title: 'No se pudo guardar el Túnel',
          desc: data.error || 'Error al configurar el túnel con Token.',
          pill: 'Verificá tu Token y volvé a intentarlo.'
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
