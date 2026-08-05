package pw.binom.health.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class IpHealthDto(
    val ip: String,
    val healthy: Boolean? = null,
    val healthCheck: HealthCheckDto? = null,
    val domains: List<String> = emptyList(),
)

@Serializable
data class HealthCheckDto(
    val http: HttpCheckDto? = null,
    val tcp: TcpCheckDto? = null,
)

@Serializable
data class HttpCheckDto(
    val url: String,
    val method: String = "GET",
    val responseCode: Int = 200,
    val interval: String = "PT10S",
    val timeout: String = "PT30S",
)

@Serializable
data class TcpCheckDto(
    val host: String,
    val port: Int,
    val interval: String = "PT10S",
    val timeout: String = "PT30S",
)
