package pw.binom.utils

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun AtomicBoolean.lock() {
    while (true) {
        if (compareAndSet(false, true)) {
            break
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
fun AtomicBoolean.unlock() {
    store(false)
}

@OptIn(ExperimentalAtomicApi::class)
inline fun <T> AtomicBoolean.synchronize(func: () -> T): T {
    lock()
    return try {
        func()
    } finally {
        unlock()
    }
}