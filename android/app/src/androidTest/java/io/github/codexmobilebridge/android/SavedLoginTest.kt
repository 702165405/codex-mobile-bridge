package io.github.codexmobilebridge.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedLoginTest {
    @Test fun credentialsAreEncryptedIsolatedRestoredAndRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AppStore(context)
        val first = "saved-login-fixture-a"
        val second = "saved-login-fixture-b"
        val login = SavedLogin("fixture-admin", "fixture-private-password")
        try {
            store.saveLogin(first, login)
            store.saveLogin(second, SavedLogin("other-admin", "other-password"))
            val encoded = context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("login:$first", null)!!
            assertFalse(encoded.contains(login.password))
            assertFalse(encoded.contains(login.username))
            assertEquals(login, AppStore(context).savedLogin(first))
            assertEquals("other-admin", store.savedLogin(second)?.username)
            store.saveLogin(first, null)
            assertNull(AppStore(context).savedLogin(first))
            assertNotNull(store.savedLogin(second))
            store.removeConnection(second)
            assertNull(AppStore(context).savedLogin(second))
        } finally {
            store.saveLogin(first, null)
            store.saveLogin(second, null)
        }
    }
}
