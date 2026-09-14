alter table ftp_user drop column hdfs_effective_user;
alter table hdfs_cluster add column bundle_path varchar(1024);
alter table hdfs_cluster add column bundle_sha256 varchar(64);
alter table gateway_node add column ip_address varchar(128);
alter table gateway_node add column ftp_port integer;
alter table gateway_node add column sftp_port integer;
alter table gateway_node add column management_port integer;

create table gateway_certificate (
  id uuid primary key, gateway_id varchar(128) not null,
  service_group_id varchar(64) not null references service_group(id),
  serial_number varchar(128) not null unique, fingerprint_sha256 varchar(64) not null,
  not_before timestamptz not null, not_after timestamptz not null,
  status varchar(32) not null default 'ACTIVE', created_by varchar(128) not null,
  created_at timestamptz not null
);
