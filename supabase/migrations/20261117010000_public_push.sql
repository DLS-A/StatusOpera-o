-- Server-only push enrollment. No permissions on GRM, auth.users, or operational snapshots.
create table operational.public_push_devices (
 id uuid primary key, secret_hash text not null,
 token text unique, pending_token text unique, challenge_hash text,
 challenge_expires timestamptz, preferences jsonb not null default '{}',
 enabled boolean not null default false, observed_maintenance jsonb,
 last_seen_at timestamptz not null default now(), created_at timestamptz not null default now()
);
create table operational.public_push_rates (
 peer_hash text not null, bucket bigint not null, attempts int not null default 1,
 primary key(peer_hash,bucket)
);
create table operational.public_push_outbox (
 id uuid primary key, device_id uuid not null references operational.public_push_devices(id) on delete cascade,
 event_key text not null, record_id text not null, status text not null default 'pending',
 attempts int not null default 0, next_attempt_at timestamptz not null default now(),
 created_at timestamptz not null default now(), sent_at timestamptz,
 unique(device_id,event_key)
);
create index public_push_due on operational.public_push_outbox(next_attempt_at) where status='pending';
alter table operational.public_push_devices enable row level security;
alter table operational.public_push_rates enable row level security;
alter table operational.public_push_outbox enable row level security;
revoke all on operational.public_push_devices,operational.public_push_rates,operational.public_push_outbox from public,anon,authenticated;
do $$ begin
 if exists(select 1 from pg_roles where rolname='status_operacional_runtime') then
  grant select,insert,update,delete on operational.public_push_devices,operational.public_push_rates,operational.public_push_outbox to status_operacional_runtime;
 end if;
end $$;