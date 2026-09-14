create table hdfs_cluster (
  id varchar(64) primary key, name varchar(128) not null unique, default_fs varchar(512) not null,
  nameservice varchar(128), kerberos_enabled boolean not null default false,
  principal varchar(255), keytab_secret_ref varchar(512), config_resource_refs text,
  status varchar(32) not null default 'ENABLED', created_at datetime(6) not null, updated_at datetime(6) not null
);

create table service_group (
  id varchar(64) primary key, name varchar(128) not null unique, vip varchar(128) not null unique,
  hdfs_cluster_id varchar(64) not null references hdfs_cluster(id), status varchar(32) not null default 'ENABLED',
  created_at datetime(6) not null, updated_at datetime(6) not null
);

create table gateway_node (
  id varchar(128) primary key, service_group_id varchar(64) not null references service_group(id),
  hostname varchar(255) not null, role varchar(16) not null, management_address varchar(512) not null,
  software_version varchar(64), snapshot_version bigint not null default 0, last_heartbeat_at datetime(6),
  status varchar(32) not null default 'UNKNOWN', created_at datetime(6) not null, updated_at datetime(6) not null,
  unique(service_group_id,hostname)
);

create table ftp_user (
  id char(36) primary key, username varchar(64) not null unique, password_hash varchar(255) not null,
  department varchar(128), business_domain varchar(128), phone varchar(64), email varchar(255), note varchar(1024),
  status varchar(32) not null, service_group_id varchar(64) not null references service_group(id),
  hdfs_effective_user varchar(128), expires_at datetime(6), created_at datetime(6) not null,
  updated_at datetime(6) not null, revision bigint not null default 0
);

create index idx_ftp_user_group_status on ftp_user(service_group_id,status);
create index idx_ftp_user_department on ftp_user(department);
create index idx_ftp_user_business_domain on ftp_user(business_domain);

create table ssh_public_key (
  id char(36) primary key, user_id char(36) not null references ftp_user(id) on delete cascade,
  fingerprint varchar(255) not null, public_key text not null, note varchar(512), created_at datetime(6) not null,
  unique(user_id,fingerprint)
);

create table directory_mapping (
  id char(36) primary key, name varchar(128) not null, virtual_path varchar(600) not null,
  hdfs_path varchar(600) not null, hdfs_cluster_id varchar(64) not null references hdfs_cluster(id),
  auto_create boolean not null default true, namespace_quota bigint not null default -1,
  space_quota_bytes bigint not null default -1, status varchar(32) not null default 'ENABLED',
  created_at datetime(6) not null, updated_at datetime(6) not null,
  unique(hdfs_cluster_id,hdfs_path), unique(name,virtual_path)
);

create table directory_grant (
  id char(36) primary key, user_id char(36) not null references ftp_user(id) on delete cascade,
  directory_mapping_id char(36) not null references directory_mapping(id) on delete cascade,
  access_mode varchar(32) not null, created_at datetime(6) not null,
  unique(user_id,directory_mapping_id)
);

create table traffic_policy (
  user_id char(36) primary key references ftp_user(id) on delete cascade,
  upload_bytes_per_second bigint not null default 0, download_bytes_per_second bigint not null default 0,
  upload_burst_bytes bigint not null default 0, download_burst_bytes bigint not null default 0,
  max_connections integer not null default 0, max_upload_transfers integer not null default 0,
  max_download_transfers integer not null default 0, period varchar(16) not null default 'DAY',
  period_upload_files bigint not null default 0, period_download_files bigint not null default 0,
  period_upload_bytes bigint not null default 0, period_download_bytes bigint not null default 0,
  time_zone varchar(64) not null default 'UTC', updated_at datetime(6) not null
);

create table usage_window (
  user_id char(36) not null references ftp_user(id) on delete cascade, direction varchar(16) not null,
  window_start datetime(6) not null, window_end datetime(6) not null, reserved_files bigint not null default 0,
  completed_files bigint not null default 0, reserved_bytes bigint not null default 0, completed_bytes bigint not null default 0,
  revision bigint not null default 0, primary key(user_id,direction,window_start)
);

create table config_snapshot (
  id char(36) primary key, service_group_id varchar(64) not null references service_group(id), version bigint not null,
  payload_json text not null, payload_sha256 varchar(64) not null, signature text not null,
  status varchar(32) not null, created_by varchar(128) not null, created_at datetime(6) not null,
  unique(service_group_id,version)
);

create table transfer_event (
  transfer_id char(36) not null, event_sequence bigint not null, user_id char(36) not null,
  protocol varchar(16) not null, direction varchar(16) not null, status varchar(32) not null,
  virtual_path varchar(600), bytes bigint not null default 0, gateway_id varchar(128),
  client_address varchar(128), error_code varchar(64), correlation_id varchar(128), occurred_at datetime(6) not null,
  primary key(transfer_id,event_sequence)
);

create index idx_transfer_user_time on transfer_event(user_id,occurred_at desc);
create index idx_transfer_status_time on transfer_event(status,occurred_at desc);

create table alert_rule (
  id char(36) primary key, name varchar(128) not null unique, promql text not null, duration_seconds integer not null,
  severity varchar(32) not null, labels_json text not null, annotations_json text not null,
  enabled boolean not null default true, created_at datetime(6) not null, updated_at datetime(6) not null
);

create table audit_log (
  id char(36) primary key, actor varchar(128) not null, action varchar(128) not null, resource_type varchar(64) not null,
  resource_id varchar(128), correlation_id varchar(128), source_address varchar(128),
  before_json text, after_json text, occurred_at datetime(6) not null
);

create index idx_audit_time on audit_log(occurred_at desc);
create index idx_audit_resource on audit_log(resource_type,resource_id);
