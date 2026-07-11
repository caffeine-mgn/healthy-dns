package pw.binom.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import pw.binom.properties.WebServerProperty

/**
 * Web HTTP сервер. Поднимает Ktor CIO engine, раздаёт фронт и API.
 */
actual class WebService actual constructor(private val config: WebServerProperty) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    actual suspend fun start() {
        if (!config.enabled) return

        val s = embeddedServer(CIO, host = config.host, port = config.port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }

            routing {
                // Раздача статики фронта из build/jvmMain/resources/static
                staticResources("/", "static") {
                    default("index.html")
                }

                // Health-check
                get("/api/health") {
                    call.respond(mapOf("status" to "ok"))
                }

                // TODO: сюда добавлять API-контроллеры
            }
        }
        s.start(wait = false)
        server = s

        println("Web server started on http://${config.host}:${config.port}")
    }

    actual suspend fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
    }
}
