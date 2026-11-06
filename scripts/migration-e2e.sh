#!/usr/bin/env bash
# Puts a genuine v7 database into the NEW app's private storage, then runs an instrumented test
# inside the app that opens it with the production database code (migration + Room validation).
set -uo pipefail
PKG=com.compass.diary
OUT=migration-out; mkdir -p "$OUT"
FAIL=0
fail() { echo "MIGRATION FAIL: $*" | tee -a "$OUT/result.txt"; FAIL=1; }
ok()   { echo "MIGRATION ok:   $*" | tee -a "$OUT/result.txt"; }

adb wait-for-device
adb logcat -c
adb install -r /tmp/new.apk      || { fail "install app"; exit 1; }
adb install -r /tmp/new-test.apk || { fail "install test apk"; exit 1; }

adb shell am force-stop $PKG
adb shell "run-as $PKG mkdir -p databases"
adb shell "run-as $PKG rm -f databases/compass_diary.db databases/compass_diary.db-wal databases/compass_diary.db-shm"
adb shell "run-as $PKG sh -c 'cat > databases/compass_diary.db'" < /tmp/v7.db
adb shell "run-as $PKG ls -la databases" | tee "$OUT/seeded.txt"
ok "version-7 database placed in the app's storage"

adb shell am instrument -w -e class com.compass.diary.MigrationInstrumentedTest \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT/instrument.txt"
if grep -q "^OK (1 test)" "$OUT/instrument.txt"; then
  ok "instrumented test passed: 7 -> 8 migration + Room validation + song kept"
else
  fail "instrumented test failed (see instrument.txt)"
fi

adb exec-out "run-as $PKG cat databases/compass_diary.db" > "$OUT/after.db"
adb logcat -d > "$OUT/logcat.txt"
python3 scripts/db_check.py "$OUT/after.db" | tee "$OUT/after.json" >/dev/null
python3 - "$OUT/after.json" <<'PYX' | tee -a "$OUT/result.txt"
import json, sys
d = json.load(open(sys.argv[1]))
bad = []
if d["user_version"] != 8: bad.append("database version is %s, expected 8" % d["user_version"])
for c in ("categories", "categoriesAt", "categoryTries"):
    if c not in d["song_columns"]: bad.append("missing column " + c)
print("MIGRATION ok:   database file is now version 8 with the new columns" if not bad else "MIGRATION FAIL: " + "; ".join(bad))
sys.exit(1 if bad else 0)
PYX
[ "${PIPESTATUS[0]}" = "0" ] || FAIL=1

echo "MIGRATION exit=$FAIL" | tee -a "$OUT/result.txt"
exit $FAIL
