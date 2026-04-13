package pw.binom.properties

import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class PowerDnsProperty(
    val url: Url,
    val token: String,
) {
    init {
        require(url.protocol.name == "http" || url.protocol.name == "https") { "Only http and https protocols are supported: $url" }
        require(token.isNotBlank()) { "Invalid token" }
    }
}