package pw.binom.services

import kotlinx.coroutines.*
import pw.binom.atomic.AtomicBoolean
import pw.binom.concurrency.SpinLock
import pw.binom.concurrency.synchronize
import pw.binom.http.client.HttpClientRunnable
import pw.binom.io.AsyncCloseable
import pw.binom.io.httpClient.HttpClient
import pw.binom.io.socket.InetAddress
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.useAsync
import pw.binom.network.NetworkManager
import pw.binom.network.tcpConnect
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject
import pw.binom.url.URL
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

@OptIn(DelicateCoroutinesApi::class)
class IpService {
    sealed interface Checker {
        data class Http(
            val interval: Duration,
            val method: String,
            val url: URL,
            val timeout: Duration,
            val responseCode: Int = 200,
        ) : Checker

        data class Tcp(
            val address: InetSocketAddress,
            val interval: Duration,
            val timeout: Duration,
        ) : Checker
    }

    interface Ip {
        val healthy: Boolean
    }

    private interface CheckerImpl : AsyncCloseable {
        val healthy: Boolean
        fun start(context: CoroutineContext = EmptyCoroutineContext)
    }

    private abstract class AbstractChecker : CheckerImpl {
        protected abstract val checkInternal: Duration
        protected abstract val timeout: Duration
        private var job: Job? = null
        protected abstract suspend fun isHealthy(): Boolean
        private val internalHealthy = AtomicBoolean(false)

        final override val healthy: Boolean
            get() = internalHealthy.getValue()

        final override fun start(context: CoroutineContext) {
            job = GlobalScope.launch(context) {
                while (isActive) {
                    val h = try {
                        withTimeout(timeout) {
                            isHealthy()
                        }
                    } catch (e: Exception) {
                        false
                    }
                    internalHealthy.setValue(h)
                    delay(checkInternal)
                }
            }
        }

        override suspend fun asyncClose() {
            runCatching { job?.cancelAndJoin() }
        }
    }

    private class TcpChecker(
        val data: Checker.Tcp,
        val networkManager: NetworkManager,
    ) : AbstractChecker() {
        override val checkInternal: Duration
            get() = data.interval
        override val timeout: Duration
            get() = data.timeout

        override suspend fun isHealthy(): Boolean {
            networkManager.tcpConnect(data.address).asyncClose()
            return true
        }
    }

    private class HttpChecker(
        val data: Checker.Http,
        val client: HttpClientRunnable,
    ) : AbstractChecker() {
        override val checkInternal: Duration
            get() = data.interval
        override val timeout: Duration
            get() = data.timeout

        override suspend fun isHealthy(): Boolean {
            val connection = try {
                client.request(
                    method = data.method,
                    url = data.url,
                ).connect()
            } catch (e: Exception) {
                return false
            }
            return connection.useAsync {
                it.getResponseCode() == data.responseCode
            }
        }

    }

    private class IpHolderImpl(
        val address: InetAddress,
        val checker: CheckerImpl,
    ) : Ip {
        override val healthy: Boolean
            get() = checker.healthy
    }

    private val httpClient by inject<HttpClientRunnable>()
    private val networkManager by inject<NetworkManager>()
    private val ips = HashMap<String, IpHolderImpl>()

    private val lock = SpinLock()

    operator fun get(ip: InetAddress): Ip? = lock.synchronize { ips[ip.host] }
    operator fun get(ip: String): Ip? = lock.synchronize { ips[ip] }

    fun addIp(ip: InetAddress, checker: Checker): Ip? {
        val impl = lock.synchronize {
            if (ips.containsKey(ip.host)) {
                return null
            }
            val checkerImpl = when (checker) {
                is Checker.Tcp -> TcpChecker(data = checker, networkManager = networkManager)
                is Checker.Http -> HttpChecker(data = checker, client = httpClient)
            }
            val ipImpl = IpHolderImpl(address = ip, checkerImpl)
            ips[ip.host] = ipImpl
            ipImpl
        }
        impl.checker.start(context = networkManager)
        return impl
    }

    fun removeIp(ip: InetAddress): Boolean {
        val ipImpl = lock.synchronize {
            ips.remove(ip.host) ?: return false
        }
        GlobalScope.launch(networkManager) {
            ipImpl.checker.asyncCloseAnyway()
        }
        return true
    }

    init {
        BeanLifeCycle.postConstruct {
            val actualIps = lock.synchronize {
                val result = HashSet(ips.values)
                ips.clear()
                result
            }
            actualIps.forEach {
                it.checker.asyncClose()
            }
        }
    }
}