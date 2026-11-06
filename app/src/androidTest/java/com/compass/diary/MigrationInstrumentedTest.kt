package com.compass.diary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.compass.diary.data.local.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs inside the app on an emulator. The CI script first drops a genuine version-7 database file
 * (built from the OLD app's own Room schema, with one song in it) into the app's private storage;
 * opening it here goes through the production database builder, so this exercises the real
 * 7 -> 8 migration AND Room's schema validation.
 */
@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {

    @Test
    fun versionSevenDatabaseUpgradesAndKeepsTheSong() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.create(context)
        try {
            val dao = db.songDao()
            val songs = dao.getAllSongsForBackup()
            val song = songs.singleOrNull { it.note == "keep me" }
            assertNotNull("the existing song must survive the upgrade (found ${songs.size} songs)", song)
            song!!
            assertEquals("Old song", song.title)
            assertEquals("JENMASANI", song.sender)
            assertEquals("https://youtu.be/dQw4w9WgXcQ", song.youtubeUrl)
            assertNull("an old song starts unsorted", song.categories)
            assertEquals(0L, song.categoriesAt)
            assertEquals(0, song.categoryTries)
            assertEquals(8, db.openHelper.readableDatabase.version)

            // the new columns work end to end
            dao.setCategories(song.id, "love,90s", 1234L)
            val saved = dao.getById(song.id)!!
            assertEquals("love,90s", saved.categories)
            assertEquals(1234L, saved.categoriesAt)
            dao.bumpCategoryTries(song.id)
            assertEquals(1, dao.getById(song.id)!!.categoryTries)
            dao.resetCategoriesIfUrlChanged(song.id, "https://youtu.be/other")
            assertNull("a different video must be sorted again", dao.getById(song.id)!!.categories)
        } finally {
            db.close()
        }
    }
}
