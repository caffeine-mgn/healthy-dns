package pw.binom.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import pw.binom.io.socket.InetSocketAddress

object InetSocketAddressSerializer : KSerializer<InetSocketAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InetSocketAddress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InetSocketAddress {
        val items = decoder.decodeString().split(':', limit = 2)
        if (items.size != 2) {
            throw SerializationException("")
        }
        val port = items[1].toIntOrNull() ?: throw SerializationException("Can't parse ${items[1]} to Int")
        return InetSocketAddress.resolve(host = items[0], port = port)
    }

    override fun serialize(encoder: Encoder, value: InetSocketAddress) {
        encoder.encodeString("${value.host}:${value.port}")
    }
}