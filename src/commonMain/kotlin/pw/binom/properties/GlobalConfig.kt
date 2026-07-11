package pw.binom.properties

import kotlinx.serialization.Serializable

@Serializable
data class GlobalConfig(
    val server: DnsServerProperty = DnsServerProperty(),
    val domains: DomainsProperty = DomainsProperty(),
    val web: WebServerProperty = WebServerProperty()
)
