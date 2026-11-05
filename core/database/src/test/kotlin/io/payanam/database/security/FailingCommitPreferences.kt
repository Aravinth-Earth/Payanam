//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Wraps a real [SharedPreferences] and makes ONE commit return `false`, so a code path that treats
 * a failed commit as fatal can be driven deterministically.
 *
 * `SharedPreferences.Editor.commit()` is the only durability boundary in
 * `DatabaseEncryptionManager`: it is the check that throws inside `persistVerifier` and inside
 * `disableBiometricUnlockInternal`, and it is the only way to make those throw without a device.
 *
 * The failure is selected by **the key the pending edit writes**, not by counting commits — an
 * ordinal is an implementation detail that drifts the moment a step is added, and a test that
 * starts failing a *different* commit still compiles and can still go green. The failure is
 * one-shot, so the rollback's own commit succeeds and the test asserts the rollback rather than a
 * broken store.
 */
class FailingCommitPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    private var armedKey: String? = null

    /**
     * Arms the first commit whose pending edits write [key] to return `false`. Every other commit —
     * before and after — behaves normally.
     */
    fun failFirstCommitWriting(key: String) {
        armedKey = key
    }

    override fun edit(): SharedPreferences.Editor = RecordingEditor(delegate.edit(), this)

    /** Whether a commit staging [key] is the armed one; consumes the arming when it is. */
    internal fun shouldFail(key: String): Boolean {
        if (armedKey != key) return false
        armedKey = null
        return true
    }

    /**
     * Delegates to a real editor while recording which keys an edit stages.
     *
     * Every mutator returns THIS wrapper, never the delegate — returning the delegate is the trap
     * that makes the wrapper invisible: `edit().putBoolean(k, v).remove(k).commit()` would then
     * run its `commit` on the raw editor, so the injected failure would never fire while the test
     * still compiled and still looked armed.
     */
    private class RecordingEditor(
        private val delegate: SharedPreferences.Editor,
        private val store: FailingCommitPreferences,
    ) : SharedPreferences.Editor {
        private val staged = mutableSetOf<String>()

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            delegate.putString(key, value)
            staged.add(key)
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            delegate.putStringSet(key, values)
            staged.add(key)
            return this
        }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor {
            delegate.putInt(key, value)
            staged.add(key)
            return this
        }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor {
            delegate.putLong(key, value)
            staged.add(key)
            return this
        }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor {
            delegate.putFloat(key, value)
            staged.add(key)
            return this
        }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor {
            delegate.putBoolean(key, value)
            staged.add(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            delegate.remove(key)
            staged.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            staged.clear()
            return this
        }

        override fun commit(): Boolean {
            val armed = staged.firstOrNull { store.shouldFail(it) }
            staged.clear()
            return if (armed != null) false else delegate.commit()
        }

        override fun apply() {
            staged.clear()
            delegate.apply()
        }
    }
}

/**
 * A [Context] whose `getSharedPreferences` hands back a [FailingCommitPreferences] for the named
 * store, so `DatabaseEncryptionManager` — which resolves its store from
 * `context.applicationContext` — can be constructed against it.
 */
class FailingCommitContext(
    base: Context,
    private val storeName: String,
) : android.content.ContextWrapper(base) {
    /** The wrapped store, so a test can arm the failure it needs. */
    val preferences: FailingCommitPreferences =
        FailingCommitPreferences(base.getSharedPreferences(storeName, Context.MODE_PRIVATE))

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences = if (name == storeName) preferences else super.getSharedPreferences(name, mode)
}
