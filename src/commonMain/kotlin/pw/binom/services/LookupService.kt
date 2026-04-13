package pw.binom.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMap
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import pw.binom.dns.protocol.DnsClass
import pw.binom.dns.protocol.DnsHeader
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.dns.protocol.Opcode
import pw.binom.dns.protocol.RCode
import pw.binom.dns.protocol.Resource

class LookupService(
    private val domainsServices: DomainsServices,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun lookup(record: DnsPackage): DnsPackage {
        val ans = record.queries
            .asFlow()
            .filter { it.clazz == DnsClass.IN }
            .filter { it.type == DnsType.A }
            .flatMapConcat { query ->
                domainsServices.findRecords(query.name)
                    .asFlow()
                    .map {
                        query.name to it
                    }
            }
            .map { (name, record) ->
                Resource(
                    name = name,
                    type = record.type,
                    clazz = DnsClass.IN,
                    ttl = record.ttl.inWholeSeconds.toUInt(),
                    rdata = record.content,
                )
            }
            .toList()

        return DnsPackage(
            answer = ans,
            queries = record.queries,
            authority = emptyList(),
            header = DnsHeader(
                id = record.header.id,
                rd = true,
                tc = false,
                aa = true,
                opcode = Opcode.QUERY,
                qr = true,
                ra = false,
                z = 0,
                rcode = RCode.NOERROR,
            ),
            additional = emptyList()
        )
    }
}