package pw.binom.properties

import io.ktor.http.*
import io.ktor.network.sockets.*
import kotlinx.serialization.Serializable
import pw.binom.serialization.HttpMethodSerializer
import pw.binom.serialization.InetSocketAddressSerializer
import pw.binom.utils.HostName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class DomainsProperty(
    val records: List<Record> = emptyList(),
    val healthChecks: Map<String, HealthCheck> = emptyMap(),
    val ips: List<IP> = emptyList(),
) {

    @Serializable
    enum class Policy {
        ALL_HEALTHY,
        ROUND_ROBIN_HEALTHY,
        FIRST_HEALTHY,
    }

    @Serializable
    data class IP(
        val ip: String,
        val healthCheck: HealthCheck? = null,
    )

    @Serializable
    data class Record(
        val ips: List<String> = emptyList(),
        val downStream: Downstream? = null,
        val domain: String,
        val ttl: Duration = 30.seconds,
        val policy: Policy = Policy.ALL_HEALTHY,
    ){
        init {
//            ips.forEach {
//                val h = HostName(it)
//                require(h.isIpv4 || h.isIpv6) { "Only ipv4 or ipv6 addresses are allowed: $it" }
//            }
        }
    }

    @Serializable
    data class Downstream(
        val ips: List<@Serializable(InetSocketAddressSerializer::class) InetSocketAddress>,
    )

    @Serializable
    data class HealthCheck(
        val http: Http? = null,
        val tcp: Tcp? = null,
    ) {
        @Serializable
        data class Http(
            @Serializable(HttpMethodSerializer::class)
            val method: HttpMethod = HttpMethod.Get,
            val url: Url,
            val responseCode: Int = 200,
            val interval: Duration = 10.seconds,
            val timeout: Duration = 30.seconds,
        )

        @Serializable
        data class Tcp(
            @Serializable(InetSocketAddressSerializer::class)
            val address: InetSocketAddress,
            val interval: Duration = 10.seconds,
            val timeout: Duration = 30.seconds,
        )
    }
}