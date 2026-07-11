package pw.binom.web

import pw.binom.properties.WebServerProperty

/**
 * Платформенно-зависимый веб-сервер.
 * Ожидается actual-реализация в jvmMain.
 */
expect class WebService(config: WebServerProperty) {
    suspend fun start()
    suspend fun stop()
}
