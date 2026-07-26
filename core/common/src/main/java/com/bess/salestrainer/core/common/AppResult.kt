package com.bess.salestrainer.core.common

/** Lightweight result type for expected business failures (avoid leaking impl exceptions). */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

data class AppError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)

inline fun <T> AppResult<T>.orElse(fallback: (AppError) -> T): T = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> fallback(error)
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}
