#!/system/bin/sh
# ==============================================================================
# MODO 1: INICIALIZACIÓN SIN ROOT (PRoot + Debian ARM64 + MariaDB + PM2 + Caddy)
# ==============================================================================
set -eu

RUNTIME_DIR="${1:-/data/data/com.cajitapaas/files/runtime}"
NATIVE_LIB_DIR="${2:-/data/app/com.cajitapaas/lib/arm64}"

ROOTFS_DIR="$RUNTIME_DIR/rootfs/debian-arm64"
PROOT_TMP_DIR="$RUNTIME_DIR/tmp"
PROOT_BIN="$NATIVE_LIB_DIR/libproot.so"

mkdir -p "$ROOTFS_DIR" "$PROOT_TMP_DIR" "$RUNTIME_DIR/logs"
export PROOT_TMP_DIR="$PROOT_TMP_DIR"
export PROOT_NO_SECCOMP=1

# 1. Desempaquetar RootFS base si es el primer arranque
if [ ! -f "$ROOTFS_DIR/etc/debian_version" ]; then
    echo "[INIT-NONROOT] Extrayendo Debian ARM64 base rootfs..."
    "$NATIVE_LIB_DIR/libbusybox.so" tar -xJf "$RUNTIME_DIR/assets/debian-arm64-rootfs.tar.xz" -C "$ROOTFS_DIR"
fi

# 2. Configurar DNS y entorno dentro de la jaula PRoot
cat << 'EOF' > "$ROOTFS_DIR/etc/resolv.conf"
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

# 3. Script de arranque interno que se ejecutará dentro del namespace emulado (UID 0 virtual)
cat << 'INNER_EOF' > "$ROOTFS_DIR/root/start_paas_stack.sh"
#!/bin/bash
set -euo pipefail

export HOME=/root
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export PM2_HOME="/root/.pm2"

# Cargar variables de entorno seguras (.env)
if [ ! -f /opt/cajitapaas/control-plane/.env ]; then
    echo "[ERROR] Archivo /opt/cajitapaas/control-plane/.env no encontrado."
    exit 1
fi
set -a
source /opt/cajitapaas/control-plane/.env
set +a

echo "[PROOT-INNER] 1/5 Inicializando MariaDB..."
mkdir -p /run/mysqld
chown -R mysql:mysql /run/mysqld /var/lib/mysql
if [ ! -d "/var/lib/mysql/mysql" ]; then
    mysql_install_db --user=mysql --datadir=/var/lib/mysql > /dev/null
fi
mysqld_safe --no-watch --skip-networking=0 --bind-address=127.0.0.1 --port="${DB_PORT:-3306}" &

# Esperar socket de MariaDB y asegurar credenciales desde .env
for i in $(seq 1 15); do
    if mysqladmin ping --silent 2>/dev/null; then
        break
    fi
    sleep 1
done

mysql -u root <<SQL_EOF || true
ALTER USER 'root'@'localhost' IDENTIFIED BY '${DB_ROOT_PASSWORD}';
CREATE DATABASE IF NOT EXISTS \`${DB_NAME:-cajitapaas}\`;
CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME:-cajitapaas}\`.* TO '${DB_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL_EOF

echo "[PROOT-INNER] 2/5 Inicializando Caddy Reverse Proxy (Admin API en 127.0.0.1:2019)..."
mkdir -p /etc/caddy /var/log/caddy
if [ ! -f /etc/caddy/Caddyfile ]; then
cat << 'CADDY_EOF' > /etc/caddy/Caddyfile
{
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
        reverse_proxy 127.0.0.1:4000
    }

    handle {
        reverse_proxy 127.0.0.1:3001
    }
}
CADDY_EOF
fi
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile

echo "[PROOT-INNER] 3/5 Iniciando PM2 Daemon y Control Plane API..."
cd /opt/cajitapaas/control-plane
pm2 ping > /dev/null
pm2 start server.js --name "cajita-control-plane" --update-env
pm2 save

echo "[PROOT-INNER] 4/5 Levantando Túnel Seguro Cloudflared (Bypass CGNAT)..."
if [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
    cloudflared tunnel --no-autoupdate run --token "$CLOUDFLARE_TUNNEL_TOKEN" &
else
    echo "[WARN] CLOUDFLARE_TUNNEL_TOKEN vacío, iniciando Quick Tunnel efímero hacia Caddy (:8080)..."
    cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8080 > /var/log/cloudflared.log 2>&1 &
fi

echo "[PROOT-INNER] 5/5 Clúster PaaS operativo. Monitoreando procesos..."
exec pm2 logs --raw
INNER_EOF

chmod 700 "$ROOTFS_DIR/root/start_paas_stack.sh"

# 4. Lanzar PRoot con emulación de root (-0), link2symlink (para sistemas de archivos Android)
exec "$PROOT_BIN" \
    --kill-on-exit \
    --link2symlink \
    -0 \
    -r "$ROOTFS_DIR" \
    -b /dev \
    -b /proc \
    -b /sys \
    -b "$RUNTIME_DIR:/mnt/host_runtime" \
    -w /root \
    /bin/bash /root/start_paas_stack.sh

