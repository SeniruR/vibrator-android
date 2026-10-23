package com.example.haptictester.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    defaultSaveBundleName: String,
    syncRootFolderName: String?,
    isConnectedToDesktop: Boolean,
    syncedBundles: List<String>,
    desktopBundles: List<String>,
    activeBundleId: String?,
    transferStatus: String?,
    onSelectSyncFolder: () -> Unit,
    onRequestSyncLibrary: () -> Unit,
    onSaveToLibrary: (String) -> Unit,
    onLoadSyncedBundle: (String) -> Unit,
    onDeleteSyncedBundle: (String) -> Unit,
    onLoadGroup: (SavedFileGroup) -> Unit,
) {
    val savedGroups by libraryViewModel.savedGroups.collectAsState()
    var saveGroupNameInput by remember { mutableStateOf("") }

    LaunchedEffect(showSaveDialog, defaultSaveBundleName) {
        if (showSaveDialog) {
            saveGroupNameInput = defaultSaveBundleName
        }
    }

    val missingOnPhone = remember(syncedBundles, desktopBundles) {
        desktopBundles.count { d -> syncedBundles.none { it.equals(d, ignoreCase = true) } }
    }
    val missingOnPc = remember(syncedBundles, desktopBundles) {
        syncedBundles.count { p -> desktopBundles.none { it.equals(p, ignoreCase = true) } }
    }
    val isOutOfSync = isConnectedToDesktop && (missingOnPhone > 0 || missingOnPc > 0)

    if (showLibraryDialog) {
        AlertDialog(
            onDismissRequest = onDismissLibrary,
            title = { Text("Bundles Library") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Library Folder row
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (syncRootFolderName != null) {
                                        "Folder: $syncRootFolderName"
                                    } else {
                                        "No Library Folder selected"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (syncRootFolderName != null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = onSelectSyncFolder,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(if (syncRootFolderName != null) "Change" else "Set Folder")
                            }
                        }
                    }

                    // Sync status & Sync button when connected to Desktop
                    if (isConnectedToDesktop) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isOutOfSync) {
                                    Color(0xFF4A3410)
                                } else {
                                    Color(0xFF143828)
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (isOutOfSync) {
                                        "⚠ Out of Sync ($missingOnPhone on PC, $missingOnPc on Phone)"
                                    } else {
                                        "✓ Library In Sync (${syncedBundles.size} bundle(s))"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOutOfSync) Color(0xFFFFD584) else Color(0xFF81F4B6),
                                )
                                if (!transferStatus.isNullOrBlank()) {
                                    Text(
                                        text = transferStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                    )
                                }
                                Button(
                                    onClick = onRequestSyncLibrary,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Sync Library with PC")
                                }
                            }
                        }
                    } else if (!transferStatus.isNullOrBlank()) {
                        Text(
                            text = transferStatus,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Bundle list (clean single-line items: tap name to load, delete icon on right)
                    if (syncedBundles.isEmpty() && savedGroups.isEmpty()) {
                        Text(
                            text = if (syncRootFolderName == null) {
                                "Tap 'Set Folder' above to select your Library folder."
                            } else {
                                "No bundles in Library yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        syncedBundles.forEach { bundleId ->
                            val isActive = activeBundleId != null && activeBundleId.equals(bundleId, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isActive) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .clickable { onLoadSyncedBundle(bundleId) }
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (isActive) "$bundleId  ✓" else bundleId,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { onDeleteSyncedBundle(bundleId) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete $bundleId",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        savedGroups.forEach { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onLoadGroup(group) }
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.name,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { libraryViewModel.deleteGroup(group.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete ${group.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissLibrary) { Text("Close") }
            },
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = onDismissSave,
            title = { Text("Save Current Files to Library") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (syncRootFolderName != null) {
                            "Copies video, A–E WAVs, and events.json into your Library folder ($syncRootFolderName):"
                        } else {
                            "You will be asked to select a Library folder where this bundle will be saved:"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = saveGroupNameInput,
                        onValueChange = { saveGroupNameInput = it },
                        placeholder = { Text("Bundle folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = saveGroupNameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveToLibrary(trimmed)
                            onDismissSave()
                        }
                    },
                ) { Text("Save to Library") }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismissSave) { Text("Cancel") }
            },
        )
    }
}