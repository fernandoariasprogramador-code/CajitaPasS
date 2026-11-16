#!/bin/sh
# ==============================================================================
# Script de empaquetado de MariaDB Alpine ARM64 oficial para CajitaPaaS (Android)
# Se ejecuta dentro de un contenedor alpine:3.20 (linux/arm64) durante el build.
# ==============================================================================
set -ex

apk add --no-cache mariadb mariadb-client mariadb-common zip

JNI_DIR="/workspace/android/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="/workspace/android/app/src/main/assets"
STAGE_DIR="/tmp/mariadb_stage"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/symlinks" "$STAGE_DIR/data" "$STAGE_DIR/share" "$STAGE_DIR/tmp"
mkdir -p "$JNI_DIR" "$ASSETS_DIR"

# 1. Copiar linker musl y binarios oficiales de MariaDB con nombres válidos para APK (lib*.so)
cp -L /lib/ld-musl-aarch64.so.1 "$JNI_DIR/libalpine_musl.so"
cp -L /usr/bin/mariadbd "$JNI_DIR/libmariadbd.so"
cp -L /usr/bin/mariadb "$JNI_DIR/libmariadb_cli.so"
chmod 755 "$JNI_DIR/libalpine_musl.so" "$JNI_DIR/libmariadbd.so" "$JNI_DIR/libmariadb_cli.so"

# Crear mapa de symlinks (SONAME original -> archivo lib*.so en nativeLibraryDir)
rm -f "$STAGE_DIR/libs_map.txt"
echo "ld-musl-aarch64.so.1=libalpine_musl.so" >> "$STAGE_DIR/libs_map.txt"
echo "libc.musl-aarch64.so.1=libalpine_musl.so" >> "$STAGE_DIR/libs_map.txt"
ln -sf "$JNI_DIR/libalpine_musl.so" "$STAGE_DIR/symlinks/ld-musl-aarch64.so.1"
ln -sf "$JNI_DIR/libalpine_musl.so" "$STAGE_DIR/symlinks/libc.musl-aarch64.so.1"

# 2. Recolectar todas las librerías dinámicas requeridas por mariadbd y mariadb
ldd /usr/bin/mariadbd /usr/bin/mariadb | awk '/=> \/.*\(0x/{print $1 "|" $3}' | sort -u > "$STAGE_DIR/raw_libs.txt"

while IFS="|" read -r soname libpath; do
  [ -z "$soname" ] && continue
  case "$soname" in
    ld-musl*|libc.musl*) continue ;;
  esac
  stem=$(echo "$soname" | sed -E 's/\.so(\.[0-9]+)*$//')
  clean_stem=$(echo "${stem#lib}" | tr -c 'a-zA-Z0-9_' '_')
  apk_so_name="libalpine_${clean_stem}.so"

  cp -L "$libpath" "$JNI_DIR/$apk_so_name"
  chmod 755 "$JNI_DIR/$apk_so_name"
  echo "$soname=$apk_so_name" >> "$STAGE_DIR/libs_map.txt"
  ln -sf "$JNI_DIR/$apk_so_name" "$STAGE_DIR/symlinks/$soname"
done < "$STAGE_DIR/raw_libs.txt"

cp -f "$STAGE_DIR/libs_map.txt" "$ASSETS_DIR/mariadb_libs_map.txt"

# 3. Copiar archivos de mensajes (errmsg.sys) y charsets de MariaDB
cp -r /usr/share/mariadb/charsets "$STAGE_DIR/share/"
cp -r /usr/share/mariadb/english "$STAGE_DIR/share/"
if [ -d /usr/share/mariadb/spanish ]; then
  cp -r /usr/share/mariadb/spanish "$STAGE_DIR/share/"
fi

# 4. Generar configuración my.cnf para inicialización limpia
cat <<EOF > "$STAGE_DIR/my.cnf"
[mysqld]
datadir=$STAGE_DIR/data
lc-messages-dir=$STAGE_DIR/share
character-sets-dir=$STAGE_DIR/share/charsets
tmpdir=$STAGE_DIR/tmp
socket=$STAGE_DIR/tmp/mysql.sock
pid-file=$STAGE_DIR/tmp/mariadbd.pid
port=13307
bind-address=127.0.0.1
skip-networking=0
skip-name-resolve
innodb-data-file-path=ibdata1:12M:autoextend
innodb-log-file-size=8M
innodb-buffer-pool-size=32M
innodb-use-native-aio=0
innodb-flush-method=fsync
performance-schema=OFF
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
default-storage-engine=InnoDB
EOF

# 5. Inicializar datadir oficial de MariaDB Alpine
mariadb-install-db \
  --defaults-file="$STAGE_DIR/my.cnf" \
  --user=root \
  --basedir=/usr \
  --datadir="$STAGE_DIR/data" \
  --auth-root-authentication-method=normal \
  --skip-test-db \
  --innodb-use-native-aio=0

# 6. Verificar que los binarios de jniLibs arrancan usando exclusivamente los symlinks
"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadbd.so" --version
"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadb_cli.so" --version

# 7. Iniciar mariadbd en modo prueba para configurar usuarios DBA (cajita_admin y root) y base cajitapaas
"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadbd.so" \
  --defaults-file="$STAGE_DIR/my.cnf" \
  --user=root &
MDB_PID=$!

SOCKET_READY=0
for i in $(seq 1 40); do
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

"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadb_cli.so" \
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
"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadb_cli.so" \
  --no-defaults \
  --protocol=TCP -h 127.0.0.1 -P 13307 -u cajita_admin -pcajitapaas2026 \
  -e "SHOW DATABASES; SELECT * FROM cajitapaas.info_sistema;"

# Apagar limpiamente mariadbd para volcar buffers InnoDB a disco
"$JNI_DIR/libalpine_musl.so" --library-path "$STAGE_DIR/symlinks" "$JNI_DIR/libmariadb_cli.so" \
  --no-defaults \
  --protocol=TCP -h 127.0.0.1 -P 13307 -u root -pcajitapaas2026 \
  -e "SHUTDOWN;"

wait "$MDB_PID" || true
rm -rf "$STAGE_DIR/tmp"/* "$STAGE_DIR/data/"*.pid "$STAGE_DIR/data/"*.err

# 8. Empaquetar datadir, share y libs_map.txt en mariadb_alpine_bundle.zip
cd "$STAGE_DIR"
zip -r -9 "$ASSETS_DIR/mariadb_alpine_bundle.zip" data share libs_map.txt

ls -lh "$JNI_DIR/"
ls -lh "$ASSETS_DIR/mariadb_alpine_bundle.zip"
