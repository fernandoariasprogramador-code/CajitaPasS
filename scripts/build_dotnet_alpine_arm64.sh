#!/bin/sh
# ==============================================================================
# Script de empaquetado de .NET 10.0 Runtime + ASP.NET Core 10.0 (linux-musl-arm64)
# para CajitaPaaS (CPS):
# 1. Descarga el runtime oficial ASP.NET Core 10.0 para Alpine ARM64 (linux-musl-arm64)
#    e instala las librerías nativas del sistema desde Alpine 3.20 (aarch64).
# 2. Copia `dotnet` como `libdotnet.so` y todas sus bibliotecas nativas (*.so*)
#    a `jniLibs/arm64-v8a` cumpliendo políticas SELinux W^X de Android.
# 3. Empaqueta los ensamblados gestionados (.dll, .json) y `dotnet_so_map.txt`
#    en `assets/dotnet_alpine_bundle.zip`.
# ==============================================================================
set -ex

apk add --no-cache curl bash

JNI_DIR="/workspace/android/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="/workspace/android/app/src/main/assets"
STAGE_DIR="/tmp/dotnet_stage"
ARM64_ROOT="/tmp/dotnet_arm64_root"
DOTNET_SRC="/tmp/dotnet10_src"

rm -rf "$STAGE_DIR" "$ARM64_ROOT" "$DOTNET_SRC"
mkdir -p "$STAGE_DIR" "$ARM64_ROOT/etc/apk" "$DOTNET_SRC" "$JNI_DIR" "$ASSETS_DIR"

cp /etc/apk/repositories "$ARM64_ROOT/etc/apk/"
cp -r /etc/apk/keys "$ARM64_ROOT/etc/apk/"

# Instalar librerías del sistema requeridas por .NET 10 en Alpine ARM64 (aarch64)
apk --arch aarch64 --root "$ARM64_ROOT" --initdb --no-scripts --allow-untrusted add \
  musl icu-libs icu-data-full krb5-libs lttng-ust zlib openssl libstdc++ libgcc

# Descargar ASP.NET Core 10.0 Runtime (incluye Microsoft.NETCore.App + Microsoft.AspNetCore.App) para linux-musl-arm64
curl -fSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh
chmod +x /tmp/dotnet-install.sh
bash /tmp/dotnet-install.sh --runtime aspnetcore --channel 10.0 --os linux-musl --arch arm64 --install-dir "$DOTNET_SRC"

if [ ! -e "$DOTNET_SRC/dotnet" ]; then
  echo "ERROR: No se encontró el binario dotnet en $DOTNET_SRC"
  exit 1
fi

ls -la "$DOTNET_SRC/shared/Microsoft.AspNetCore.App/"

# 1. Copiar binario ejecutable principal `dotnet` como `libdotnet.so`
cp -L "$DOTNET_SRC/dotnet" "$JNI_DIR/libdotnet.so"
chmod 755 "$JNI_DIR/libdotnet.so"

# 2. Agregar librerías compartidas del sistema (/lib y /usr/lib) que necesite .NET al mapa existente
touch "$ASSETS_DIR/mariadb_libs_map.txt"
cp -f "$ASSETS_DIR/mariadb_libs_map.txt" "$STAGE_DIR/libs_map_merged.txt"

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
    echo "$soname=$apk_so_name" >> "$STAGE_DIR/libs_map_merged.txt"
  done
done

sort -u "$STAGE_DIR/libs_map_merged.txt" -o "$ASSETS_DIR/mariadb_libs_map.txt"

# 3. Copiar todas las bibliotecas nativas *.so* dentro de $DOTNET_SRC a jniLibs y registrar su ruta relativa
rm -f "$STAGE_DIR/dotnet_so_map.txt"
touch "$STAGE_DIR/dotnet_so_map.txt"

mkdir -p "$STAGE_DIR/dotnet_files"
cd "$DOTNET_SRC"

find . -type f -o -type l | while IFS= read -r rel_raw; do
  rel="${rel_raw#./}"
  [ "$rel" = "dotnet" ] && continue

  base=$(basename "$rel")
  case "$base" in
    *.so)
      apk_so="$base"
      case "$apk_so" in
        lib*) ;;
        *) apk_so="libdotnet_${apk_so}" ;;
      esac
      if [ -f "$JNI_DIR/$apk_so" ]; then
        existing_hash=$(sha256sum "$JNI_DIR/$apk_so" | awk '{print $1}')
        new_hash=$(sha256sum "$DOTNET_SRC/$rel" | awk '{print $1}')
        if [ "$existing_hash" != "$new_hash" ]; then
          dir_tag=$(echo "$rel" | md5sum | cut -c1-6)
          clean_name=$(echo "${base#lib}" | tr -c 'a-zA-Z0-9_' '_')
          apk_so="libdotnet_${clean_name}_${dir_tag}.so"
        fi
      fi
      cp -L "$DOTNET_SRC/$rel" "$JNI_DIR/$apk_so"
      chmod 755 "$JNI_DIR/$apk_so"
      echo "$rel=$apk_so" >> "$STAGE_DIR/dotnet_so_map.txt"
      ;;
    *.so.*)
      clean_name=$(echo "${base#lib}" | tr -c 'a-zA-Z0-9_' '_')
      apk_so="libdotnet_${clean_name}.so"
      if [ -f "$JNI_DIR/$apk_so" ]; then
        existing_hash=$(sha256sum "$JNI_DIR/$apk_so" | awk '{print $1}')
        new_hash=$(sha256sum "$DOTNET_SRC/$rel" | awk '{print $1}')
        if [ "$existing_hash" != "$new_hash" ]; then
          dir_tag=$(echo "$rel" | md5sum | cut -c1-6)
          apk_so="libdotnet_${clean_name}_${dir_tag}.so"
        fi
      fi
      cp -L "$DOTNET_SRC/$rel" "$JNI_DIR/$apk_so"
      chmod 755 "$JNI_DIR/$apk_so"
      echo "$rel=$apk_so" >> "$STAGE_DIR/dotnet_so_map.txt"
      ;;
    *)
      dest_file="$STAGE_DIR/dotnet_files/$rel"
      mkdir -p "$(dirname "$dest_file")"
      cp -L "$DOTNET_SRC/$rel" "$dest_file"
      ;;
  esac
done

cp -f "$STAGE_DIR/dotnet_so_map.txt" "$STAGE_DIR/dotnet_files/dotnet_so_map.txt"

# 4. Empaquetar los ensamblados gestionados y mapas en assets/dotnet_alpine_bundle.zip
cd "$STAGE_DIR/dotnet_files"
rm -f "$ASSETS_DIR/dotnet_alpine_bundle.zip"
zip -r -9 -q "$ASSETS_DIR/dotnet_alpine_bundle.zip" .

ls -lh "$ASSETS_DIR/dotnet_alpine_bundle.zip"
wc -l "$STAGE_DIR/dotnet_so_map.txt"
