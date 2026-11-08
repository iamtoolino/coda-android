package dev.coda.core.subsonic

import dev.coda.core.playback.ReplayGainMetadata
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.descriptors.buildClassSerialDescriptor

/** LossyReplayGain / ReplayGainMetadata decoding: any invalid field is absent, never a decode failure. */
object LossyReplayGainSerializer : KSerializer<ReplayGainMetadata?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LossyReplayGain")

    override fun deserialize(decoder: Decoder): ReplayGainMetadata? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("JSON only")
        val values = element as? JsonObject ?: return null
        fun number(key: String): Double? =
            (values[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
        return ReplayGainMetadata(number("trackGain"), number("albumGain"), number("trackPeak"), number("albumPeak"))
    }

    override fun serialize(encoder: Encoder, value: ReplayGainMetadata?) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        json.encodeJsonElement(if (value == null) JsonNull else buildJsonObject {
            value.trackGain?.let { put("trackGain", JsonPrimitive(it)) }
            value.albumGain?.let { put("albumGain", JsonPrimitive(it)) }
            value.trackPeak?.let { put("trackPeak", JsonPrimitive(it)) }
            value.albumPeak?.let { put("albumPeak", JsonPrimitive(it)) }
        })
    }
}
