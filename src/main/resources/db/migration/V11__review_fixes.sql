-- A self-review says how many fixes it found, so the desk can offer to make them.
alter table job add column fixes integer;
