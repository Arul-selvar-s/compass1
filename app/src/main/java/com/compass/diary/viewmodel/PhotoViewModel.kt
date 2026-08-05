package com.compass.diary.viewmodel

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.PhotoEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import com.compass.diary.util.PhotoCompressor
import com.compass.diary.util.PreferencesManager
import com.compass.diary.util.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PhotoViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private fun photosDir(): File = File(context.filesDir, "photos").apply { mkdirs() }

    fun photosForDate(dateKey: String) = repo.getPhotosForDate(dateKey)

    val allPhotos: StateFlow<List<PhotoEntity>> = repo.getAllPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingCaptureFile: File? = null

    fun createCaptureUri(): Uri {
        val temp = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        pendingCaptureFile = temp
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
    }

    fun discardCapture() {
        pendingCaptureFile?.delete()
        pendingCaptureFile = null
    }

    fun currentCaptureFile(): File? = pendingCaptureFile

    suspend fun getPhotoCountForDate(dateKey: String): Int = repo.getPhotosForDateOnce(dateKey).size

    fun savePendingCapture(dateKey: String) {
        val temp = pendingCaptureFile ?: return
        viewModelScope.launch {
            val outFile = File(photosDir(), "photo_${dateKey}_${System.currentTimeMillis()}.jpg")
            val ok = PhotoCompressor.compress(temp, outFile)
            temp.delete()
            pendingCaptureFile = null
            if (ok) {
                val id = repo.addPhoto(PhotoEntity(dateKey = dateKey, fileName = outFile.name))
                try {
                    driveSync.uploadPhotoFile(outFile).onSuccess { fileId ->
                        repo.setPhotoDriveFileId(id, fileId)
                    }
                } catch (e: Exception) { /* the immediate sync job below will retry this */ }
                SyncScheduler.requestImmediateSync(context)
            }
        }
    }

    fun photoFile(fileName: String): File = File(photosDir(), fileName)
}
