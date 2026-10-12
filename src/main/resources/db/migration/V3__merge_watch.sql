-- "Merge on green": PRs the desk merges (through the runner) once CI passes.
-- head_sha is the head you saw when you clicked; the merge is pinned to it.
create table merge_watch (
    id         bigserial primary key,
    item_key   varchar(300) not null,
    method     varchar(10)  not null check (method in ('merge', 'squash', 'rebase')),
    head_sha   varchar(40)  not null,
    state      varchar(10)  not null check (state in ('waiting', 'merged', 'failed', 'cancelled')),
    outcome    text,
    login      varchar(100) not null,
    created    timestamptz  not null default now(),
    checked    timestamptz,
    finished   timestamptz
);
-- At most one waiting watch per PR.
create unique index merge_watch_waiting on merge_watch (item_key) where state = 'waiting';
