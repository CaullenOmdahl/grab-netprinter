#!/system/bin/sh
# GrabNetPrinter: publish the module configuration where the hooked app can read it.
# Runs early (post-fs-data) and again in service.sh; idempotent.
MODDIR=${0%/*}
SRC="$MODDIR/config.prop"
DST=/data/local/tmp/grab-netprinter.conf
LOG=/data/local/tmp/grab-netprinter.log

[ -f "$SRC" ] || exit 0

# Seed the legacy file once. The in-app settings (ConfigProvider) are canonical; do not
# overwrite a file the user/UI already maintains.
if [ ! -f "$DST" ] && [ -f "$SRC" ]; then
  cp -f "$SRC" "$DST" 2>/dev/null
fi
chmod 0644 "$DST" 2>/dev/null

# The hooked app appends to this log file, so it must be writable by the app uid.
touch "$LOG" 2>/dev/null
chmod 0666 "$LOG" 2>/dev/null

# If the settings app recorded a pending configuration (it cannot write here without root),
# this file is the newest user intent and is applied now.
PENDING=/data/data/dev.blacksheep.grabnetprinter/files/pending-gnp.conf
FORCE_SEED=0
if [ -f "$PENDING" ]; then
  cp -f "$PENDING" "$DST" 2>/dev/null
  chmod 0644 "$DST" 2>/dev/null
  rm -f "$PENDING" 2>/dev/null
  FORCE_SEED=1
fi

# Seed the per-target-app config file (the hooked app reads its own files/gnp.conf).
# A settings-app pending config was just written to DST, so overwrite; otherwise only
# seed a missing file so configuration applied earlier is never clobbered.
seed_target_files() {
  targets=$(grep '^targets=' "$DST" 2>/dev/null | head -1 | cut -d= -f2 | tr ',;' '  ')
  for pkg in $targets; do
    [ -n "$pkg" ] || continue
    [ "$pkg" = "*" ] && continue
    [ -d "/data/data/$pkg" ] || continue
    target="/data/data/$pkg/files/gnp.conf"
    [ -f "$target" ] && [ "$FORCE_SEED" != "1" ] && continue
    mkdir -p "/data/data/$pkg/files" 2>/dev/null
    cp -f "$DST" "$target" 2>/dev/null || continue
    chown "$(stat -c %u:%g "/data/data/$pkg" 2>/dev/null)" "$target" 2>/dev/null
    chmod 0600 "$target" 2>/dev/null
  done
}
seed_target_files

exit 0
