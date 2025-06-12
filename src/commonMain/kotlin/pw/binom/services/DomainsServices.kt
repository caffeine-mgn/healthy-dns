package pw.binom.services

import kotlinx.coroutines.runBlocking
import pw.binom.DomainTree
import pw.binom.dns.QType
import pw.binom.io.socket.InetAddress
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.socket.ProtocolFamily
import pw.binom.properties.DomainsProperty
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject
import pw.binom.strong.properties.injectProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DomainsServices {
    private val domainsProperty by injectProperty<DomainsProperty>()
    private val ipService by inject<IpService>()
    private val dnsClientService by inject<DnsClientService>()

    class DnsRecord(
        val content: ByteArray,
        val type: QType,
        val ttl: Duration,
    )

    fun findRecords(name: String): List<DnsRecord> {
        val controller = domains.get(name.split("."))?.value ?: return emptyList()
        val downStreamList = controller.downStream
            .asSequence()
            .map {
                runBlocking {
                    dnsClientService.sendRequest(name, it)
                        .ans
                        .map {
                            DnsRecord(content = it.rdata, type = it.type, ttl = it.ttl.toInt().seconds)
                        }
                }
            }.flatten()
        return controller.ips
            .mapNotNull { ip ->
                if (ipService[ip]?.healthy != false) {
                    DnsRecord(
                        content = ip.address,
                        type = when (ip.protocolFamily) {
                            ProtocolFamily.AF_INET -> QType.A
                            ProtocolFamily.AF_INET6 -> QType.AAAA
                            else -> TODO("Protocol ${ip.protocolFamily} not supported")
                        },
                        ttl = controller.ttl,
                    )
                } else {
                    null
                }
            }
            .toList() + downStreamList
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
        val ips: List<InetAddress>,
        val ttl: Duration,
    )

    private val domains = DomainTree<DomainController>()

    init {
        BeanLifeCycle.postConstruct {
            domainsProperty.ips
                .asSequence()
                .filter { it.healthCheck != null }
                .forEach {
                    ipService.addIp(it.ip, createChecker(it.healthCheck!!))
                }

            domainsProperty.records.forEach { record ->
                val ips = record.ips
                    .map { InetAddress.resolve(it) }
                domains.getOrPut(record.domain).value = DomainController(
                    ips = ips,
                    ttl = record.ttl,
                    downStream = record.downStream?.ips ?: emptyList()
                )
            }
        }
    }
}