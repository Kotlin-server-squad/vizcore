package com.jh.proj.coroutineviz.auth

import com.password4j.Password
import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUTH-03 config-seeded user resolution. Regression for F1 (2026-06-22): an
 * Argon2id PHC hash starts with `$argon2…`, which Ktor's YAML loader cannot carry
 * (it treats `$`-leading values as env-var refs, no escape), so seeded-user login
 * was impossible via `application.yaml`. [UserStore.fromConfig] now also accepts a
 * Base64-encoded hash (`passwordHashB64`) which contains no `$`.
 */
class UserStoreTest {
    @Test
    fun `fromConfig decodes a base64 password hash that verifies with password4j`() {
        val phc = Password.hash("vizcore123").withArgon2().result
        val b64 = Base64.getEncoder().encodeToString(phc.toByteArray())
        val config =
            MapApplicationConfig(
                "auth.users.size" to "1",
                "auth.users.0.username" to "alice",
                "auth.users.0.passwordHashB64" to b64,
                "auth.users.0.role" to "runner",
            )

        val user = UserStore.fromConfig(config).find("alice")
        assertNotNull(user)
        assertEquals(Role.RUNNER, user.role)
        // Decoded back to the exact PHC string, and the token endpoint's check passes.
        assertEquals(phc, user.passwordHash)
        assertTrue(Password.check("vizcore123", user.passwordHash).withArgon2())
        assertFalse(Password.check("wrong-password", user.passwordHash).withArgon2())
    }

    @Test
    fun `fromConfig still accepts a raw passwordHash (programmatic path unchanged)`() {
        val phc = Password.hash("pw").withArgon2().result
        val config =
            MapApplicationConfig(
                "auth.users.size" to "1",
                "auth.users.0.username" to "bob",
                "auth.users.0.passwordHash" to phc,
                "auth.users.0.role" to "viewer",
            )

        val user = UserStore.fromConfig(config).find("bob")
        assertNotNull(user)
        assertTrue(Password.check("pw", user.passwordHash).withArgon2())
    }

    @Test
    fun `fromConfig drops entries with blank username or hash (inert default)`() {
        val config =
            MapApplicationConfig(
                "auth.users.size" to "1",
                "auth.users.0.username" to "",
                "auth.users.0.passwordHashB64" to "",
            )
        val store = UserStore.fromConfig(config)
        assertTrue(store.isEmpty)
        assertNull(store.find("anyone"))
    }
}
