alter table operational.edge_state add column if not exists payload_gzip text, add column if not exists history_gzip text;
