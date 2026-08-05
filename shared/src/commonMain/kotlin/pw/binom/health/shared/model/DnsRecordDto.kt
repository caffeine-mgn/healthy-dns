package pw.binom.health.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class DnsRecordDto(
    val domain: String,
    val ips: List<String> = emptyList(),
    val ttl: String = "PT30S",
    val policy: String = "ALL_HEALTHY",
    val downstream: DownstreamDto? = null,
)

@Serializable
data class DownstreamDto(
    val ips: List<String> = emptyList(),
)
