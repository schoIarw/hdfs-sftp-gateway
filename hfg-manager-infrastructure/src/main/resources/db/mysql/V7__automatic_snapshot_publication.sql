alter table config_snapshot add column source_sha256 varchar(64);

create table snapshot_publish_request (
  service_group_id varchar(64) primary key,
  requested_at datetime(6) not null,
  requested_by varchar(128) not null,
  reason varchar(512) not null,
  attempts integer not null default 0,
  next_attempt_at datetime(6) not null,
  last_error varchar(2048),
  constraint fk_snapshot_publish_group foreign key(service_group_id)
    references service_group(id) on delete cascade
);

create index idx_snapshot_publish_due on snapshot_publish_request(next_attempt_at);
