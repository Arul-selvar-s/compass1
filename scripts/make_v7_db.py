#!/usr/bin/env python3
"""make_v7_db.py <AppDatabase_Impl of the OLD app> <out.db>

Builds a genuine version-7 database: runs exactly the CREATE statements Room generated for the old
app (and records Room's identity hash), sets user_version 7 and adds one song."""
import re, sqlite3, sys

src = open(sys.argv[1], encoding="utf-8").read()
start = src.find("createAllTables(")
end = src.find("dropAllTables(", start)
if start < 0 or end < 0:
    sys.exit("createAllTables/dropAllTables not found in %s (file is %d chars)" % (sys.argv[1], len(src)))
body = src[start:end]

def unescape(s):
    return s.replace('\\"', '"').replace("\\\\", "\\")

wanted = ("CREATE TABLE", "CREATE INDEX", "CREATE UNIQUE INDEX", "CREATE VIRTUAL TABLE",
          "INSERT OR REPLACE INTO ROOM_MASTER_TABLE")
statements = [unescape(s) for s in re.findall(r'execSQL\("((?:[^"\\]|\\.)*)"\)', body)]
statements = [s for s in statements if s.strip().upper().startswith(wanted)]
print("found %d create statements" % len(statements))
if not statements:
    print(body[:1500])
    sys.exit("no CREATE statements recognised")

con = sqlite3.connect(sys.argv[2])
con.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
for s in statements:
    con.execute(s)
row = con.execute("SELECT sql FROM sqlite_master WHERE name='song_messages'").fetchone()
if row is None:
    sys.exit("song_messages table missing; tables: %s" % [r[0] for r in con.execute("SELECT name FROM sqlite_master")])
songs_ddl = row[0]
assert "categories" not in songs_ddl, "this is not the old schema: " + songs_ddl
con.execute("PRAGMA user_version = 7")
con.execute(
    "INSERT INTO song_messages (youtubeUrl, note, sender, sentAt, title) "
    "VALUES ('https://youtu.be/dQw4w9WgXcQ', 'keep me', 'JENMASANI', 1700000000000, 'Old song')"
)
con.commit()
con.execute("PRAGMA journal_mode = DELETE")
print("built v7 database; song_messages: " + songs_ddl)
print("identity hash row:", con.execute("SELECT * FROM room_master_table").fetchall())
con.close()
