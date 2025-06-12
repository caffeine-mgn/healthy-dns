package pw.binom.properties

import kotlinx.serialization.Serializable
import pw.binom.io.socket.InetSocketAddress
import pw.binom.properties.serialization.annotations.PropertiesPrefix
import pw.binom.serialization.InetSocketAddressSerializer
import pw.binom.validate.annotations.GreaterOrEquals

@PropertiesPrefix("dns")
@Serializable
data class DnsServerProperty(
    @Serializable(InetSocketAddressSerializer::class)
    val bind: InetSocketAddress = InetSocketAddress.resolve(host = "0.0.0.0", port = 53),
    @GreaterOrEquals("100")
    val packageSize: Int = 1500,
)