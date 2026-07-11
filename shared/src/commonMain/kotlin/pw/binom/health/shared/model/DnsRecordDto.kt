package pw.binom.health.shared.model

import kotlinx.serialization.Serializable

/**
 * DTO для DNS-записи, отдаваемой через API.
 */
@Serializable
data class DnsRecordDto(
    val domain: String,
    val type: String,
    val content: String,
    val ttl: Long,
)
