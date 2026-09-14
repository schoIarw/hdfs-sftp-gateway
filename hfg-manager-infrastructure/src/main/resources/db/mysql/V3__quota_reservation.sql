create table quota_reservation (
 id char(36) primary key, user_id char(36) not null references ftp_user(id) on delete cascade, direction varchar(16) not null,
 window_start datetime(6) not null, reserved_files bigint not null, reserved_bytes bigint not null,
 status varchar(16) not null, expires_at datetime(6) not null, created_at datetime(6) not null, committed_at datetime(6)
);
create index idx_quota_reservation_expiry on quota_reservation(status,expires_at);
