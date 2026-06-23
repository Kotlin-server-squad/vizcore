package com.jh.proj.coroutineviz.auth

import io.ktor.server.config.ApplicationConfig

/**
 * Config-seeded user store (AUTH-03, D-01, ADR-016 Phase B).
 *
 * Users are loaded once at startup from `auth.users` (env-interpolated). Each entry
 * carries an Argon2id [passwordHash] (D-02) — NEVER a plaintext password. There is no
 * registration/CRUD surface this phase (Deferred); the token endpoint only reads via [find].
 */
class UserStore(private val users: List<UserEntry>) {
    data class UserEntry(val username: String, val passwordHash: String, val role: Role)

    /** True when no users are configured — feeds the fail-open auth toggle (D-04a). */
    val isEmpty: Boolean get() = users.isEmpty()

    /** Look up a user by exact username, or null. Callers must respond uniformly on null (T-03-07). */
    fun find(username: String): UserEntry? = users.firstOrNull { it.username == username }

    companion object {
        fun fromConfig(config: ApplicationConfig): UserStore {
            val entries =
                configListOrEmpty(config, "auth.users").mapNotNull { userConfig ->
                    val username = userConfig.propertyOrNull("username")?.getString()?.takeIf { it.isNotBlank() }
                    val passwordHash = resolvePasswordHash(userConfig)
                    val role = Role.fromConfig(userConfig.propertyOrNull("role")?.getString())
                    if (username != null && passwordHash != null) {
                        UserEntry(username = username, passwordHash = passwordHash, role = role)
                    } else {
                        null
                    }
                }
            return UserStore(entries)
        }

        /**
         * Resolve a user's Argon2id hash from config. Prefers the raw [passwordHash]
         * (programmatic/test config), and falls back to [passwordHashB64] — a Base64
         * encoding of the same PHC string.
         *
         * The Base64 form exists because an Argon2id PHC hash starts with `$argon2…`,
         * and Ktor's YAML config loader treats any value starting with `$` as an
         * environment-variable reference (with no escape), making the raw hash
         * impossible to supply via `application.yaml` / env interpolation. Base64
         * contains no `$`, so it round-trips cleanly; we decode it back to the PHC
         * string here for `Password.check(...).withArgon2()`.
         */
        private fun resolvePasswordHash(userConfig: ApplicationConfig): String? {
            userConfig.propertyOrNull("passwordHash")?.getString()?.takeIf { it.isNotBlank() }?.let { return it }
            val b64 = userConfig.propertyOrNull("passwordHashB64")?.getString()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
