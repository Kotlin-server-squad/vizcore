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
                config.configList("auth.users").mapNotNull { userConfig ->
                    val username = userConfig.propertyOrNull("username")?.getString()?.takeIf { it.isNotBlank() }
                    val passwordHash =
                        userConfig.propertyOrNull("passwordHash")?.getString()?.takeIf { it.isNotBlank() }
                    val role = Role.fromConfig(userConfig.propertyOrNull("role")?.getString())
                    if (username != null && passwordHash != null) {
                        UserEntry(username = username, passwordHash = passwordHash, role = role)
                    } else {
                        null
                    }
                }
            return UserStore(entries)
        }
    }
}
