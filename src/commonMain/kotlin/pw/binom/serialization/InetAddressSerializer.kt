package pw.binom.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import pw.binom.io.socket.InetAddress

object InetAddressSerializer : KSerializer<InetAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InetAddress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InetAddress =
        InetAddress.resolve(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: InetAddress) {
        encoder.encodeString(value.host)
    }
}