drop index operational.records_equipment_start_idx;
create index records_last_snapshot_id_idx on operational.records (last_snapshot_id);
