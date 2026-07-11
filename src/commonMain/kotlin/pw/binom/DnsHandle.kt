package pw.binom

import kotlinx.coroutines.withTimeoutOrNull
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.Opcode
import pw.binom.dns.protocol.RCode
import pw.binom.utils.makeRefused
import kotlin.time.Duration

fun interface DnsHandle {
    suspend fun lookup(record: DnsPackage): DnsPackage
}

fun DnsHandle.withRetry(count: Int) =
    if (count <= 0) {
        this
    } else {
        val self = this
        var count = count
        DnsHandle { pack ->
            while (count > 0) {
                val result = self.lookup(pack)
                if (result.header.rcode == RCode.SERVFAIL) {
                    count--
                    continue
                }
                return@DnsHandle result
            }
            return@DnsHandle pack.makeRefused()
        }
    }

fun DnsHandle.withTimeout(timeout: Duration) =
    if (timeout == Duration.INFINITE) {
        this
    } else {
        val oldHandler = this
        DnsHandle { pack ->
            withTimeoutOrNull(timeout) {
                oldHandler.lookup(pack)
            } ?: pack.makeRefused()
        }
    }