package com.jh.proj.coroutineviz.auth

import io.ktor.server.config.ApplicationConfig
import java.security.MessageDigest

/**
 * In-memory SHA-256 API-key store (AUTH-02, ADR-016 Phase A).
 *
 * Keys are NEVER held in plaintext: each [KeyEntry] carries the SHA-256 hex of the
 * raw key (env-interpolated config). [validate] hashes the incoming raw key and
 * constant-time compares (via [MessageDigest.isEqual]) against every stored hash,
 * defeating timing side-channels. Multiple keys are supported for zero-downtime rotation.
 */
class ApiKeyStore(private val keys: List<KeyEntry>) {
    data class KeyEntry(val name: String, val sha256Hash: String, val role: Role)

    /** True when no keys are configured — used by the fail-open auth toggle (D-04a). */
    val isEmpty: Boolean get() = keys.isEmpty()

    /**
     * Returns the [KeyEntry] whose stored SHA-256 hex matches [rawKey], or null.
     * Comparison is constant-time over the hex bytes; the loop is not short-circuited
     * by a faster non-equal comparison because [MessageDigest.isEqual] is length-and-content
     * constant-time per candidate.
     */
    fun validate(rawKey: String): KeyEntry? {
        if (keys.isEmpty()) return null
        val digestHex = sha256Hex(rawKey)
        val candidate = digestHex.toByteArray(Charsets.US_ASCII)
        return keys.firstOrNull { entry ->
            MessageDigest.isEqual(entry.sha256Hash.lowercase().toByteArray(Charsets.US_ASCII), candidate)
        }
    }

    companion object {
        fun sha256Hex(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Build a store from the `auth.keys` config list plus the legacy `auth.apiKey`
         * single-key var (back-compat). The legacy key is supplied as the RAW key
         * (historical behavior), so it is hashed here and added as a RUNNER entry.
         */
        fun fromConfig(config: ApplicationConfig): ApiKeyStore {
            val entries = mutableListOf<KeyEntry>()

            config.configList("auth.keys").forEach { keyConfig ->
                val hash = keyConfig.propertyOrNull("hash")?.getString()?.takeIf { it.isNotBlank() }
                val name = keyConfig.propertyOrNull("name")?.getString() ?: "key"
                val role = Role.fromConfig(keyConfig.propertyOrNull("role")?.getString())
                if (hash != null) {
                    entries += KeyEntry(name = name, sha256Hash = hash, role = role)
                }
            }

            // Legacy back-compat: auth.apiKey holds a RAW key; hash it so the comparison
            // path is uniform. Only honoured when no structured keys are present, to keep
            // the new config authoritative.
            val legacy = config.propertyOrNull("auth.apiKey")?.getString()?.takeIf { it.isNotBlank() }
            if (legacy != null) {
                entries += KeyEntry(name = "legacy", sha256Hash = sha256Hex(legacy), role = Role.RUNNER)
            }

            return ApiKeyStore(entries)
        }
    }
}
