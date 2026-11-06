-- V2: health persistence (Part 5b, spec 2026-10-21 C-4/C-5). Portable: runs unchanged on H2 and PostgreSQL.
alter table sessions add column summaries text;
alter table sessions add column summaries_seq bigint;
alter table sessions add column ingest_state text;
alter table sessions add column ingest_open_since bigint;
alter table sessions add column checks text;
