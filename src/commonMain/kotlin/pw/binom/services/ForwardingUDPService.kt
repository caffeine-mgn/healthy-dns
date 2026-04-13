package pw.binom.services
/*
import com.diamondedge.logging.logging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.internal.decodeToSequenceByReader
import pw.binom.dns.DnsRecord
import pw.binom.io.ByteBuffer
import pw.binom.io.holdState
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.socket.MutableInetSocketAddress
import pw.binom.io.socket.UdpNetSocket
import pw.binom.io.use
import pw.binom.logger.Logger
import pw.binom.logger.info
import pw.binom.network.NetworkManager
import pw.binom.network.bindUdp
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject
import pw.binom.dns.protocol.DnsPackage

@OptIn(ExperimentalStdlibApi::class)
class ForwardingUDPService(val selector: SelectorManager) {

    private val logger = logging()

    init {
        bgJob {
            try {
                val dnsServer = InetSocketAddress.resolve("192.168.76.101", 53)
                networkManager.bindUdp(InetSocketAddress.resolve("0.0.0.0", 53)).use { socket ->
                    ByteBuffer(1500).use { buffer ->
                        val m = MutableInetSocketAddress()
                        while (isActive) {
                            buffer.clear()
                            if (socket.read(buffer, m).isNotAvailable) {
                                logger.info("Read 0")
                                continue
                            }
                            buffer.flip()
                            logger.info("Was read ${buffer.remaining} bytes")
                            val len = networkManager.bindUdp(InetSocketAddress.resolve("0.0.0.0", 0)).use { client ->
                                println("--->${client.port}")
                                logger.info("Send ${buffer.remaining} bytes")
                                val writeLen = client.write(buffer, dnsServer)
                                buffer.clear()
                                logger.info("Waiting response... writeLen=$writeLen")
                                val l = client.read(buffer, MutableInetSocketAddress())
                                buffer.flip()
                                logger.info("Response of DNS $l")
                                l
                            }
                            val r = buffer.holdState {
                                DnsRecord.read(it)
                            }
                            buffer.clear()
                            r.write(buffer)
                            buffer.flip()
                            socket.write(buffer, m)
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}

fun bgJob(func: suspend CoroutineScope.() -> Unit) {
    var job: Job? = null
    val networkManager = inject<NetworkManager>()
    BeanLifeCycle.postConstruct {
        job = GlobalScope.launch(networkManager.service) {
            func()
        }
    }
    BeanLifeCycle.preDestroy {
        runCatching { job?.cancel() }
    }
}
 */