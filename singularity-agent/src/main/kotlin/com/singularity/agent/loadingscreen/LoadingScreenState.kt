package com.singularity.agent.loadingscreen

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Stan loading screen — aktualizowany przez pipeline transformacji,
 * odczytywany przez renderer. Wszystkie operacje thread-safe.
 */
class LoadingScreenState {

    enum class ModStatus { PENDING, LOADING, LOADED, ERROR }

    data class ModEntry(
        val modId: String,
        val displayName: String,
        val status: ModStatus
    )

    private val overallProgress = AtomicInteger(0) // 0-100
    private val currentStageText = AtomicReference("Inicjalizacja...")
    private val mods = ConcurrentHashMap<String, ModEntry>()
    private val conflictCount = AtomicInteger(0)
    private val slowModWarning = AtomicReference<String?>(null)
    private val currentTip = AtomicReference<String?>(null)
    @Volatile var finished = false
        private set

    fun setProgress(percent: Int) {
        overallProgress.set(percent.coerceIn(0, 100))
    }

    fun getProgress(): Int = overallProgress.get()

    fun setCurrentStage(text: String) {
        currentStageText.set(text)
    }

    fun getCurrentStage(): String = currentStageText.get()

    fun updateModStatus(modId: String, displayName: String, status: ModStatus) {
        mods[modId] = ModEntry(modId, displayName, status)
    }

    fun getMods(): List<ModEntry> = mods.values.toList().sortedBy { it.modId }

    fun getLoadedModCount(): Int = mods.values.count { it.status == ModStatus.LOADED }

    fun getTotalModCount(): Int = mods.size

    fun setConflictCount(count: Int) {
        conflictCount.set(count)
    }

    fun getConflictCount(): Int = conflictCount.get()

    fun setSlowModWarning(modId: String?) {
        slowModWarning.set(modId)
    }

    fun getSlowModWarning(): String? = slowModWarning.get()

    fun setCurrentTip(tip: String?) {
        currentTip.set(tip)
    }

    fun getCurrentTip(): String? = currentTip.get()

    fun markFinished() {
        setProgress(100)
        setCurrentStage("Gotowe")
        finished = true // last — renderer checks finished after reading progress
    }
}
