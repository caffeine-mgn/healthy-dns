package pw.binom.health.shared.model

import kotlinx.serialization.Serializable

/**
 * DTO для информации о healthcheck IP-адреса.
 */
@Serializable
data class IpHealthDto(
    val ip: String,
    val healthy: Boolean,
    val checkerType: String? = null,
)
