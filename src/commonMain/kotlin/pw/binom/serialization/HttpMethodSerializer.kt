package pw.binom.serialization

import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object HttpMethodSerializer : KSerializer<HttpMethod> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HttpMethod", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): HttpMethod =
        HttpMethod(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: HttpMethod) {
        encoder.encodeString(value.toString())
    }
}