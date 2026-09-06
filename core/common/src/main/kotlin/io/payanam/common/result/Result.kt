//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.result

/**
 * A generic class that holds a value with its loading status.
 */
sealed class Result<out T> {
    /**
     * Successful result holding [data].
     */
    data class Success<T>(
        val data: T,
    ) : Result<T>()

    /**
     * Failed result holding the thrown [exception].
     */
    data class Error(
        val exception: Throwable,
    ) : Result<Nothing>()

    /**
     * Represents an in-flight or not-yet-completed operation.
     */
    data object Loading : Result<Nothing>()

    /** True when this is a [Success]. */
    val isSuccess: Boolean get() = this is Success
    /** True when this is an [Error]. */
    val isError: Boolean get() = this is Error
    /** True when this is [Loading]. */
    val isLoading: Boolean get() = this is Loading

    /** Returns the success value, or null if this is not [Success]. */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Returns the success value, or throws the held [Error.exception]
     * (or [IllegalStateException] if still [Loading]).
     */
    fun getOrThrow(): T =
        when (this) {
            is Success -> data
            is Error -> throw exception
            is Loading -> error("Result is still loading")
        }

    /**
     * Maps a [Success] value with [transform], leaving [Error]/[Loading] untouched.
     */
    fun <R> map(transform: (T) -> R): Result<R> =
        when (this) {
            is Success -> Success(transform(data))
            is Error -> this
            is Loading -> this
        }

    /**
     * Suspending variant of [map]; maps a [Success] value with the suspend
     * [transform], leaving [Error]/[Loading] untouched.
     */
    suspend fun <R> suspendMap(transform: suspend (T) -> R): Result<R> =
        when (this) {
            is Success -> Success(transform(data))
            is Error -> this
            is Loading -> this
        }
}

/**
 * Execute a block and wrap the result.
 */
inline fun <T> runCatching(block: () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Result.Error(e)
    }

/**
 * Execute a suspending block and wrap the result.
 */
suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Result.Error(e)
    }
