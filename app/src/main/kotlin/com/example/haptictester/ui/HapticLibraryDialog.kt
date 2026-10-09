package com.example.haptictester.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.haptictester.haptic.SavedFileGroup
import com.example.haptictester.viewmodel.HapticLibraryViewModel

@Composable
fun HapticLibraryDialogs(
    libraryViewModel: HapticLibraryViewModel,
    showLibraryDialog: Boolean,
    onDismissLibrary: () -> Unit,
    showSaveDialog: Boolean,
    onDismissSave: () -> Unit,
    loadedVideoUri: Uri?,
    selectedVideoName: String?,
    slotAUri: Uri?,
    slotBUri: Uri?,
    slotCUri: Uri?,
    slotDUri: Uri?,
    slotEUri: Uri?,
    pipelineEventsUri: Uri?,
    pipelineEventsName: String?,
    onLoadGroup: (SavedFileGroup) -> Unit
) {
    val savedGroups by libraryViewModel.savedGroups.collectAsState()
    var saveGroupNameInput by remember { mutableStateOf("") }

    if (showLibraryDialog) {
        AlertDialog(
            onDismissRequest = onDismissLibrary,
            title = { Text("Saved Video Libraries") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (savedGroups.isEmpty()) {
                        Text("No saved libraries yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        savedGroups.forEach { group ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(group.name, fontWeight = FontWeight.Bold)
                                    Text("Video: ${group.videoName ?: "None"}", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                        Button(
                                            onClick = { onLoadGroup(group) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Load") }

                                        OutlinedButton(
                                            onClick = { libraryViewModel.deleteGroup(group.id) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissLibrary) { Text("Close") }
            }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = onDismissSave,
            title = { Text("Save Library Group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a name for this session bundle:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = saveGroupNameInput,
                        onValueChange = { saveGroupNameInput = it },
                        placeholder = { Text("e.g., Action Scene Test") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (saveGroupNameInput.isNotBlank()) {
                            libraryViewModel.saveCurrentSession(
                                name = saveGroupNameInput,
                                videoUri = loadedVideoUri,
                                videoName = selectedVideoName,
                                slotAUri = slotAUri,
                                slotBUri = slotBUri,
                                slotCUri = slotCUri,
                                slotDUri = slotDUri,
                                slotEUri = slotEUri,
                                pipelineEventsUri = pipelineEventsUri,
                                pipelineEventsName = pipelineEventsName
                            )
                            saveGroupNameInput = ""
                            onDismissSave()
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismissSave) { Text("Cancel") }
            }
        )
    }
}