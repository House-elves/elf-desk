-- A draft can be a new issue in another repo (for example upstream).
alter table draft drop constraint draft_kind_check;
alter table draft add constraint draft_kind_check check (kind in ('review', 'comment', 'push', 'issue'));
