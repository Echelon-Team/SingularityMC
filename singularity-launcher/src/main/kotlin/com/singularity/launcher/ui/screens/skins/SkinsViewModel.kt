// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.skins

import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

data class SkinsScreenState(
    val skins: List<SkinEntry> = emptyList(),
    val selectedSkin: SkinEntry? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val uploadStatus: String? = null,
    val canUpload: Boolean = false  // #19 edge-case — offline accounts disabled upload
)

/**
 * ViewModel dla SkinsScreen. Zarządza galerią skinów (upload + select).
 *
 * **Skins dir:** `<launcherHome>/skins/` — user własne skiny.
 * **Premium gate (#19):** `canUpload` ustawiane z AuthManager — offline = false.
 */
class SkinsViewModel(
    private val skinsDir: Path,
    private val isPremiumProvider: () -> Boolean = { false },
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<SkinsScreenState>(
    SkinsScreenState(),
    dispatcher
) {

    init {
        loadSkins()
    }

    private fun loadSkins() {
        updateState { it.copy(isLoading = true, error = null, canUpload = isPremiumProvider()) }
        viewModelScope.launch {
            try {
                Files.createDirectories(skinsDir)
                val skins = Files.list(skinsDir).use { stream ->
                    stream
                        .filter { it.name.lowercase().endsWith(".png") }
                        .map { path ->
                            SkinEntry(
                                path = path,
                                name = path.name.removeSuffix(".png"),
                                model = SkinModel.STEVE  // Default detection Sub 5
                            )
                        }
                        .toList()
                }
                updateState {
                    it.copy(
                        skins = skins,
                        isLoading = false,
                        selectedSkin = skins.firstOrNull()
                    )
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectSkin(skin: SkinEntry) {
        updateState { it.copy(selectedSkin = skin) }
    }

    fun uploadSkin(sourceFile: java.io.File) {
        viewModelScope.launch {
            try {
                // Validate
                val result = SkinPngValidator.validate(sourceFile)
                if (result is SkinPngValidator.ValidationResult.Invalid) {
                    updateState { it.copy(uploadStatus = "Invalid: ${result.reason}") }
                    return@launch
                }

                // Copy to skins dir
                Files.createDirectories(skinsDir)
                val target = skinsDir.resolve(sourceFile.name)
                Files.copy(sourceFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING)

                loadSkins()
                updateState { it.copy(uploadStatus = "Uploaded: ${sourceFile.name}") }
            } catch (e: Exception) {
                updateState { it.copy(uploadStatus = "Upload failed: ${e.message}") }
            }
        }
    }

    fun refresh() = loadSkins()

    fun clearUploadStatus() = updateState { it.copy(uploadStatus = null) }
}
