package com.compass.diary.util

import com.compass.diary.data.local.entity.NoteMessageEntity
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.VoiceMessageEntity
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ExportManager {

    const val PASSWORD = "0512"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun csvEscape(s: String) = "\"${s.replace("\"", "'").replace("\n", " ")}\""

    fun notesCsv(notes: List<NoteMessageEntity>): String {
        val sb = StringBuilder("Date,Time,Text\n")
        notes.sortedBy { it.sentAt }.forEach { n ->
            val d = Date(n.sentAt)
            sb.append("${dateFmt.format(d)},${timeFmt.format(d)},${csvEscape(n.text)}\n")
        }
        return sb.toString()
    }

    fun songsCsv(songs: List<SongMessageEntity>): String {
        val sb = StringBuilder("Date,Time,Sender,YouTubeLink,Note\n")
        songs.sortedBy { it.sentAt }.forEach { s ->
            val d = Date(s.sentAt)
            val sender = if (s.sender == "JENMASANI") "Jenmasani" else "Kutty Golu"
            sb.append("${dateFmt.format(d)},${timeFmt.format(d)},$sender,${csvEscape(s.youtubeUrl)},${csvEscape(s.note ?: "")}\n")
        }
        return sb.toString()
    }

    fun voiceManifestCsv(voice: List<VoiceMessageEntity>): String {
        val sb = StringBuilder("Date,Time,FileName,DurationSec,Source,Note\n")
        voice.sortedBy { it.sentAt }.forEach { v ->
            val d = Date(v.sentAt)
            sb.append("${dateFmt.format(d)},${timeFmt.format(d)},${v.audioFileName},${v.durationMs / 1000},${v.sourceType},${csvEscape(v.note ?: "")}\n")
        }
        return sb.toString()
    }

    fun createPasswordProtectedZip(sourceDir: File, outputZip: File) {
        if (outputZip.exists()) outputZip.delete()
        val zipParameters = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        val zipFile = ZipFile(outputZip, PASSWORD.toCharArray())
        sourceDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) zipFile.addFolder(f, zipParameters) else zipFile.addFile(f, zipParameters)
        }
    }
}
