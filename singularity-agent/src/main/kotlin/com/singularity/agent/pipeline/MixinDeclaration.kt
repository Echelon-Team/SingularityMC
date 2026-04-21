// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

/**
 * Deklaracja pojedynczego mixina zebranego z mod JAR'a (Fabric fabric.mod.json lub Forge mods.toml).
 * Pre-scan analyzer uzywa listy MixinDeclaration do wykrywania konfliktow PRZED Mixin apply step.
 */
data class MixinDeclaration(
    val modId: String,
    val targetClass: String,
    val targetMethod: String?,
    val type: MixinType
)
