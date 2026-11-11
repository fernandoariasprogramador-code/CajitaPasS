#!/system/bin/sh
# ==============================================================================
# MODO 2: INICIALIZACIÓN CON ROOT (cgroups + Namespaces + Dockerd Estático ARM64)
# ==============================================================================
set -eu

RUNTIME_DIR="${1:-/data/data/com.cajitapaas/files/runtime}"
NATIVE_LIB_DIR="${2:-/data/app/com.cajitapaas/lib/arm64}"
DOCKER_DATA_DIR="/data/local/cajitapaas/docker"

mkdir -p "$DOCKER_DATA_DIR" "$RUNTIME_DIR/logs"

echo "[INIT-ROOT] 1/5 Montando y habilitando subsistemas cgroups para límites de CPU/RAM..."
if ! mountpoint -q /sys/fs/cgroup 2>/dev/null; then
    mount -t tmpfs -o mode=755 tmpfs /sys/fs/cgroup || true
fi

for subsys in cpu cpuacct memory blkio devices freezer pids cpuset; do
    mkdir -p "/sys/fs/cgroup/$subsys"
    if ! mountpoint -q "/sys/fs/cgroup/$subsys" 2>/dev/null; then
        mount -t cgroup -o "$subsys" cgroup "/sys/fs/cgroup/$subsys" 2>/dev/null || true
    fi
done

echo "[INIT-ROOT] 2/5 Ajustando parámetros de red del Kernel (IP Forwarding & Bridge)..."
sysctl -w net.ipv4.ip_forward=1 > /dev/null
iptables -P FORWARD ACCEPT || true

# Cargar secretos del Control Plane
ENV_FILE="$RUNTIME_DIR/control-plane/.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "[ERROR] No existe $ENV_FILE"
    exit 1
fi
set -a
. "$ENV_FILE"
set +a

echo "[INIT-ROOT] 3/5 Iniciando demonios containerd y dockerd estáticos ARM64..."
export PATH="$NATIVE_LIB_DIR:$RUNTIME_DIR/bin:$PATH"

if ! pgrep -x "dockerd" > /dev/null; then
    dockerd \
        --data-root "$DOCKER_DATA_DIR" \
        --exec-root "/run/docker" \
        --pidfile "/run/docker.pid" \
        --host "unix:///run/docker.sock" \
        --storage-driver overlay2 \
        --iptables=true > "$RUNTIME_DIR/logs/dockerd.log" 2>&1 &

    for i in $(seq 1 20); do
        if docker info > /dev/null 2>&1; then
            break
        fi
        sleep 1
    done
fi

echo "[INIT-ROOT] 4/5 Desplegando servicios de infraestructura con límites estrictos de hardware..."
docker network create cajita_internal_net 2>/dev/null || true

# MariaDB con límite duro de 512MB RAM y 1.0 vCPU
if ! docker ps --format '{{.Names}}' | grep -q '^cajita-mariadb$'; then
    docker run -d \
        --name cajita-mariadb \
        --restart unless-stopped \
        --network cajita_internal_net \
        --memory="512m" \
        --cpus="1.0" \
        -p 127.0.0.1:3306:3306 \
        -e MARIADB_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
        -e MARIADB_DATABASE="${DB_NAME:-cajitapaas}" \
        -e MARIADB_USER="$DB_USER" \
        -e MARIADB_PASSWORD="$DB_PASSWORD" \
        -v "$DOCKER_DATA_DIR/mariadb_data:/var/lib/mysql" \
        mariadb:11-ubi
fi

# Caddy Reverse Proxy + Cloudflared
"$NATIVE_LIB_DIR/libcaddy.so" start --config "$RUNTIME_DIR/config/Caddyfile" --adapter caddyfile

if [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
    "$NATIVE_LIB_DIR/libcloudflared.so" tunnel --no-autoupdate run --token "$CLOUDFLARE_TUNNEL_TOKEN" &
fi

echo "[INIT-ROOT] 5/5 Iniciando Control Plane en modo Root-Docker..."
export RUNTIME_MODE="DOCKER"
cd "$RUNTIME_DIR/control-plane"
exec "$NATIVE_LIB_DIR/libnode.so" server.js

