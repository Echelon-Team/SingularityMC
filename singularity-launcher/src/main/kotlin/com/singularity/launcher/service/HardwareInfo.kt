package com.singularity.launcher.service

import java.lang.management.ManagementFactory

/**
 * Minimal read-only hardware info — totalCores + totalRamMB.
 *
 * **Scope decyzja D6 Mateusza (2026-04-11):** To NIE jest pełny `ResourceManager`
 * (dynamic tracking uruchomionych instances + RAM balancing + overbook warnings
 * + preferred configs). Pełna rozbudowa idzie do Sub 5 (Task 23 ResourceManager).
 *
 * **Użycie:** `ResourceSlider` (Task 8) w `NewInstanceWizard` (Task 13) i
 * `InstanceSettingsModal` (Task 14) używa `HardwareInfo.totalCores/totalRamMB`
 * jako max range sliderów. Zabezpiecza przed bugiem "user z 8GB ustawia 16GB
 * RAM dla instancji → gra crashuje na OOM" (C5 v3 ui-reviewer blocker).
 *
 * **API:** Read-only, lazy properties, JVM standard API (bez zewnętrznych deps).
 * - `totalCores`: `Runtime.availableProcessors()` — liczba logicznych wątków
 *   (hyperthreading counts as separate cores)
 * - `totalRamMB`: `com.sun.management.OperatingSystemMXBean.totalMemorySize` /
 *   1048576 — total physical RAM w MB, działa na Windows/Linux/Mac
 *
 * **UWAGA** — pokrywa się nazwą z `onboarding.HardwareDetector` (Sub 5 Task 13)
 * ALE są to rożne rzeczy:
 * - `service.HardwareInfo` (Sub 4 Task 7): minimal, 2 fields, używane przez ResourceSlider
 * - `onboarding.HardwareDetector` (Sub 5 Task 13): GPU detection, OS info, preset
 *   recommendation, używane przez OnboardingWizard
 *
 * Oba coexist — różne pakiety, różne scope.
 */
object HardwareInfo {
    /**
     * Liczba logicznych wątków CPU (hyperthreading counts as separate).
     * `Runtime.availableProcessors()` — standard JVM API, O(1) po pierwszym wywołaniu.
     */
    val totalCores: Int by lazy {
        Runtime.getRuntime().availableProcessors()
    }

    /**
     * Total physical RAM w MB.
     * Używa `com.sun.management.OperatingSystemMXBean` (NIE `java.lang.management.OperatingSystemMXBean`,
     * ktora NIE ma `totalMemorySize`). Sun-specific API ale działa na wszystkich JVMach
     * (OpenJDK, Oracle, Adoptium Temurin, Zulu itd.) bo JMX spec jest szeroka.
     */
    val totalRamMB: Int by lazy {
        val osBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        (osBean.totalMemorySize / (1024 * 1024)).toInt()
    }
}
