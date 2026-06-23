package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A single share row (ADR-019 shape: createdBy, accessCount, lastAccessedAt,
 * nullable expiry). `expiresAt = null` means the share never expires.
 */
@OptIn(ExperimentalTime::class)
data class ShareToken(
    val token: String,
    val sessionId: String,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val permission: String,
    val accessCount: Int,
    val lastAccessedAt: Instant?,
)

/**
 * Outcome of resolving a share token on the public read path. Distinguishes the
 * three ADR-019 status codes the route maps to:
 * - [Valid]   → 200 (the row, after access tracking)
 * - [Expired] → 410
 * - [NotFound] → 404 (covers unknown AND revoked, which are indistinguishable by design)
 */
sealed interface ShareResolution {
    data class Valid(val share: ShareToken) : ShareResolution

    data object Expired : ShareResolution

    data object NotFound : ShareResolution
}

/**
 * DB-backed share-token lifecycle (replaces the in-memory prototype
 * `com.jh.proj.coroutineviz.session.ShareTokenService`). All access goes through
 * the Exposed DSL against [SharesTable] — token/sessionId are bound as
 * parameters, never string-concatenated (T-03-14).
 *
 * The `permission` is always `READ_ONLY` (the only [com.jh.proj.coroutineviz]
 * share permission today). `expiresAt` is kept accurate so the retention
 * active-share guard ([com.jh.proj.coroutineviz.persistence.DbRetentionPolicy])
 * stays correct: `expires_at IS NULL` = never-expires = always active.
 */
@OptIn(ExperimentalTime::class)
class ShareService(
    private val db: Database,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val logger = LoggerFactory.getLogger(ShareService::class.java)

    /**
     * Mint a new read-only share for [sessionId]. [expiry] maps to
     * `expiresAt = now + N days`, or null for [ShareExpiry.NEVER] (D-11).
     * `createdBy` is the owner principal id (or "anonymous" when auth is off).
     */
    fun create(
        sessionId: String,
        createdBy: String,
        expiry: ShareExpiry,
    ): ShareToken {
        val now = clock()
        val expiresAt = expiry.days?.let { now.plus(it.days) }
        val token = UUID.randomUUID().toString()

        transaction(db) {
            SharesTable.insert {
                it[SharesTable.token] = token
                it[SharesTable.sessionId] = sessionId
                it[SharesTable.createdBy] = createdBy
                it[SharesTable.createdAt] = now
                it[SharesTable.expiresAt] = expiresAt
                it[SharesTable.permission] = PERMISSION_READ_ONLY
                it[SharesTable.accessCount] = 0
                it[SharesTable.lastAccessedAt] = null
            }
        }
        logger.info("Created share for session {} (expiry={})", sessionId, expiry.code)
        return ShareToken(
            token = token,
            sessionId = sessionId,
            createdBy = createdBy,
            createdAt = now,
            expiresAt = expiresAt,
            permission = PERMISSION_READ_ONLY,
            accessCount = 0,
            lastAccessedAt = null,
        )
    }

    /**
     * Resolve a token for the public read path. A valid resolution increments
     * `access_count` and stamps `last_accessed_at` ATOMICALLY in the same
     * transaction as the read (so concurrent reads cannot lose an increment).
     */
    fun resolve(token: String): ShareResolution =
        transaction(db) {
            val row =
                SharesTable
                    .selectAll()
                    .where { SharesTable.token eq token }
                    .limit(1)
                    .firstOrNull()
                    ?: return@transaction ShareResolution.NotFound

            val expiresAt = row[SharesTable.expiresAt]
            val now = clock()
            if (expiresAt != null && expiresAt <= now) {
                return@transaction ShareResolution.Expired
            }

            // Access tracking: increment count + stamp last-accessed in the same tx.
            val newCount = row[SharesTable.accessCount] + 1
            SharesTable.update({ SharesTable.token eq token }) {
                it[SharesTable.accessCount] = newCount
                it[SharesTable.lastAccessedAt] = now
            }

            ShareResolution.Valid(
                ShareToken(
                    token = row[SharesTable.token],
                    sessionId = row[SharesTable.sessionId],
                    createdBy = row[SharesTable.createdBy],
                    createdAt = row[SharesTable.createdAt],
                    expiresAt = expiresAt,
                    permission = row[SharesTable.permission],
                    accessCount = newCount,
                    lastAccessedAt = now,
                ),
            )
        }

    /** List all shares for [sessionId] (owner view) ordered by creation time. */
    fun listForSession(sessionId: String): List<ShareToken> =
        transaction(db) {
            SharesTable
                .selectAll()
                .where { SharesTable.sessionId eq sessionId }
                .orderBy(SharesTable.createdAt)
                .map { it.toShareToken() }
        }

    /**
     * List shares for [sessionId] owned by [createdBy] (CR-02 ownership scope).
     * A principal only sees the shares it minted on that session — never another
     * tenant's shares. `created_by` is bound as an Exposed parameter (T-03-14).
     */
    fun listForSession(
        sessionId: String,
        createdBy: String,
    ): List<ShareToken> =
        transaction(db) {
            SharesTable
                .selectAll()
                .where { (SharesTable.sessionId eq sessionId) and (SharesTable.createdBy eq createdBy) }
                .orderBy(SharesTable.createdAt)
                .map { it.toShareToken() }
        }

    /**
     * Revoke a share scoped to BOTH [sessionId] and [token] (T-03-16: an owner
     * can only revoke a share on a session they addressed). Returns true when a
     * row was deleted, false when no such share existed.
     */
    fun revoke(
        sessionId: String,
        token: String,
    ): Boolean {
        val removed =
            transaction(db) {
                SharesTable.deleteWhere {
                    (SharesTable.sessionId eq sessionId) and (SharesTable.token eq token)
                }
            }
        if (removed > 0) logger.info("Revoked share {} for session {}", token, sessionId)
        return removed > 0
    }

    /**
     * Revoke a share scoped to [sessionId], [token] AND [createdBy] (CR-02): a
     * non-creator delete matches no row and is a no-op (false → 404), so a
     * principal can only revoke a share it minted. `created_by` is bound as an
     * Exposed parameter (T-03-14).
     */
    fun revoke(
        sessionId: String,
        token: String,
        createdBy: String,
    ): Boolean {
        val removed =
            transaction(db) {
                SharesTable.deleteWhere {
                    (SharesTable.sessionId eq sessionId) and
                        (SharesTable.token eq token) and
                        (SharesTable.createdBy eq createdBy)
                }
            }
        if (removed > 0) logger.info("Revoked share {} for session {} by {}", token, sessionId, createdBy)
        return removed > 0
    }

    companion object {
        const val PERMISSION_READ_ONLY = "READ_ONLY"
    }
}

/** Map a [SharesTable] result row to a [ShareToken]. */
@OptIn(ExperimentalTime::class)
private fun org.jetbrains.exposed.v1.core.ResultRow.toShareToken(): ShareToken =
    ShareToken(
        token = this[SharesTable.token],
        sessionId = this[SharesTable.sessionId],
        createdBy = this[SharesTable.createdBy],
        createdAt = this[SharesTable.createdAt],
        expiresAt = this[SharesTable.expiresAt],
        permission = this[SharesTable.permission],
        accessCount = this[SharesTable.accessCount],
        lastAccessedAt = this[SharesTable.lastAccessedAt],
    )
