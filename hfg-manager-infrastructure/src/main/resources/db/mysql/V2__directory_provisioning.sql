alter table directory_mapping add column provisioning_status varchar(32) not null default 'PENDING';
alter table directory_mapping add column provisioning_error varchar(2048);
alter table directory_mapping add column provisioned_at datetime(6);
create index idx_directory_provisioning on directory_mapping(auto_create,provisioning_status);
