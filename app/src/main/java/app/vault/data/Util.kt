package app.vault.data

import kotlin.coroutines.cancellation.CancellationException

/** Like runCatching, but never swallows coroutine cancellation (which must propagate). */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
