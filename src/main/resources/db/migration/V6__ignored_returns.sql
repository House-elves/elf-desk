-- An ignored item comes back when someone requests your review or mentions you after you ignored it.
alter table ignored_item add column returned_at timestamptz;
alter table ignored_item add column returned_reason varchar(200);
