//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.result

/**
 * A generic class that holds a value with its loading status.
 */
sealed class Result<out T> {
    data class Success<T>(
        val data: T,
    ) : Result<T>()

    data class Error(
        val exception: Throwable,
    ) : Result<Nothing>()

    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = (this as? Success)?.data

    fun getOrThrow(): T =
        when (this) {
            is Success -> data
            is Error -> throw exception
            is Loading -> error("Result is still loading")
        }

    fun <R> map(transform: (T) -> R): Result<R> =
        when (this) {
            is Success -> Success(transform(data))
            is Error -> this
            is Loading -> this
        }

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
    } catch (e: Exception) {
        // detekt:ignore:TooGenericExceptionCaught
        Result.Error(e)
    }

/**
 * Execute a suspending block and wrap the result.
 */
suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (e: Exception) {
        // detekt:ignore:TooGenericExceptionCaught
        Result.Error(e)
    }
