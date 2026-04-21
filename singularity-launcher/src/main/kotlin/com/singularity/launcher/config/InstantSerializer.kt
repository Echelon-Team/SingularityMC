// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * kotlinx-serialization codec for [java.time.Instant].
 *
 * JSON wire format: ISO 8601 string (e.g. `"2026-04-15T10:30:00Z"`). Matches GitHub API
 * conventions and Java's `Instant.toString()` / `Instant.parse()`.
 *
 * Use: `@Serializable(with = InstantSerializer::class) val timestamp: Instant`
 *
 * Throws [java.time.format.DateTimeParseException] on malformed input (deserialization); caller
 * should rely on `ignoreUnknownKeys` + outer try/catch for graceful degradation.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}
