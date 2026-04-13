package pw.binom

import com.charleskorn.kaml.Yaml
import io.ktor.client.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
suspend fun main(args: Array<String>) {
    val configFile = Path("config.yaml")
    if (!SystemFileSystem.exists(configFile)) {
        println("Config file missing")
        return
    }
    val config = SystemFileSystem.source(configFile).buffered().use {
        Yaml.default.decodeFromString(GlobalConfig.serializer(), it.readText())
    }
//    val config = DnsServerProperty(
//        bind = InetSocketAddress(port = 5333, hostname = "0.0.0.0"),
//        packageSize = 1500,
//    )
//    val domains = DomainsProperty(
//        records = listOf(
//            DomainsProperty.Record(
//                ips = listOf("192.168.0.1"),
//                domain = "test.local",
//            ),
//            DomainsProperty.Record(
//                ips = listOf("test.local"),
//                domain = "www.test.local",
//            )
//        )
//    )
    val selectorManager = SelectorManager()
    val httpClient = HttpClient()
    val ip = IpService(
        httpClient = httpClient,
        networkManager = selectorManager,
    )
    val dnsClient = DnsUdpClient(selectorManager)
    val domainsServices = DomainsServices(
        ipService = ip,
        dnsClientService = dnsClient,
        domainsProperty = config.domains,

        )
    val lookupService = LookupService(domainsServices = domainsServices)

    val udpServer = DnsUdpServer(
        selectorManager = selectorManager,
        bind = config.server.bind,
    ) {
        lookupService.lookup(it)
    }

    val tcpserver = DnsTcpServer(
        bind = config.server.bind,
        selectorManager = selectorManager,
        lookupService = lookupService
    )
    tcpserver.join()
    udpServer.join()
}