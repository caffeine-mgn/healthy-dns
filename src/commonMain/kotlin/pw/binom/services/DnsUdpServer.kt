package pw.binom.services

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.DnsHandle
import pw.binom.dns.protocol.DnsPackage
import kotlin.use

class DnsUdpServer(
    selectorManager: SelectorManager?,
    private val bind: SocketAddress,
    private val handler: DnsHandle,
) : AutoCloseable {
    private val selectorManager: SelectorManager
    private val closeManager: Boolean

    init {
        if (selectorManager != null) {
            this.selectorManager = selectorManager
            closeManager = false
        } else {
            this.selectorManager = SelectorManager()
            closeManager = true
        }
    }

    private val job = this.selectorManager.launch(start = CoroutineStart.LAZY) {
        aSocket(this@DnsUdpServer.selectorManager).udp().bind(bind).use { server ->
            while (currentCoroutineContext().isActive) {
                try {
                    val l = server.receive()
                    val income = DnsPackage.read(l.packet.readByteArray())
                    println("Income package: $income")
                    val outcome = handler.lookup(income)
                    val b = Buffer()
                    outcome.write(b)
                    server.send(
                        Datagram(
                            packet = b,
                            address = l.address
                        )
                    )
                } catch (e: CancellationException) {
                    break
                } catch (e: Throwable) {
                    e.printStackTrace()
                    break
                }
            }
        }
    }

    suspend fun join() {
        job.join()
    }

    override fun close() {
        job.cancel()
    }

    init {
        if (closeManager) {
            job.invokeOnCompletion {
                this.selectorManager.close()
            }
        }
        job.start()
    }
}