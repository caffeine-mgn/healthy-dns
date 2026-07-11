package pw.binom.services

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import io.ktor.utils.io.core.remaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.DnsHandle
import pw.binom.dns.protocol.DnsPackage
import pw.binom.utils.makeRefused
import kotlin.time.Duration
import kotlin.use

class DnsUdpServer(
    selectorManager: SelectorManager?,
    private val bind: SocketAddress,
    private val handler: DnsHandle,
    private val timeout: Duration = Duration.INFINITE
) : AutoCloseable {
    private val manager: SelectorManager
    private val closeManager: Boolean

    private val logger = KotlinLogging.logger { }

    init {
        require(bind is InetSocketAddress) { "Only InetSocketAddress is supported for UDP bind, got: $bind" }
        require(bind.port in 1..65535) { "Invalid UDP bind port ${bind.port}: must be in 1..65535" }

        if (selectorManager != null) {
            this.manager = selectorManager
            closeManager = false
        } else {
            this.manager = SelectorManager()
            closeManager = true
        }
    }

    private val job = this.manager.launch(start = CoroutineStart.LAZY) {
        try {
            aSocket(manager).udp().bind(bind).use { server ->
                while (currentCoroutineContext().isActive) {
                    try {
                        val l = server.receive()
                        logger.info { "Receive request from ${l.address.port()}: ${l.packet.remaining} bytes" }
                        manager.launch {
                            try {
                                val income = DnsPackage.read(l.packet.readByteArray())
                                val outcome = if (timeout == Duration.INFINITE) {
                                    handler.lookup(income)
                                } else {
                                    withTimeoutOrNull(timeout) {
                                        handler.lookup(income)
                                    } ?: income.makeRefused()
                                }
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
                                throw e
                            } catch (e: Throwable) {
                                logger.error(e) { "Error processing UDP DNS request from ${l.address}" }
                            }
                        }
                    } catch (e: CancellationException) {
                        logger.warn(e) { "Dns UDP server JOB cancelled" }
                        break
                    } catch (e: Throwable) {
                        logger.error(e) { "Error on processing request" }
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
                this.manager.close()
            }
        }
        job.start()
    }
}