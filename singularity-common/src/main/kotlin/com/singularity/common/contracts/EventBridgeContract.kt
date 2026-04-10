package com.singularity.common.contracts

import com.singularity.common.model.EventPriority
import com.singularity.common.model.LoaderType

/**
 * Kontrakt unified event pipeline — implementowany przez moduł compat.
 *
 * Dispatch flow (design spec sekcja 5.2B):
 * 1. Zdarzenie w grze → tworzony SingularityEvent
 * 2. Zbierane handlery z OBU loaderów, sortowane po priorytecie
 * 3. Handlery odpalane sekwencyjnie
 * 4. Cancel z któregokolwiek → canceled dla WSZYSTKICH
 *
 * Mapowanie cancellacji:
 * - Forge event.setCanceled(true) → CANCEL
 * - Fabric callback zwraca FAIL/false → CANCEL
 * - Fabric callback zwraca PASS → PASS (neutralne)
 * - Fabric callback zwraca SUCCESS/true → SUCCESS (kontynuuj)
 */
interface EventBridgeContract {

    enum class EventResult { PASS, SUCCESS, CANCEL }

    data class EventHandler(
        val modId: String,
        val loaderType: LoaderType,
        val priority: EventPriority,
        val handler: (Any) -> EventResult
    )

    /** Rejestruje handler eventu. */
    fun registerHandler(eventType: String, handler: EventHandler)

    /**
     * Dispatchuje event do WSZYSTKICH handlerów (oba loadery), posortowanych po priorytecie.
     * @return true jeśli event NIE został canceled, false jeśli canceled
     */
    fun dispatch(eventType: String, eventData: Any): Boolean

    /** Zwraca liczbę zarejestrowanych handlerów dla danego typu eventu. */
    fun getHandlerCount(eventType: String): Int
}
