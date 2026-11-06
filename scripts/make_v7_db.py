#!/usr/bin/env python3
"""make_v7_db.py <AppDatabase_Impl.java of the OLD app> <out.db>

Builds a genuine version-7 database: runs exactly the CREATE statements Room generated for the old
app, records Room's identity hash, sets user_version 7 and adds one song."""
import re, sqlite3, sys

src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"createAllTables\([^)]*\)\s*\{(.*?)\n  \}", src, re.S)
if not m:
    sys.exit("could not find createAllTables in " + sys.argv[1])
statements = re.findall(r'execSQL\("((?:[^"\\]|\\.)*)"\)', m.group(1))
if not statements:
    sys.exit("no execSQL statements found")

def unescape(s):
    return s.replace('\\"', '"').replace("\\\\", "\\")

con = sqlite3.connect(sys.argv[2])
con.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
for s in statements:
    con.execute(unescape(s))
songs_ddl = con.execute("SELECT sql FROM sqlite_master WHERE name='song_messages'").fetchone()[0]
assert "categories" not in songs_ddl, "this is not the old schema: " + songs_ddl
con.execute("PRAGMA user_version = 7")
con.execute(
    "INSERT INTO song_messages (youtubeUrl, note, sender, sentAt, title) "
    "VALUES ('https://youtu.be/dQw4w9WgXcQ', 'keep me', 'JENMASANI', 1700000000000, 'Old song')"
)
con.commit()
con.execute("PRAGMA journal_mode = DELETE")
print("built v7 database with %d statements; song_messages: %s" % (len(statements), songs_ddl))
con.close()
