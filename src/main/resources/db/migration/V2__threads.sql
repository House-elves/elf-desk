-- One thread per issue/PR: a single resumable Claude session on the runner.
create table thread (
    id             bigserial primary key,
    item_key       varchar(300) not null unique,   -- owner/repo#number
    item_kind      varchar(8)   not null check (item_kind in ('PR', 'ISSUE')),
    title          text         not null,
    url            text         not null,
    claude_session varchar(64),
    created        timestamptz  not null default now(),
    updated        timestamptz  not null default now()
);

-- The conversation as shown in the thread view.
create table turn (
    id        bigserial primary key,
    thread_id bigint      not null references thread (id) on delete cascade,
    role      varchar(8)  not null check (role in ('you', 'agent', 'system')),
    action    varchar(20),
    text      text        not null,
    created   timestamptz not null default now()
);
create index turn_thread on turn (thread_id, id);

-- One agent run. queued = not yet accepted by the runner.
create table job (
    id        bigserial primary key,
    thread_id bigint      not null references thread (id) on delete cascade,
    action    varchar(20) not null,
    prompt    text        not null,
    checkout  text        not null,  -- JSON for the runner: {repo, number, kind, base}
    untrusted text,                  -- outsiders' text for the runner's safety triage (first job only)
    state     varchar(10) not null check (state in ('queued', 'running', 'done', 'failed', 'cancelled')),
    error     text,
    cost_usd  numeric(10, 4),
    created   timestamptz not null default now(),
    started   timestamptz,
    finished  timestamptz
);
create index job_thread on job (thread_id, id);
create index job_open on job (state) where state in ('queued', 'running');

-- Something postable the agent produced. payload is JSON (see Draft.java).
create table draft (
    id         bigserial primary key,
    thread_id  bigint      not null references thread (id) on delete cascade,
    job_id     bigint      references job (id) on delete set null,
    kind       varchar(10) not null check (kind in ('review', 'comment')),
    payload    text        not null,
    state      varchar(10) not null check (state in ('draft', 'posted', 'discarded')),
    posted_url text,
    posted_at  timestamptz,
    created    timestamptz not null default now()
);

-- Append-only audit of every write to GitHub, all made by a click.
create table github_action (
    id         bigserial primary key,
    thread_id  bigint       references thread (id) on delete set null,
    draft_id   bigint       references draft (id) on delete set null,
    kind       varchar(20)  not null,
    request    text         not null,
    result_url text,
    login      varchar(100) not null,
    at         timestamptz  not null default now()
);
