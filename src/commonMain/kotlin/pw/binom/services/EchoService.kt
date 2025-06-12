package pw.binom.services

import kotlinx.coroutines.*
import pw.binom.io.ByteBuffer
import pw.binom.io.StreamClosedException
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.use
import pw.binom.logger.Logger
import pw.binom.logger.info
import pw.binom.network.NetworkManager
import pw.binom.network.SocketClosedException
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject

class EchoService {
    private val nm by inject<NetworkManager>()
    private var job: Job? = null
    private val logger by Logger.ofThisOrGlobal

    init {
        BeanLifeCycle.postConstruct {
            job = GlobalScope.launch(nm) {
                nm.bindTcp(InetSocketAddress.resolve("0.0.0.0", 8055)).use { server ->
                    while (isActive) {
                        val client = server.accept()
                        GlobalScope.launch(nm) {
                            try {
                                ByteBuffer(1000).use { buf ->
                                    while (isActive) {
                                        try {
                                            buf.clear()
                                            if (client.read(buf).isNotAvailable) {
                                                logger.info("Connection closed!")
                                                break
                                            }
                                            buf.flip()
                                            client.write(buf)
                                        } catch (e: StreamClosedException) {
                                            break
                                        } catch (e: SocketClosedException) {
                                            break
                                        } catch (e: CancellationException) {
                                            break
                                        } catch (e: Throwable) {
                                            e.printStackTrace()
                                            break
                                        }
                                    }
                                }
                            } finally {
                                logger.info("TCP client finished!")
                            }
                        }
                    }
                }
            }
        }
        BeanLifeCycle.preDestroy {
            runCatching { job?.cancelAndJoin() }
        }
    }
}