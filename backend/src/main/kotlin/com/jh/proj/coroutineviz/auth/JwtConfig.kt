package com.jh.proj.coroutineviz.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * JWT signing/verification config (AUTH-03, ADR-016 Phase B).
 *
 * Signing algorithm resolution (RESEARCH Open Question 2):
 *   - If `auth.jwt.privateKeyPath`/`publicKeyPath` are set, use RS256 (prod, asymmetric).
 *   - Otherwise, if `auth.jwt.secret` is set, use HMAC256 (dev, symmetric).
 *   - If neither is set, the config is INERT ([isConfigured] == false): no tokens can be
 *     signed or verified, and the jwt provider contributes nothing to the fail-open toggle.
 *
 * Claims minted by [sign]: `sub` (userId), `role`, `iat`, `exp = now + accessTtlMinutes`.
 * The explicit [Algorithm] (never `none`) is the T-03-05 mitigation against alg-confusion.
 *
 * Refresh tokens are deferred this phase (RESEARCH Open Question 1): only short-lived access
 * tokens are issued. A server-side refresh map / persisted refresh store is a later plan's concern.
 */
class JwtConfig private constructor(
    private val algorithm: Algorithm?,
    val issuer: String,
    val audience: String,
    val realm: String,
    private val accessTtlMinutes: Long,
) {
    /** True when a signing/verifying algorithm is available (HMAC secret or RS256 PEMs present). */
    val isConfigured: Boolean get() = algorithm != null

    /** Build the verifier used by the Ktor jwt provider. Null when not configured. */
    fun verifier(): JWTVerifier? =
        algorithm?.let {
            JWT.require(it)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
        }

    /**
     * Mint a signed access token for [userId] with [role]. Throws [IllegalStateException]
     * if the config is inert (callers gate on [isConfigured] / a configured UserStore).
     */
    fun sign(userId: String, role: Role): SignedToken {
        val alg = checkNotNull(algorithm) { "JWT not configured: no secret or RS256 key pair" }
        val now = Instant.now()
        val expiresAt = now.plus(accessTtlMinutes, ChronoUnit.MINUTES)
        val token =
            JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withSubject(userId)
                .withClaim("role", role.name)
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .sign(alg)
        return SignedToken(token = token, expiresAt = expiresAt)
    }

    data class SignedToken(val token: String, val expiresAt: Instant)

    companion object {
        fun fromConfig(config: ApplicationConfig): JwtConfig {
            val jwt = config.config("auth.jwt")
            val secret = jwt.propertyOrNull("secret")?.getString()?.takeIf { it.isNotBlank() }
            val issuer = jwt.propertyOrNull("issuer")?.getString() ?: "coroutineviz"
            val audience = jwt.propertyOrNull("audience")?.getString() ?: "coroutineviz-api"
            val realm = jwt.propertyOrNull("realm")?.getString() ?: "coroutineviz"
            val ttl = jwt.propertyOrNull("accessTtlMinutes")?.getString()?.toLongOrNull() ?: 60L
            val privateKeyPath = jwt.propertyOrNull("privateKeyPath")?.getString()?.takeIf { it.isNotBlank() }
            val publicKeyPath = jwt.propertyOrNull("publicKeyPath")?.getString()?.takeIf { it.isNotBlank() }

            val algorithm: Algorithm? =
                when {
                    privateKeyPath != null && publicKeyPath != null ->
                        Algorithm.RSA256(
                            readRsaPublicKey(publicKeyPath),
                            readRsaPrivateKey(privateKeyPath),
                        )
                    secret != null -> Algorithm.HMAC256(secret)
                    else -> null
                }

            return JwtConfig(
                algorithm = algorithm,
                issuer = issuer,
                audience = audience,
                realm = realm,
                accessTtlMinutes = ttl,
            )
        }

        private fun stripPem(pem: String): ByteArray {
            val base64 =
                pem.lineSequence()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
                    .replace("\\s".toRegex(), "")
            return Base64.getDecoder().decode(base64)
        }

        private fun readRsaPrivateKey(path: String): RSAPrivateKey {
            val der = stripPem(File(path).readText())
            return KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
        }

        private fun readRsaPublicKey(path: String): RSAPublicKey {
            val der = stripPem(File(path).readText())
            return KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        }
    }
}
