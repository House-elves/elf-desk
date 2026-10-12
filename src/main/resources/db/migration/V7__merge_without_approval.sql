-- "Merge anyway": a merge you asked for without an approval. It doesn't stop if there's no approval.
alter table merge_watch add column require_approval boolean not null default true;
