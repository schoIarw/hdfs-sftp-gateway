alter table directory_mapping add column provisioning_status varchar(32) not null default 'PENDING';
alter table directory_mapping add column provisioning_error varchar(2048);
alter table directory_mapping add column provisioned_at timestamptz;
create index idx_directory_provisioning on directory_mapping(provisioning_status) where auto_create=true;
