package pw.binom.services

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException
import pw.binom.DnsHandle
import pw.binom.dns.protocol.*

@OptIn(ExperimentalStdlibApi::class, DelicateCoroutinesApi::class)
class DnsTcpServer(
    selectorManager: SelectorManager?,
    private val bind: SocketAddress,
    private val handler: DnsHandle,
    private val maxConnections: Int = 100,
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

    private val connectionSemaphore = Semaphore(maxConnections)
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job = this.selectorManager.launch(start = CoroutineStart.LAZY) {
        aSocket(this@DnsTcpServer.selectorManager).tcp().bind(bind)
            .use { server ->
                while (isActive) {
                    val newClient = server.accept()
                    connectionScope.launch {
                        connectionSemaphore.withPermit {
                        newClient.use { client ->
                            val read = client.openReadChannel()
                            val write = client.openWriteChannel()
                            while (isActive) {
                                try {
                                    val size = read.readShort().toUShort().toInt()
                                    val income = DnsPackage.read(read.readByteArray(size))
                                    val outcome = handler.lookup(income)
                                    val b = Buffer()
                                    outcome.write(b)
                                    write.writeShort(b.size.toInt().toUShort().toShort())
                                    write.writePacket(b)
                                    write.flush()
                                } catch (_: EOFException) {
                                    break
                                } catch (_: CancellationException) {
                                    break
                                } catch (_: IOException) {
                                    break
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    break
                                }
                            }
                        }
                        }
                    }
                }
            }
    }

    init {
        if (closeManager) {
            job.invokeOnCompletion {
                this.selectorManager.close()
            }
        }
        job.start()
    }

    suspend fun join() {
        job.join()
    }

    override fun close() {
        job.cancel()
        connectionScope.cancel()
    }
}
