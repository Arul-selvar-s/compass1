#!/usr/bin/env bash
# Installs the OLD app (database v7), puts a song in its database, upgrades to the NEW app
# in place, and checks that Room migrated without crashing and the song survived.
set -uo pipefail
PKG=com.compass.diary
OUT=migration-out; mkdir -p "$OUT"
FAIL=0
fail() { echo "MIGRATION FAIL: $*" | tee -a "$OUT/result.txt"; FAIL=1; }
ok()   { echo "MIGRATION ok:   $*" | tee -a "$OUT/result.txt"; }

pull_db() { # pull_db <dir>  - copies compass_diary.db (+wal/shm) out of the app's private storage
  rm -rf "$1"; mkdir -p "$1"
  for f in $(adb shell "run-as $PKG ls databases" | tr -d '\r'); do
    case "$f" in compass_diary.db|compass_diary.db-wal|compass_diary.db-shm)
      adb exec-out "run-as $PKG cat databases/$f" > "$1/$f" ;;
    esac
  done
}

adb wait-for-device
adb logcat -c

# ---- 1) old app creates its v7 database ----
adb install -r /tmp/old.apk || { fail "install old apk"; exit 1; }
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 15
pull_db "$OUT/before"
if [ ! -s "$OUT/before/compass_diary.db" ]; then fail "old app did not create its database"; exit 1; fi
V=$(python3 -c "import sqlite3,sys;print(sqlite3.connect('$OUT/before/compass_diary.db').execute('PRAGMA user_version').fetchone()[0])")
[ "$V" = "7" ] && ok "old app database is version 7" || fail "old database version is $V, expected 7"

# ---- 2) add a song the way the old app would have stored it ----
adb shell am force-stop $PKG
sleep 2
pull_db "$OUT/before"          # consolidated after the app stopped
python3 scripts/db_check.py "$OUT/before/compass_diary.db" insert | tee "$OUT/before.json"
rm -f "$OUT/before/compass_diary.db-wal" "$OUT/before/compass_diary.db-shm"
adb shell "run-as $PKG rm -f databases/compass_diary.db-wal databases/compass_diary.db-shm"
adb shell "run-as $PKG sh -c 'cat > databases/compass_diary.db'" < "$OUT/before/compass_diary.db"

# ---- 3) upgrade in place and launch ----
adb install -r /tmp/new.apk || { fail "upgrade install"; exit 1; }
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 15
adb shell am force-stop $PKG
sleep 2
pull_db "$OUT/after"
python3 scripts/db_check.py "$OUT/after/compass_diary.db" | tee "$OUT/after.json"
adb logcat -d > "$OUT/logcat.txt"

python3 - "$OUT/after.json" <<'PYX' | tee -a "$OUT/result.txt"
import json, sys
d = json.load(open(sys.argv[1]))
bad = []
if d["user_version"] != 8: bad.append("database version is %s, expected 8" % d["user_version"])
for col in ("categories", "categoriesAt", "categoryTries"):
    if col not in d["song_columns"]: bad.append("missing column " + col)
songs = [s for s in d["songs"] if s.get("note") == "keep me"]
if len(songs) != 1: bad.append("the existing song did not survive the upgrade (%d rows)" % len(d["songs"]))
else:
    s = songs[0]
    if s["title"] != "Old song" or s["sender"] != "JENMASANI": bad.append("song data changed: %s" % s)
    if s["categories"] is not None: bad.append("categories should start empty")
    if s["categoriesAt"] != 0 or s["categoryTries"] != 0: bad.append("new columns should default to 0")
print("MIGRATION ok:   upgrade kept the song and added the tag columns" if not bad else "MIGRATION FAIL: " + "; ".join(bad))
sys.exit(1 if bad else 0)
PYX
[ "${PIPESTATUS[0]}" = "0" ] || FAIL=1

if grep -n "FATAL EXCEPTION\|Migration didn't properly handle\|IllegalStateException" "$OUT/logcat.txt" | head -5; then
  fail "crash or Room schema error in logcat"
else
  ok "no crash / schema error after upgrade"
fi
echo "MIGRATION exit=$FAIL" | tee -a "$OUT/result.txt"
exit $FAIL
