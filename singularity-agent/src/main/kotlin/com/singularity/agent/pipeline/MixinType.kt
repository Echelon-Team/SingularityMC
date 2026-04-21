// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.pipeline

/**
 * Typy mixin modifications ktore PreScanAnalyzer rozpoznaje.
 * Odpowiadaja adnotacjom SpongePowered Mixin: @Inject, @Overwrite, @Redirect,
 * @ModifyVariable, @WrapOperation, @ModifyArg, @ModifyConstant, @ModifyReturnValue.
 */
enum class MixinType {
    INJECT,
    OVERWRITE,
    REDIRECT,
    MODIFY_VARIABLE,
    WRAP_OPERATION,
    MODIFY_ARG,
    MODIFY_CONSTANT,
    MODIFY_RETURN_VALUE
}
