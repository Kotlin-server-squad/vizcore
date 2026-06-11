# ADR-016: Authentication Architecture

## Status
Accepted

## Date
2026-03-18

## Context
The backend currently has no authentication or authorization. Every endpoint is publicly accessible. Before any non-local deployment, we need API protection to prevent unauthorized session creation, data access, and scenario execution. The solution must work for both the web frontend and programmatic API consumers (CLI tools, IntelliJ plugin, CI integrations).

## Decision
Implement authentication in two phases, both using the `ktor-server-auth` plugin family.

### Phase A: API Key Authentication
Minimum viable security for initial deployments.

- Clients send `X-API-Key` header with every request
- Backend validates against an in-memory key store loaded from environment variables
- Keys are stored as SHA-256 hashes (never plaintext in memory)
- Multiple keys supported to allow rotation without downtime

```kotlin
install(Authentication) {
    apiKey("api-key") {
        headerName = "X-API-Key"
        validate { key ->
            keyStore.validate(key)?.let { ApiKeyPrincipal(it.name, it.role) }
        }
    }
}
```

Configuration:
```yaml
auth:
  type: api-key
  keys:
    - name: "frontend"
      hash: "${API_KEY_HASH_FRONTEND}"
      role: runner
    - name: "ci-tool"
      hash: "${API_KEY_HASH_CI}"
      role: viewer
```

Once ADR-015 persistence is implemented, keys migrate to the database with CRUD management endpoints.

### Phase B: JWT Authentication
Full user-based auth for multi-tenant deployments.

- Use `ktor-server-auth-jwt` with HMAC-SHA256 signing (RS256 for production)
- Token endpoint: `POST /api/auth/token` accepts credentials, returns JWT
- JWT claims: `sub` (user ID), `role` (viewer/runner/admin), `exp`, `iat`
- Refresh tokens with 7-day expiry stored server-side

```kotlin
data class UserPrincipal(
    val userId: String,
    val role: Role
) : Principal

enum class Role { VIEWER, RUNNER, ADMIN }
```

### Role Permissions
| Action | VIEWER | RUNNER | ADMIN |
|---|---|---|---|
| List/view sessions | own | own | all |
| Run scenarios | no | yes | yes |
| Delete sessions | no | own | all |
| Manage users/keys | no | no | yes |

### Tenant Isolation
All session queries are filtered by the authenticated user's ID. A `RUNNER` can only see and modify their own sessions. `ADMIN` bypasses the filter.

```kotlin
fun SessionStoreInterface.forUser(principal: UserPrincipal): SessionStoreInterface =
    if (principal.role == Role.ADMIN) this
    else FilteredSessionStore(this, principal.userId)
```

### Unauthenticated Endpoints
The following remain publicly accessible:
- `GET /health` — load balancer health checks
- `GET /openapi.json` — API documentation
- `POST /api/auth/token` — login endpoint (Phase B)

### Frontend Integration
The React frontend stores the API key or JWT in memory (not localStorage) and attaches it via an Axios/fetch interceptor. On 401 response, redirect to a login page (Phase B) or show an API key prompt (Phase A).

## Alternatives Considered

### OAuth2 / OpenID Connect
Full OAuth2 with authorization code flow is the industry standard but requires an identity provider (Keycloak, Auth0) and significant configuration. Overkill for an internal/developer tool at this stage. Can be added later as a Phase C behind the existing JWT infrastructure.

### Session Cookies
Traditional cookie-based sessions work well for browser clients but are awkward for API-first tools (CLI, IntelliJ plugin, CI). CSRF protection adds complexity. Header-based auth is more universal.

### No Auth (Rely on Network Security)
Running behind a VPN or private network avoids application-level auth but provides no user isolation, no audit trail, and breaks the session sharing model.

## Consequences

### Positive
- Phased approach delivers security quickly (Phase A) while planning for full user management (Phase B)
- API key auth is simple for CI/CD and plugin integrations
- Role-based access control enables safe multi-tenant deployment
- Tenant isolation ensures users only see their own data
- Unauthenticated health/docs endpoints support standard infrastructure patterns

### Negative
- Two auth mechanisms to maintain during the Phase A-to-B transition
- API key rotation requires redeployment in Phase A (mitigated by moving to DB)
- JWT secret management requires secure deployment configuration
- Frontend must handle auth state and token refresh, adding UI complexity

## Related
- ADR-015: Persistence Strategy (key store migration to database)

## See also
- ADR-019: Session Sharing builds on this auth model — share tokens operate outside user auth by design. The dependency runs one way (ADR-019 → ADR-016); this is an advisory back-reference, not a dependency edge.
