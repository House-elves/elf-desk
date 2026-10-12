-- A "push" draft: commits the agent made in its worktree, waiting for you to push them to the PR's branch.
alter table draft drop constraint draft_kind_check;
alter table draft add constraint draft_kind_check check (kind in ('review', 'comment', 'push'));
