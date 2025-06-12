package pw.binom.properties

import kotlinx.serialization.Serializable
import pw.binom.serialization.URLSerializer
import pw.binom.url.URL
import pw.binom.validate.annotations.NotEmpty

@Serializable
data class PowerDnsProperty(
    @Serializable(URLSerializer::class)
    @NotEmpty
    val url: URL,
    @NotEmpty
    val token: String,
)