alter table config_snapshot add column source_sha256 varchar(64);

create table snapshot_publish_request (
  service_group_id varchar(64) primary key references service_group(id) on delete cascade,
  requested_at timestamptz not null,
  requested_by varchar(128) not null,
  reason varchar(512) not null,
  attempts integer not null default 0,
  next_attempt_at timestamptz not null,
  last_error varchar(2048)
);

create index idx_snapshot_publish_due on snapshot_publish_request(next_attempt_at);
