create schema if not exists operational;

create table operational.snapshot_runs (
    id uuid primary key,
    captured_at timestamptz not null default now(),
    record_count integer not null check (record_count >= 0),
    equipment_count integer not null check (equipment_count >= 0)
);

create table operational.records (
    grm_record_id text primary key,
    equipment_tag text not null default '',
    reason_name text not null default '',
    start_date timestamptz,
    end_date timestamptz,
    payload jsonb not null,
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    last_snapshot_id uuid not null references operational.snapshot_runs(id)
);

create index records_equipment_start_idx
    on operational.records (equipment_tag, start_date desc);

alter table operational.snapshot_runs enable row level security;
alter table operational.records enable row level security;

revoke all on schema operational from public, anon, authenticated;
revoke all on all tables in schema operational from public, anon, authenticated;
