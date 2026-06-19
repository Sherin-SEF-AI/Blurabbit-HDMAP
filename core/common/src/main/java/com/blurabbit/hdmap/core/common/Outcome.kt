package com.blurabbit.hdmap.core.common

/**
 * Lightweight result type used across module boundaries to make failure explicit without
 * leaking exceptions through clean-architecture layers.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: Throwable, val message: String? = error.message) : Outcome<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    companion object {
        inline fun <T> catching(block: () -> T): Outcome<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(t)
        }
    }
}
