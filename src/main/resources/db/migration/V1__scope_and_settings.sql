-- What the board watches. An owner row covers every public repo of that org
-- or user; a repo row adds one repo, or (mode = exclude) removes one repo
-- from a watched owner.
create table watch_scope (
    id         bigserial primary key,
    kind       varchar(8)   not null check (kind in ('owner', 'repo')),
    name       varchar(200) not null,
    mode       varchar(8)   not null check (mode in ('include', 'exclude')),
    added      timestamptz  not null default now(),
    unique (kind, name)
);

create table setting (
    setting_key   varchar(100) primary key,
    setting_value text         not null
);
