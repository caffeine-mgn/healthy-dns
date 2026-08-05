package pw.binom.health.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null,
)

@Serializable
data class DashboardStatsDto(
    val dnsStatus: String,
    val webStatus: String,
    val recordsCount: Int,
    val ipsCount: Int,
    val healthyCount: Int,
    val unhealthyCount: Int,
    val unmonitoredCount: Int,
)
