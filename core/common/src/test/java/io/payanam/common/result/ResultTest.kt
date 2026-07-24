//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.result

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.payanam.common.result.runCatching as payanamRunCatching

@RunWith(RobolectricTestRunner::class)
class ResultTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        logger = initLogger()
        logger.d("ResultTest.setup", "Logger initialized for tests")
    }

    @Test
    fun success_getOrNull_returnsValue() {
        val result = Result.Success(42)
        assertThat(result.getOrNull()).isEqualTo(42)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.isError).isFalse()
    }

    @Test
    fun success_getOrThrow_returnsValue() {
        val result = Result.Success("ok")
        assertThat(result.getOrThrow()).isEqualTo("ok")
    }

    @Test
    fun error_getOrNull_returnsNull() {
        val result = Result.Error(IllegalStateException("fail"))
        assertThat(result.getOrNull() == null).isTrue()
    }

    @Test(expected = IllegalStateException::class)
    fun loading_getOrThrow_throws() {
        Result.Loading.getOrThrow()
    }

    @Test(expected = RuntimeException::class)
    fun error_getOrThrow_throwsOriginal() {
        val error = RuntimeException("boom")
        Result.Error(error).getOrThrow()
    }

    @Test
    fun map_transformsSuccess() {
        val result = Result.Success(5).map { it * 2 }
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(result.getOrNull()).isEqualTo(10)
    }

    @Test
    fun map_preservesError() {
        val error = Result.Error(IllegalArgumentException("nope"))
        val mapped = error.map { 1 }
        assertThat(mapped).isInstanceOf(Result.Error::class.java)
    }

    @Test
    fun map_preservesLoading() {
        val mapped = Result.Loading.map { 1 }
        assertThat(mapped).isInstanceOf(Result.Loading::class.java)
    }

    @Test
    fun loading_flags_areTrue() {
        val loading = Result.Loading
        assertThat(loading.isLoading).isTrue()
    }

    @Test
    fun runCatching_wrapsSuccess() {
        val result = payanamRunCatching { "ok" }
        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun runCatching_wrapsError() {
        val result = payanamRunCatching { throw IllegalStateException("fail") }
        assertThat(result).isInstanceOf(Result.Error::class.java)
    }

    @Test
    fun runSuspendCatching_wrapsSuccess() =
        runTest {
            val result = runSuspendCatching { "async" }
            assertThat(result.getOrNull()).isEqualTo("async")
        }

    @Test
    fun runSuspendCatching_wrapsError() =
        runTest {
            val result = runSuspendCatching { throw IllegalStateException("fail") }
            assertThat(result).isInstanceOf(Result.Error::class.java)
        }

    @Test
    fun suspendMap_transformsSuccess() =
        runTest {
            val result = Result.Success(2).suspendMap { it * 5 }
            assertThat(result.getOrNull()).isEqualTo(10)
        }

    @Test
    fun suspendMap_preservesError() =
        runTest {
            val error = Result.Error(IllegalStateException("boom"))
            val mapped = error.suspendMap { "never" }
            assertThat(mapped).isInstanceOf(Result.Error::class.java)
        }

    @Test
    fun suspendMap_preservesLoading() =
        runTest {
            val mapped = Result.Loading.suspendMap { "never" }
            assertThat(mapped).isInstanceOf(Result.Loading::class.java)
        }

    @Test
    fun getOrNull_returnsNullForError() {
        val result = Result.Error(RuntimeException("error"))
        assertThat(result.getOrNull() == null).isTrue()
    }

    @Test
    fun getOrNull_returnsNullForLoading() {
        val result = Result.Loading
        assertThat(result.getOrNull() == null).isTrue()
    }

    @Test
    fun isSuccess_isFalseForError() {
        val result = Result.Error(RuntimeException("error"))
        assertThat(result.isSuccess).isFalse()
    }

    @Test
    fun isError_isFalseForSuccess() {
        val result = Result.Success("data")
        assertThat(result.isError).isFalse()
    }

    @Test
    fun isLoading_isFalseForSuccess() {
        val result = Result.Success("data")
        assertThat(result.isLoading).isFalse()
    }

    @Test
    fun isLoading_isFalseForError() {
        val result = Result.Error(RuntimeException("error"))
        assertThat(result.isLoading).isFalse()
    }

    @Test
    fun runCatching_catchesRuntimeException() {
        val result = payanamRunCatching { throw IllegalArgumentException("test exception") }
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(result.isError).isTrue()
    }

    @Test
    fun runSuspendCatching_catchesException() =
        runTest {
            val result = runSuspendCatching { throw IllegalArgumentException("suspend error") }
            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat(result.isError).isTrue()
        }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
