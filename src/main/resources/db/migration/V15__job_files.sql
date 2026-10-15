-- Files the agent left for you in its $ELF_FILES (screenshots, logs): sent by the runner when the job ends,
-- shown with the agent's answer. The answer's turn records its job, to find them.
create table job_file (
    id           bigserial   primary key,
    job_id       bigint      not null references job (id) on delete cascade,
    name         text        not null,
    content_type text        not null,
    size         integer     not null,
    data         bytea       not null,
    created      timestamptz not null default now(),
    unique (job_id, name)
);

alter table turn add column job_id bigint;
