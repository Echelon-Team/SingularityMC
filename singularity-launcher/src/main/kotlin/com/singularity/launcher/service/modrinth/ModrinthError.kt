package com.singularity.launcher.service.modrinth

/**
 * Sealed hierarchy dla błędów Modrinth API — rozróżnia stany żeby UI mogło pokazać
 * odpowiednie komunikaty zamiast generic "błąd".
 *
 * - NoQuery — initial state przed pierwszym search
 * - EmptyResults — API returned empty (user wpisał query, nic nie znaleziono)
 * - RateLimit — HTTP 429 z retryAfterSec (Modrinth ma 300/min limit)
 * - Network — connection refused/timeout
 * - Offline — brak internet
 * - Server — 5xx
 */
sealed class ModrinthError {
    object NoQuery : ModrinthError()
    object EmptyResults : ModrinthError()
    data class RateLimit(val retryAfterSec: Int) : ModrinthError()
    data class Network(val message: String) : ModrinthError()
    object Offline : ModrinthError()
    data class Server(val statusCode: Int, val message: String) : ModrinthError()
}
