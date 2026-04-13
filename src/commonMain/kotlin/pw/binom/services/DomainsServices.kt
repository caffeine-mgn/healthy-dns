package pw.binom.services

import io.ktor.network.sockets.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pw.binom.DomainTree
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.dns.protocol.RData
import pw.binom.dns.protocol.records.ipv4
import pw.binom.dns.protocol.records.ipv6
import pw.binom.properties.DomainsProperty
import pw.binom.utils.HostName
import pw.binom.utils.request
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DomainsServices(
    val ipService: IpService,
    val dnsClientService: DnsUdpClient,
    val domainsProperty: DomainsProperty,
) {

    class DnsRecord(
        val content: RData,
        val type: DnsType,
        val ttl: Duration,
    )

    suspend fun findRecords(name: String): List<DnsRecord> {
        val controller = domains.get(name.split("."))?.value ?: return emptyList()
        val request = DnsPackage.request(hostname = name, listOf(DnsType.A))
        val downStreamList = controller.downStream
            .asFlow()
            .flatMapConcat {
                    dnsClientService.lookup(request, it)
                        .answer
                        .map {
                            DnsRecord(content = it.rdata, type = it.type, ttl = it.ttl.toInt().seconds)
                        }
                        .asFlow()
            }
        return controller.ips
            .mapNotNull { ip ->
                if (ipService[ip]?.healthy != false) {
                    val host = HostName(ip)

                    val type: DnsType
                    val data: RData
                    when {
                        host.isIpv4 -> {
                            type = DnsType.A
                            data = RData.ipv4(ip)
                        }

                        host.isIpv6 -> {
                            type = DnsType.AAAA
                            data = RData.ipv6(ip)
                        }
                        else -> {
                            type = DnsType.CNAME
                            data = RData.cname(ip)
                        }
                    }

                    DnsRecord(
                        content = data,
                        type = type,
                        ttl = controller.ttl,
                    )
                } else {
                    null
                }
            }
            .toList() + downStreamList.toList()
    }

    private fun createChecker(healthCheck: DomainsProperty.HealthCheck) =
        when {
            healthCheck.http != null -> IpService.Checker.Http(
                interval = healthCheck.http.interval,
                method = healthCheck.http.method,
                url = healthCheck.http.url,
                timeout = healthCheck.http.timeout,
                responseCode = healthCheck.http.responseCode,
            )

            healthCheck.tcp != null -> IpService.Checker.Tcp(
                interval = healthCheck.tcp.interval,
                timeout = healthCheck.tcp.timeout,
                address = healthCheck.tcp.address,
            )

            else -> TODO()
        }

    private class DomainController(
        val downStream: List<InetSocketAddress>,
        val ips: List<String>,
        val ttl: Duration,
    )

    private val domains = DomainTree<DomainController>()

    init {
        domainsProperty.ips
            .asSequence()
            .filter { it.healthCheck != null }
            .forEach {
                ipService.addIp(HostName(it.ip), createChecker(it.healthCheck!!))
            }

        domainsProperty.records.forEach { record ->
            val ips = record.ips
            domains.getOrPut(record.domain).value = DomainController(
                ips = ips,
                ttl = record.ttl,
                downStream = record.downStream?.ips ?: emptyList()
            )
        }
    }
}