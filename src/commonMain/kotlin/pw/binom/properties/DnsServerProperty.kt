package pw.binom.properties

import io.ktor.network.sockets.*
import kotlinx.serialization.Serializable
import pw.binom.serialization.InetSocketAddressSerializer

@Serializable
data class DnsServerProperty(
    @Serializable(InetSocketAddressSerializer::class)
    val bind: InetSocketAddress = InetSocketAddress(hostname = "0.0.0.0", port = 53),
    val packageSize: Int = 1500,
) {
    init {
        require(packageSize >= 0)
    }
}