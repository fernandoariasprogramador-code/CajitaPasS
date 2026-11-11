#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# INSTALADOR DIRECTO EN CELULAR ANDROID (ARM64) PARA PROBAR CAJITAPAAS HOY MISMO
# Ejecución en el celular (Termux):
#   bash install_on_phone_termux.sh
# ==============================================================================
set -euo pipefail

echo "=========================================================="
echo " 📦 CPS (CajitaPaaS) - Instalador para Android ARM64"
echo "=========================================================="

# 1. Evitar que Android duerma el procesador (WakeLock)
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock
    echo "[OK] WakeLock de CPU activado."
fi

# 2. Instalar dependencias nativas ARM64 (Node.js, MariaDB, Caddy, Cloudflared, Git)
echo "[1/5] Instalando motor base ARM64 (Node.js, MariaDB, Caddy, Cloudflared)..."
pkg update -y
pkg install -y nodejs-lts mariadb caddy cloudflared git openssl-tool procps

# 3. Instalar PM2 globalmente
if ! command -v pm2 >/dev/null 2>&1; then
    echo "[2/5] Instalando orquestador PM2..."
    npm install -g pm2
fi

# 4. Inicializar MariaDB si es la primera vez
echo "[3/5] Configurando MariaDB en 127.0.0.1:3306..."
mkdir -p "$PREFIX/var/lib/mysql" "$PREFIX/var/run/mysqld"
if [ ! -d "$PREFIX/var/lib/mysql/mysql" ]; then
    mysql_install_db
fi

if ! pgrep -x "mariadbd" >/dev/null 2>&1 && ! pgrep -x "mysqld" >/dev/null 2>&1; then
    mysqld_safe --bind-address=127.0.0.1 --port=3306 &
    sleep 4
fi

# 5. Preparar carpeta del Control Plane
CPS_DIR="$HOME/cajitapaas/control-plane"
mkdir -p "$CPS_DIR"
cp -r ./control-plane/* "$CPS_DIR/" 2>/dev/null || true

cd "$CPS_DIR"
if [ ! -f .env ]; then
    cp .env.example .env
fi

mysql -u "$(whoami)" <<SQL_EOF || true
CREATE DATABASE IF NOT EXISTS cajitapaas;
CREATE USER IF NOT EXISTS 'cajita_admin'@'127.0.0.1' IDENTIFIED BY 'super_secret_mariadb_user_pwd_2026';
GRANT ALL PRIVILEGES ON *.* TO 'cajita_admin'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL_EOF

echo "[4/5] Instalando paquetes del Control Plane..."
npm install --omit=dev

# 6. Iniciar Control Plane y Túnel Público Cloudflare
echo "[5/5] Levantando Control Plane y Túnel Público HTTPS..."
pm2 delete cajita-control-plane >/dev/null 2>&1 || true
pm2 start preview-server.js --name "cajita-control-plane"

pkill -f "cloudflared tunnel" >/dev/null 2>&1 || true
nohup cloudflared tunnel --no-autoupdate --url http://127.0.0.1:4000 > "$HOME/cloudflared.log" 2>&1 &

echo "Esperando a que Cloudflare genere tu URL pública HTTPS..."
sleep 6
PUBLIC_URL=$(grep -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "$HOME/cloudflared.log" | tail -n 1 || true)

echo "=========================================================="
echo " ✅ ¡CAJITAPAAS (CPS) CORRIENDO EN TU CELULAR!"
echo " 👉 En el navegador de tu celu: http://127.0.0.1:4000"
if [ -n "$PUBLIC_URL" ]; then
    echo " 🌐 URL Pública en Internet (4G/5G/Cualquier PC):"
    echo "    $PUBLIC_URL"
fi
echo "=========================================================="

