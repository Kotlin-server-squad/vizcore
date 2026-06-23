@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.auth

import io.ktor.server.auth.Principal

/**
 * Role-based access tiers (ADR-016). Carried by both credential principals so
 * downstream tenancy (Plan 03) and RBAC can branch on it.
 */
enum class Role {
    VIEWER,
    RUNNER,
    ADMIN,
    ;

    companion object {
        /** Parse a config-supplied role string; defaults to VIEWER for unknown/blank values. */
        fun fromConfig(raw: String?): Role =
            raw?.trim()?.uppercase()?.let { name ->
                entries.firstOrNull { it.name == name }
            } ?: VIEWER
    }
}

/**
 * Principal minted when a request authenticates with a valid `X-API-Key` (AUTH-02).
 * [name] identifies the key entry (used as the tenant id when api-key auth is on).
 */
data class ApiKeyPrincipal(
    val name: String,
    val role: Role,
) : Principal

/**
 * Principal minted when a request authenticates with a valid JWT (AUTH-03).
 * [userId] is the JWT `sub` claim (used as the tenant id when JWT auth is on).
 */
data class UserPrincipal(
    val userId: String,
    val role: Role,
) : Principal
