#!/system/bin/sh
# GrabNetPrinter: late-start service. Re-publishes the config in case post-fs-data
# ran before /data/local/tmp was usable, and records a boot marker for debugging.
MODDIR=${0%/*}
SRC="$MODDIR/config.prop"
DST=/data/local/tmp/grab-netprinter.conf
LOG=/data/local/tmp/grab-netprinter.log

[ -f "$SRC" ] && [ ! -f "$DST" ] && cp -f "$SRC" "$DST" 2>/dev/null
chmod 0644 "$DST" 2>/dev/null
touch "$LOG" 2>/dev/null
chmod 0666 "$LOG" 2>/dev/null

# Apply a pending configuration written by the settings app (no root needed there).
PENDING=/data/data/dev.blacksheep.grabnetprinter/files/pending-gnp.conf
if [ -f "$PENDING" ]; then
  cp -f "$PENDING" "$DST" 2>/dev/null
  chmod 0644 "$DST" 2>/dev/null
  rm -f "$PENDING" 2>/dev/null
fi

# Seed per-target-app config files if the settings screen has not done so yet.
targets=$(grep '^targets=' "$DST" 2>/dev/null | head -1 | cut -d= -f2 | tr ',;' '  ')
for pkg in $targets; do
  [ -n "$pkg" ] || continue
  [ "$pkg" = "*" ] && continue
  [ -d "/data/data/$pkg" ] || continue
  target="/data/data/$pkg/files/gnp.conf"
  [ -f "$target" ] && continue
  mkdir -p "/data/data/$pkg/files" 2>/dev/null
  cp -f "$DST" "$target" 2>/dev/null || continue
  chown "$(stat -c %u:%g "/data/data/$pkg" 2>/dev/null)" "$target" 2>/dev/null
  chmod 0600 "$target" 2>/dev/null
done

# Keep a bounded size so the log cannot fill /data on a busy till.
if [ -f "$LOG" ]; then
  size=$(wc -c < "$LOG" 2>/dev/null)
  case "$size" in ''|*[!0-9]*) size=0 ;; esac
  if [ "$size" -gt 1048576 ]; then
    tail -c 262144 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
    chmod 0666 "$LOG" 2>/dev/null
  fi
fi
exit 0
