package pw.binom

import com.charleskorn.kaml.Yaml
import io.ktor.client.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.properties.DnsServerProperty
import pw.binom.properties.DomainsProperty
import pw.binom.properties.GlobalConfig
import pw.binom.services.DnsTcpServer
import pw.binom.services.DnsUdpClient
import pw.binom.services.DnsUdpServer
import pw.binom.services.DomainsServices
import pw.binom.services.IpService
import pw.binom.services.LookupService
import pw.binom.utils.request
import pw.binom.web.WebService
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    val configPath = args.firstOrNull() ?: "config.yaml"
    runBlocking {
        val configFile = Path(configPath)
        if (!SystemFileSystem.exists(configFile)) {
            println("Config file missing: $configPath")
            return@runBlocking
        }
        val config = SystemFileSystem.source(configFile).buffered().use {
            Yaml.default.decodeFromString(GlobalConfig.serializer(), it.readText())
        }

        val selectorManager = SelectorManager()
        val httpClient = HttpClient()
        val webService = if (config.web.enabled) {
            val ws = WebService(config.web)
            ws.start()
            println("Web server started on http://${config.web.host}:${config.web.port}")
            ws
        } else {
            println("Web server disabled")
            null
        }

        try {
            val ip = IpService(
                httpClient = httpClient,
                networkManager = selectorManager,
            )
            val dnsClient = DnsUdpClient(selectorManager)
            try {
                val domainsServices = DomainsServices(
                    ipService = ip,
                    dnsClientService = dnsClient,
                    domainsProperty = config.domains,
                )
                val lookupService = LookupService(domainsServices = domainsServices)

                val dnsHandle = DnsHandle { pack ->
                    lookupService.lookup(pack)
                }
                    .withTimeout(5.seconds)
                    .withRetry(3)

                val udpServer = DnsUdpServer(
                    selectorManager = selectorManager,
                    bind = config.server.bind,
                    handler = dnsHandle,
                )
                val tcpserver = DnsTcpServer(
                    bind = config.server.bind,
                    selectorManager = selectorManager,
                    handler = dnsHandle,
                )
                try {
                    tcpserver.join()
                    udpServer.join()
            } finally {
                    tcpserver.close()
                    udpServer.close()
                }
            } finally {
                dnsClient.close()
            }
        } finally {
            webService?.stop()
            httpClient.close()
            selectorManager.close()
        }
    }
}
