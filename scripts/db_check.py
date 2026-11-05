#!/usr/bin/env python3
"""db_check.py <db-file> [insert]  - print schema version + song rows; with `insert` add a test song first."""
import sqlite3, sys, json

path = sys.argv[1]
con = sqlite3.connect(path)
if len(sys.argv) > 2 and sys.argv[2] == "insert":
    con.execute(
        "INSERT INTO song_messages (youtubeUrl, note, sender, sentAt, title) "
        "VALUES ('https://youtu.be/dQw4w9WgXcQ', 'keep me', 'JENMASANI', 1700000000000, 'Old song')"
    )
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
version = con.execute("PRAGMA user_version").fetchone()[0]
columns = [r[1] for r in con.execute("PRAGMA table_info(song_messages)")]
rows = [dict(zip([d[0] for d in con.execute("SELECT * FROM song_messages").description], r))
        for r in con.execute("SELECT * FROM song_messages")]
print(json.dumps({"user_version": version, "song_columns": columns, "songs": rows}))
con.close()
