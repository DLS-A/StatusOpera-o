-- Additive isolated state. No anon/authenticated access and no existing data removed.
create table operational.edge_state (
 singleton boolean primary key default true check(singleton),
 payload jsonb, history jsonb not null default '[]',
 attempted_at timestamptz,updated_at timestamptz,failed boolean not null default true,
 history_cursor integer not null default 1 check(history_cursor between 1 and 91),
 history_retry_at timestamptz
);
insert into operational.edge_state(singleton) values(true);
alter table operational.edge_state enable row level security;
revoke all on operational.edge_state from public,anon,authenticated;
-- Role created by private provisioning, scoped only to edge state and push tables.
create policy edge_runtime_state on operational.edge_state to status_operacional_edge using(true) with check(true);
grant usage on schema operational to status_operacional_edge;
grant select,update on operational.edge_state to status_operacional_edge;
do $$ declare t text; begin
 foreach t in array array['public_push_devices','public_push_rates','public_push_outbox'] loop
  execute format('create policy edge_runtime_push on operational.%I to status_operacional_edge using(true) with check(true)',t);
  execute format('grant select,insert,update,delete on operational.%I to status_operacional_edge',t);
 end loop;
end $$;
