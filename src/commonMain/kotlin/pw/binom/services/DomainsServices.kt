package pw.binom.services

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.toList
import pw.binom.DomainTree
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.dns.protocol.RData
import pw.binom.dns.protocol.records.ipv4
import pw.binom.dns.protocol.records.ipv6
import pw.binom.dns.protocol.utils.normalizedRdata
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

    private val logger = KotlinLogging.logger { }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun findRecords(name: String, queryTypes: List<DnsType> = listOf(DnsType.A, DnsType.AAAA)): List<DnsRecord> {
        val controller = domains.get(name.split("."))?.value ?: return emptyList()
        val request = DnsPackage.request(hostname = name, queryTypes)
        val downStreamList = controller.downStream
            .asFlow()
            .flatMapConcat { server ->
                try {
                    dnsClientService.lookup(request, server)
                        .answer
                        .map { resource ->
                            DnsRecord(
                                content = resource.normalizedRdata()
                                    ?: throw IllegalStateException("Unknown type ${resource.type}"),
                                type = resource.type,
                                ttl = resource.ttl.toInt().seconds,
                            )
                        }
                        .asFlow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Мёртвый/медленный апстрим не должен ломать весь ответ — пропускаем его
                    logger.warn(e) { "Downstream $server lookup failed for $name" }
                    emptyFlow()
                }
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