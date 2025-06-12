package pw.binom

import kotlinx.coroutines.test.runTest
import pw.binom.dns.DnsRecord
import pw.binom.io.ByteBuffer
import pw.binom.io.socket.*
import pw.binom.io.use
import pw.binom.network.MultiFixedSizeThreadNetworkDispatcher
import pw.binom.network.bindUdp
import kotlin.test.Test

class DnsTest {

    @Test
    fun test() = runTest {
        MultiFixedSizeThreadNetworkDispatcher(4).use { nm ->
            nm.bindUdp(InetSocketAddress.resolve("127.0.0.1", 8053)).use { server ->
                val e = MutableInetSocketAddress()
                ByteBuffer(1500).use { buf ->
                    server.read(buf, e)
                    buf.flip()
                    val record = DnsRecord.read(buf)
                    println("request->$record")
                    buf.clear()
                    record.write(buf)
                    buf.flip()
                    nm.attach(UdpNetSocket()).use { it ->
                        it.write(buf, InetSocketAddress.resolve("192.168.76.1", 53))
                        buf.clear()
                        it.read(buf, null)
                        buf.flip()
                        val resp = DnsRecord.read(buf)
                        println("response->$resp")
                        buf.clear()
                        resp.write(buf)
                        buf.flip()
                        server.write(buf, e)
                    }
                }
            }
        }
    }
}