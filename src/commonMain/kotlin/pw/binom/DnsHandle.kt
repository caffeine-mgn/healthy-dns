package pw.binom

import pw.binom.dns.protocol.DnsPackage

fun interface DnsHandle {
    suspend fun lookup(record: DnsPackage): DnsPackage
}