#!/bin/sh
# ==============================================================================
# Script de empaquetado de MariaDB Alpine ARM64 + Node.js ARM64 para CajitaPaaS
# 1. Descarga binarios y librerías nativas ARM64 (aarch64) desde Alpine 3.20
#    (mariadbd, mariadb client y nodejs para ejecutar APIs backend).
# 2. Inicializa el datadir oficial de MariaDB 10.11 (InnoDB + catálogos + DBA).
# 3. Pre-instala módulos comunes de Node.js (express, cors, mysql2, dotenv, etc.).
# ==============================================================================
set -ex

apk add --no-cache mariadb mariadb-client mariadb-common alpine-keys zip npm

JNI_DIR="/workspace/android/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="/workspace/android/app/src/main/assets"
STAGE_DIR="/tmp/mariadb_stage"
ARM64_ROOT="/tmp/arm64_root"

rm -rf "$STAGE_DIR" "$ARM64_ROOT"
mkdir -p "$STAGE_DIR/data" "$STAGE_DIR/share" "$STAGE_DIR/tmp" "$STAGE_DIR/node_global"
mkdir -p "$JNI_DIR" "$ASSETS_DIR"

# 1. Instalar paquetes oficiales ARM64 (aarch64) de Alpine 3.20 en un root limpio
mkdir -p "$ARM64_ROOT/etc/apk"
cp /etc/apk/repositories "$ARM64_ROOT/etc/apk/"
cp -r /etc/apk/keys "$ARM64_ROOT/etc/apk/"
apk --arch aarch64 --root "$ARM64_ROOT" --initdb --no-scripts --allow-untrusted add musl mariadb mariadb-client nodejs

# 2. Copiar linker musl y ejecutables ARM64 (MariaDB + Node.js) con nombres válidos para Android APK (lib*.so)
cp -L "$ARM64_ROOT/lib/ld-musl-aarch64.so.1" "$JNI_DIR/libalpine_musl.so"
cp -L "$ARM64_ROOT/usr/bin/mariadbd" "$JNI_DIR/libmariadbd.so"
cp -L "$ARM64_ROOT/usr/bin/mariadb" "$JNI_DIR/libmariadb_cli.so"
cp -L "$ARM64_ROOT/usr/bin/node" "$JNI_DIR/libnode.so"
chmod 755 "$JNI_DIR/libalpine_musl.so" "$JNI_DIR/libmariadbd.so" "$JNI_DIR/libmariadb_cli.so" "$JNI_DIR/libnode.so"

# Crear mapa de enlaces simbólicos (SONAME original -> archivo lib*.so en nativeLibraryDir)
rm -f "$STAGE_DIR/libs_map.txt"
echo "ld-musl-aarch64.so.1=libalpine_musl.so" >> "$STAGE_DIR/libs_map.txt"
echo "libc.musl-aarch64.so.1=libalpine_musl.so" >> "$STAGE_DIR/libs_map.txt"

# 3. Empaquetar todas las librerías compartidas ARM64 de /lib y /usr/lib
for libdir in "$ARM64_ROOT/lib" "$ARM64_ROOT/usr/lib"; do
  [ -d "$libdir" ] || continue
  for entry in "$libdir"/lib*.so*; do
    [ -e "$entry" ] || continue
    soname=$(basename "$entry")
    case "$soname" in
      ld-musl*|libc.musl*) continue ;;
    esac
    stem=$(echo "$soname" | sed -E 's/\.so(\.[0-9]+)*$//')
    clean_stem=$(echo "${stem#lib}" | tr -c 'a-zA-Z0-9_' '_')
    apk_so_name="libalpine_${clean_stem}.so"

    if [ ! -f "$JNI_DIR/$apk_so_name" ]; then
      cp -L "$entry" "$JNI_DIR/$apk_so_name"
      chmod 755 "$JNI_DIR/$apk_so_name"
    fi
    echo "$soname=$apk_so_name" >> "$STAGE_DIR/libs_map.txt"
  done
done

sort -u "$STAGE_DIR/libs_map.txt" -o "$STAGE_DIR/libs_map.txt"
cp -f "$STAGE_DIR/libs_map.txt" "$ASSETS_DIR/mariadb_libs_map.txt"

# 4. Pre-instalar paquetes esenciales de Node.js para que APIs subidas sin node_modules funcionen directo
npm install --prefix "$STAGE_DIR/node_global" --no-audit --no-fund --silent \
  express cors mysql2 mysql dotenv body-parser cookie-parser morgan helmet jsonwebtoken bcryptjs uuid axios multer express-validator

# Crear alias de 'bcrypt' apuntando a 'bcryptjs' (puro JS) para evitar fallos de binarios Windows .node en ARM64
MODS_DIR="$STAGE_DIR/node_global/node_modules"
[ -d "$STAGE_DIR/node_global/lib/node_modules" ] && MODS_DIR="$STAGE_DIR/node_global/lib/node_modules"
mkdir -p "$MODS_DIR/bcrypt"
cat <<'BCRYPT_EOF' > "$MODS_DIR/bcrypt/package.json"
{"name":"bcrypt","version":"5.1.1","main":"index.js"}
BCRYPT_EOF
cat <<'BCRYPT_EOF' > "$MODS_DIR/bcrypt/index.js"
module.exports = require('bcryptjs');
BCRYPT_EOF

# 5. Copiar archivos de mensajes (errmsg.sys) y charsets de MariaDB
cp -r /usr/share/mariadb/charsets "$STAGE_DIR/share/"
cp -r /usr/share/mariadb/english "$STAGE_DIR/share/"
if [ -d /usr/share/mariadb/spanish ]; then
  cp -r /usr/share/mariadb/spanish "$STAGE_DIR/share/"
fi

# 6. Inicializar datadir oficial de MariaDB Alpine
mariadb-install-db \
  --no-defaults \
  --user=root \
  --basedir=/usr \
  --datadir="$STAGE_DIR/data" \
  --auth-root-authentication-method=normal \
  --skip-test-db \
  --innodb-data-file-path=ibdata1:12M:autoextend \
  --innodb-log-file-size=8M \
  --innodb-use-native-aio=0

# 7. Iniciar mariadbd para configurar usuarios DBA (cajita_admin y root) y base cajitapaas
/usr/bin/mariadbd \
  --no-defaults \
  --user=root \
  --datadir="$STAGE_DIR/data" \
  --tmpdir="$STAGE_DIR/tmp" \
  --socket="$STAGE_DIR/tmp/mysql.sock" \
  --pid-file="$STAGE_DIR/tmp/mariadbd.pid" \
  --port=13307 \
  --bind-address=127.0.0.1 \
  --skip-networking=0 \
  --skip-name-resolve \
  --innodb-data-file-path=ibdata1:12M:autoextend \
  --innodb-log-file-size=8M \
  --innodb-buffer-pool-size=32M \
  --innodb-use-native-aio=0 \
  --innodb-flush-method=fsync \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_general_ci \
  --default-storage-engine=InnoDB &
MDB_PID=$!

SOCKET_READY=0
for i in $(seq 1 30); do
  if [ -S "$STAGE_DIR/tmp/mysql.sock" ]; then
    SOCKET_READY=1
    break
  fi
  sleep 1
done

if [ "$SOCKET_READY" -ne 1 ]; then
  echo "ERROR: mariadbd no abrió el socket en el tiempo esperado"
  exit 1
fi

/usr/bin/mariadb \
  --no-defaults \
  --socket="$STAGE_DIR/tmp/mysql.sock" \
  -u root <<'SQL_EOF'
DROP USER IF EXISTS ''@'localhost';
CREATE DATABASE IF NOT EXISTS `cajitapaas` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `cajitapaas`.`deployments_audit` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `app_name` VARCHAR(128) NOT NULL,
  `active_color` VARCHAR(32) NOT NULL,
  `active_port` INT NOT NULL,
  `deployed_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `cajitapaas`.`info_sistema` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `clave` VARCHAR(128) NOT NULL,
  `valor` TEXT NOT NULL,
  `creado_en` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO `cajitapaas`.`info_sistema` (`clave`, `valor`) VALUES ('motor', 'MariaDB Alpine ARM64 Oficial (InnoDB)');

ALTER USER 'root'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
ALTER USER 'root'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
ALTER USER 'root'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;

CREATE USER IF NOT EXISTS 'cajita_admin'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
ALTER USER 'cajita_admin'@'%' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
GRANT ALL PRIVILEGES ON *.* TO 'cajita_admin'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'cajita_admin'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
ALTER USER 'cajita_admin'@'127.0.0.1' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
GRANT ALL PRIVILEGES ON *.* TO 'cajita_admin'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'cajita_admin'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
ALTER USER 'cajita_admin'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('cajitapaas2026');
GRANT ALL PRIVILEGES ON *.* TO 'cajita_admin'@'localhost' WITH GRANT OPTION;

FLUSH PRIVILEGES;
SQL_EOF

# Verificar consulta por TCP usando usuario cajita_admin
/usr/bin/mariadb \
  --no-defaults \
  --protocol=TCP -h 127.0.0.1 -P 13307 -u cajita_admin -pcajitapaas2026 \
  -e "SHOW DATABASES; SELECT * FROM cajitapaas.info_sistema;"

# Apagar limpiamente mariadbd para volcar buffers InnoDB a disco
/usr/bin/mariadb \
  --no-defaults \
  --protocol=TCP -h 127.0.0.1 -P 13307 -u root -pcajitapaas2026 \
  -e "SHUTDOWN;"

wait "$MDB_PID" || true
rm -rf "$STAGE_DIR/tmp"/* "$STAGE_DIR/data/"*.pid "$STAGE_DIR/data/"*.err

# 8. Empaquetar datadir, share, node_global y libs_map.txt en mariadb_alpine_bundle.zip
cd "$STAGE_DIR"
zip -r -9 "$ASSETS_DIR/mariadb_alpine_bundle.zip" data share node_global libs_map.txt

ls -lh "$JNI_DIR/"
ls -lh "$ASSETS_DIR/mariadb_alpine_bundle.zip"
