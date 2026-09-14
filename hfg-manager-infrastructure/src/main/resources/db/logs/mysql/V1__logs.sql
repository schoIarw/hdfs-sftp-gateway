create table logs (
  log_date date not null, record_type varchar(16) not null, log_id char(36) not null,
  transfer_id char(36), user_id char(36) not null, username varchar(64) not null,
  protocol varchar(16), direction varchar(16) not null, status varchar(32) not null,
  virtual_path varchar(600), file_name varchar(600), file_size_bytes bigint not null default 0,
  started_at datetime(6), ended_at datetime(6), duration_millis bigint,
  average_bytes_per_second bigint, gateway_id varchar(128), client_address varchar(128),
  error_code varchar(64), correlation_id varchar(128), window_start datetime(6), window_end datetime(6),
  completed_files bigint, completed_bytes bigint, reserved_files bigint, reserved_bytes bigint,
  file_limit bigint, byte_limit bigint, quota_reached boolean,
  created_at datetime(6) not null, updated_at datetime(6) not null,
  primary key(log_date,record_type,log_id),
  index idx_logs_user_time(user_id,updated_at desc), index idx_logs_transfer(transfer_id),
  index idx_logs_type_time(record_type,updated_at desc), index idx_logs_direction_time(direction,updated_at desc)
) partition by range columns(log_date) (
  partition p_seed values less than ('1970-01-02'),
  partition p_future values less than (maxvalue)
);
