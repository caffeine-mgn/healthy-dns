package pw.binom.utils

import pw.binom.dns.protocol.DnsClass
import pw.binom.dns.protocol.DnsHeader
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.dns.protocol.Opcode
import pw.binom.dns.protocol.Question
import pw.binom.dns.protocol.RCode
import kotlin.random.Random

fun DnsPackage.Companion.request(hostname: String, types: List<DnsType> = listOf(DnsType.A, DnsType.AAAA)) =
    DnsPackage(
        header = DnsHeader(
            id = Random.nextInt().toShort(),
            qr = false,
            opcode = Opcode.QUERY,
            aa = false,
            tc = false,
            rd = true,
            ra = false,
            z = 0,
            rcode = RCode.NOERROR
        ),
        queries = types.map { type ->
            Question(name = hostname, type = type, clazz = DnsClass.IN)
        },
        answer = emptyList(),
        authority = emptyList(),
        additional = listOf()
    )