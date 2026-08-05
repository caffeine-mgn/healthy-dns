package pw.binom.properties

import kotlinx.serialization.Serializable

@Serializable
data class WebServerProperty(
    val enabled: Boolean = true,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val token: String? = null,
) {
    init {
        require(port in 0..65535) { "Port must be in 0..65535" }
    }
}
