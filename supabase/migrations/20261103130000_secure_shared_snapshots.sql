-- Apply after the two existing migrations. No operational GRM writes are introduced.
begin;
alter table operational.snapshot_runs add column payload jsonb;
create index snapshot_runs_captured_idx on operational.snapshot_runs (captured_at desc);

create table operational.poll_state (
    singleton boolean primary key default true check (singleton),
    attempted_at timestamptz,
    failed boolean not null default false
);
insert into operational.poll_state(singleton) values (true);

create table operational.revoked_sessions (
    session_id uuid primary key,
    user_id uuid not null,
    revoked_at timestamptz not null default now()
);
create table operational.devices (
    id uuid primary key,
    user_id uuid not null,
    session_id uuid not null,
    token text not null unique,
    preferences jsonb not null default '{}',
    observed_maintenance jsonb,
    last_seen_at timestamptz not null default now(),
    enabled boolean not null default true
);
create index devices_user_idx on operational.devices(user_id);
create table operational.notification_outbox (
    id uuid primary key,
    device_id uuid not null references operational.devices(id) on delete cascade,
    event_key text not null,
    record_id text not null,
    status text not null default 'pending' check (status in ('pending','sending','sent','cancelled')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    sent_at timestamptz,
    unique(device_id,event_key)
);
create index outbox_pending_idx on operational.notification_outbox(next_attempt_at)
    where status in ('pending','sending');

alter table operational.poll_state enable row level security;
alter table operational.revoked_sessions enable row level security;
alter table operational.devices enable row level security;
alter table operational.notification_outbox enable row level security;
revoke all on schema operational from public, anon, authenticated;
revoke all on all tables in schema operational from public, anon, authenticated;
alter default privileges in schema operational revoke all on tables from public, anon, authenticated;
commit;