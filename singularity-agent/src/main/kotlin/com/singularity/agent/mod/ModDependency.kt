package com.singularity.agent.mod

/**
 * Zależność moda — wyciągnięta z depends (Fabric) lub [[dependencies]] (Forge).
 *
 * versionRange to surowy string z metadanych moda, np.:
 * - Fabric: ">=0.14.21", "~1.20.1", "*"
 * - Forge: "[47,)", "[1.20.1,1.20.2)"
 * Parsowanie zakresów jest odpowiedzialnością DependencyResolver.
 *
 * Referencja: design spec sekcja 5A.2-5A.3.
 */
data class ModDependency(
    /** ID moda od którego zależy (np. "fabricloader", "forge", "minecraft", "fabric-api") */
    val modId: String,

    /** Zakres wymaganej wersji — surowy string z metadanych (null = dowolna wersja) */
    val versionRange: String?,

    /** Czy zależność jest wymagana (true) czy opcjonalna (false = "suggests" w Fabric) */
    val required: Boolean
)
