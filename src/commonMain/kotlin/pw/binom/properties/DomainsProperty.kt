package pw.binom.properties

import kotlinx.serialization.Serializable
import pw.binom.io.socket.InetAddress
import pw.binom.io.socket.InetSocketAddress
import pw.binom.properties.serialization.annotations.PropertiesPrefix
import pw.binom.serialization.InetAddressSerializer
import pw.binom.serialization.InetSocketAddressSerializer
import pw.binom.serialization.URLSerializer
import pw.binom.url.URL
import pw.binom.validate.annotations.OneOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@PropertiesPrefix("domains")
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
        @Serializable(InetAddressSerializer::class)
        val ip: InetAddress,
        val healthCheck: HealthCheck? = null,
    )

    @Serializable
    data class Record(
        val ips: List<String> = emptyList(),
        val downStream: Downstream? = null,
        val domain: String,
        val ttl: Duration = 30.seconds,
        val policy: Policy = Policy.ALL_HEALTHY,
    )

    @Serializable
    data class Downstream(
        val ips: List<@Serializable(InetSocketAddressSerializer::class) InetSocketAddress>,
    )

    @OneOf("http", "tcp")
    @Serializable
    data class HealthCheck(
        val http: Http? = null,
        val tcp: Tcp? = null,
    ) {
        @Serializable
        data class Http(
            val method: String = "GET",
            @Serializable(URLSerializer::class)
            val url: URL,
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