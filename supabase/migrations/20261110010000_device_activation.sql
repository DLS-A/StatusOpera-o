-- Team-code activation replaces interactive OAuth for this deployment.
create table operational.activated_devices (
    id uuid primary key,
    token_hash text not null unique check (length(token_hash)=64),
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);
create table operational.activation_attempts (
    scope text primary key check (length(scope)=64),
    window_start timestamptz not null,
    attempts integer not null check (attempts>0)
);
alter table operational.activated_devices enable row level security;
alter table operational.activation_attempts enable row level security;
revoke all on operational.activated_devices, operational.activation_attempts from public, anon, authenticated;