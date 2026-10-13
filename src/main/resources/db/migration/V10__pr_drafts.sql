-- A draft can be a new PR: the agent's fix for an issue, pushed to your fork.
alter table draft drop constraint draft_kind_check;
alter table draft add constraint draft_kind_check check (kind in ('review', 'comment', 'push', 'issue', 'pr'));
