package pw.binom.web

import pw.binom.properties.DomainsProperty
import pw.binom.properties.WebServerProperty
import pw.binom.services.IpService

/**
 * Платформенно-зависимый веб-сервер.
 * Ожидается actual-реализация в jvmMain.
 */
expect class WebService(config: WebServerProperty) {
    fun init(
        domainsProperty: DomainsProperty,
        ipService: IpService,
        onConfigUpdate: suspend (DomainsProperty) -> Unit,
    )
    suspend fun start()
    suspend fun stop()
}
