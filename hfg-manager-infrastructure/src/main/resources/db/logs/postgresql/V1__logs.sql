create table logs (
  log_date date not null, record_type varchar(16) not null, log_id uuid not null,
  transfer_id uuid, user_id uuid not null, username varchar(64) not null,
  protocol varchar(16), direction varchar(16) not null, status varchar(32) not null,
  virtual_path varchar(2048), file_name varchar(1024), file_size_bytes bigint not null default 0,
  started_at timestamptz, ended_at timestamptz, duration_millis bigint,
  average_bytes_per_second bigint, gateway_id varchar(128), client_address varchar(128),
  error_code varchar(64), correlation_id varchar(128), window_start timestamptz, window_end timestamptz,
  completed_files bigint, completed_bytes bigint, reserved_files bigint, reserved_bytes bigint,
  file_limit bigint, byte_limit bigint, quota_reached boolean,
  created_at timestamptz not null, updated_at timestamptz not null,
  primary key(log_date,record_type,log_id)
) partition by range(log_date);
create index idx_logs_user_time on logs(user_id,updated_at desc);
create index idx_logs_transfer on logs(transfer_id);
create index idx_logs_type_time on logs(record_type,updated_at desc);
create index idx_logs_direction_time on logs(direction,updated_at desc);
