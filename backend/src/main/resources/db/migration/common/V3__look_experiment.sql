-- V3: the look experiment (plan 2026-11-06-look-experiment). Portable: runs unchanged on H2 and PostgreSQL.
-- Anonymous only: a random browser id, the look, counts and times. No IP, user agent, session or user.
create table look_visitors (
    anon_id varchar(36) primary key,
    entry_look varchar(16),
    entry_kind varchar(8),
    first_seen_at bigint not null
);

create table look_exposure (
    anon_id varchar(36) not null,
    look varchar(16) not null,
    sessions_seen integer not null default 0,
    visible_ms bigint not null default 0,
    primary key (anon_id, look)
);

create table look_switches (
    anon_id varchar(36) not null,
    from_look varchar(16) not null,
    to_look varchar(16) not null,
    times integer not null default 0,
    primary key (anon_id, from_look, to_look)
);

create table look_votes (
    anon_id varchar(36) not null,
    look varchar(16) not null,
    rating integer not null,
    comment text,
    voted_at bigint not null,
    primary key (anon_id, look)
);
