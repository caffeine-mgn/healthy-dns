package pw.binom.web

import io.ktor.http.*
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.serialization.json.Json
import pw.binom.health.shared.model.*
import pw.binom.mcp.DnsTools
import pw.binom.properties.DomainsProperty
import pw.binom.properties.WebServerProperty
import pw.binom.services.IpService
import pw.binom.utils.HostName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.seconds

actual class WebService actual constructor(
    private val config: WebServerProperty,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private var domainsProperty: DomainsProperty = DomainsProperty()
    private var ipService: IpService? = null
    private var onConfigUpdate: (suspend (DomainsProperty) -> Unit)? = null
    private var dnsTools: DnsTools? = null

    actual fun init(
        domainsProperty: DomainsProperty,
        ipService: IpService,
        onConfigUpdate: suspend (DomainsProperty) -> Unit,
    ) {
        this.domainsProperty = domainsProperty
        this.ipService = ipService
        this.onConfigUpdate = onConfigUpdate
        this.dnsTools = DnsTools(
            config,
            { this.domainsProperty },
            { newProps ->
                this.domainsProperty = newProps
                this@WebService.onConfigUpdate?.invoke(newProps)
            },
            ipService,
        )
    }

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
                // === Auth ===
                if (config.token != null) {
                    intercept(ApplicationCallPipeline.Call) {
                        val path = call.request.path()
                        if (path != "/api/health") {
                            val auth = call.request.header("Authorization")
                            if (auth != "Bearer ${config.token}") {
                                call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                                return@intercept
                            }
                        }
                    }
                }

                // === MCP: Streamable HTTP ===
                mcpStreamableHttp { dnsTools!!.server }

                get("/api/health") {
                    call.respond(mapOf("status" to "ok", "mcp" to "/mcp"))
                }
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
