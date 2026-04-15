package com.singularity.launcher.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path

/**
 * kotlinx-serialization codec for [java.nio.file.Path].
 *
 * JSON wire format: plain string (e.g. `"D:\\Games\\MC"`). Callers receive [Path] typed value.
 *
 * Use: `@Serializable(with = PathSerializer::class) val pathField: Path? = null`
 */
object PathSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.nio.file.Path", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Path =
        Path.of(decoder.decodeString())
}
