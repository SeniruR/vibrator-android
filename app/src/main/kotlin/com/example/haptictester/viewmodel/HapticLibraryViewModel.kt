package com.example.haptictester.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.haptictester.haptic.HapticLibraryRepository
import com.example.haptictester.haptic.SavedFileGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HapticLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HapticLibraryRepository(application)

    // Reactive list of saved groups observed by the UI
    private val _savedGroups = MutableStateFlow<List<SavedFileGroup>>(repository.getSavedGroups())
    val savedGroups = _savedGroups.asStateFlow()

    // Save the current working session as a named group with individual files
    fun saveCurrentSession(
        name: String,
        videoUri: Uri?,
        videoName: String?,
        slotAUri: Uri?,
        slotBUri: Uri?,
        slotCUri: Uri?,
        slotDUri: Uri?,
        slotEUri: Uri?,
        pipelineEventsUri: Uri?,
        pipelineEventsName: String?
    ) {
        viewModelScope.launch {
            val group = SavedFileGroup(
                name = name,
                videoUriString = videoUri?.toString(),
                videoName = videoName,
                slotAUriString = slotAUri?.toString(),
                slotBUriString = slotBUri?.toString(),
                slotCUriString = slotCUri?.toString(),
                slotDUriString = slotDUri?.toString(),
                slotEUriString = slotEUri?.toString(),
                pipelineEventsUriString = pipelineEventsUri?.toString(),
                pipelineEventsName = pipelineEventsName
            )
            repository.saveGroup(group)
            _savedGroups.value = repository.getSavedGroups() // Refresh UI list
        }
    }

    // Delete a saved group by its unique ID
    fun deleteGroup(id: String) {
        viewModelScope.launch {
            repository.deleteGroup(id)
            _savedGroups.value = repository.getSavedGroups() // Refresh UI list
        }
    }
}