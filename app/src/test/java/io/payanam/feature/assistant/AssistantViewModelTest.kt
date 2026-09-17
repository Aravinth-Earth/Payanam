//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Pins the ViewModel half of the "Change provider or key" contract: the persisted
 * reconfigure flag must drive the setup surface even while a stale model row exists, the
 * stored key must survive, and a provider failure must stay contained.
 *
 * The store is mocked here for deterministic scheduling; the real-row semantics
 * (flag writes, model single-writer, transactional clear) are covered by
 * [AssistantSettingsStoreTest] against an in-memory Room database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AssistantViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var store: AssistantSettingsStore
    private lateinit var client: OpenCodeGoClient

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        store = mock()
        client = mock()
        whenever(store.fingerprint(any())).thenReturn("-")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AssistantViewModel = AssistantViewModel(store, mock(), mock(), client)

    private suspend fun stubConfig(apiKey: String, model: String, setupRequired: Boolean) {
        whenever(store.loadConfig()).thenReturn(
            StoredAssistantConfig(
                apiKey = apiKey,
                settings = AssistantSettings(model = model),
                setupRequired = setupRequired,
            ),
        )
    }

    @Test
    fun `refresh suppresses the model while reconfigure is pending, even with a stale model row`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = true)
        val viewModel = newViewModel()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.isConfigured)
        assertTrue(state.hasKey)
        assertEquals("", state.settings.model)
    }

    @Test
    fun `refresh resolves configured when the flag is clear`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConfigured)
    }

    @Test
    fun `changeProvider persists the reconfigure request and keeps the stored key`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.changeProvider()
        advanceUntilIdle()
        verify(store).beginReconfigure()
        verify(store, never()).clearProviderConfiguration()
        val state = viewModel.uiState.value
        assertTrue(state.hasKey)
        assertEquals("", state.settings.model)
    }

    @Test
    fun `changeProvider contains a persistence failure`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        whenever(store.beginReconfigure()).doThrow(IllegalStateException("db closed"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.changeProvider()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasKey)
    }

    @Test
    fun `loadModels falls back to the stored key when the draft is empty`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        whenever(client.fetchModels(eq("stored-key"), any())).thenReturn(listOf("m1"))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadModels()
        // The provider call hops through Dispatchers.IO (real threads), so drain the test scheduler
        // until the result lands instead of assuming one advanceUntilIdle is enough.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.modelOptions.isEmpty() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        verify(client).fetchModels(eq("stored-key"), any())
        assertEquals("", viewModel.uiState.value.keyDraft)
    }

    @Test
    fun `a fresh ViewModel observes the reconfigure flag written by changeProvider`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        val first = newViewModel()
        advanceUntilIdle()
        first.changeProvider()
        advanceUntilIdle()
        verify(store).beginReconfigure()
        // The persisting half is proved row-wise in AssistantSettingsStoreTest; here the store
        // mock now stands for the persisted flag as the NEXT ViewModel instance would read it.
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = true)
        val second = newViewModel()
        advanceUntilIdle()
        val state = second.uiState.value
        assertFalse(state.isConfigured)
        assertTrue(state.hasKey)
        assertEquals("", state.settings.model)
    }

    @Test
    fun `redactSecrets replaces every occurrence of a non-empty secret`() {
        val viewModel = newViewModel()
        val redacted = viewModel.redactSecrets("echo echo-token-42 and echo-token-42", "echo-token-42", "")
        assertEquals("echo <redacted> and <redacted>", redacted)
    }

    @Test
    fun `a rejected key surfaces no provider body`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        // doAnswer (not doThrow): Mockito rejects checked exceptions on stubbed methods that do
        // not declare them, and the suspend client never declares AssistantHttpException.
        whenever(client.fetchModels(eq("stored-key"), any())).doAnswer {
            throw AssistantHttpException(AssistantErrorKind.KEY_REJECTED, "body that echoes stored-key")
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadModels()
        // fetchModels throws on Dispatchers.IO (real threads), so drain until the failure landed.
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.error == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        val state = viewModel.uiState.value
        assertEquals(AssistantErrorKind.KEY_REJECTED, state.error)
        assertNull(state.errorDetail)
        assertTrue(state.keyInvalid)
    }

    @Test
    fun `a provider failure redacts the key echo in the banner detail`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "stored-key", model = "m1", setupRequired = false)
        whenever(client.fetchModels(eq("stored-key"), any())).doAnswer {
            throw AssistantHttpException(AssistantErrorKind.SERVER, "upstream rejected stored-key")
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadModels()
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.error == null && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        val state = viewModel.uiState.value
        assertEquals(AssistantErrorKind.SERVER, state.error)
        assertEquals("upstream rejected <redacted>", state.errorDetail)
    }

    @Test
    fun `loadModels without any available key makes no provider call`() = runTest(mainDispatcher) {
        stubConfig(apiKey = "", model = "", setupRequired = false)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadModels()
        advanceUntilIdle()
        verify(client, never()).fetchModels(any(), any())
    }
}
