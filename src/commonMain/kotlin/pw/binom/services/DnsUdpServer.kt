package pw.binom.services

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import io.ktor.utils.io.core.remaining
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

    private val logger = KotlinLogging.logger { }

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
        try {
            aSocket(this@DnsUdpServer.selectorManager).udp().bind(bind).use { server ->
                while (currentCoroutineContext().isActive) {
                    try {
                        val l = server.receive()
                        logger.info { "Receive request from ${l.address.port()}: ${l.packet.remaining} bytes" }
                        val income = DnsPackage.read(l.packet.readByteArray())
                        val outcome = handler.lookup(income)
                        val b = Buffer()
                        outcome.write(b)
                        logger.info { "Send response to ${l.address}: ${b.size} bytes" }
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
        } finally {
            logger.info { "Dns UDP server JOB finished" }
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