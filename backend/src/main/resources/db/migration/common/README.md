# Flyway migrations

`DatabaseFactory` runs `classpath:db/migration/common` plus the folder for the connected database,
`classpath:db/migration/h2` or `classpath:db/migration/postgresql` (chosen from the JDBC metadata; plain
Flyway has no `{vendor}` placeholder, that is Spring Boot's).

- `common/`: migrations whose SQL runs unchanged on H2 and PostgreSQL. Use `text`, not `clob`
  (stock PostgreSQL has no `clob`). New migrations belong here unless they cannot be written portably.
- `h2/`, `postgresql/`: one copy per vendor for a migration that cannot be portable, with the same
  version and name in each folder. Never a version in `common/` and a vendor folder at once: Flyway
  would find it twice.

V1 is vendor-specific: it was written with `clob`, which PostgreSQL rejects. Its H2 copy is the
original file, byte for byte, so H2 databases that already applied it still validate.

`PostgresMigrationTest` runs every migration on a real PostgreSQL when `VIZCORE_PG_URL` is set
(CI does); `MigrationLayoutTest` guards this layout.
