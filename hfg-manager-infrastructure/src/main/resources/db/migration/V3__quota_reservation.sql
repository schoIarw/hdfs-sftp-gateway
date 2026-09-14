create table quota_reservation (
 id uuid primary key, user_id uuid not null references ftp_user(id) on delete cascade, direction varchar(16) not null,
 window_start timestamptz not null, reserved_files bigint not null, reserved_bytes bigint not null,
 status varchar(16) not null, expires_at timestamptz not null, created_at timestamptz not null, committed_at timestamptz
);
create index idx_quota_reservation_expiry on quota_reservation(status,expires_at);
