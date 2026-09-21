alter table directory_mapping
  add column owner_user_id char(36),
  add column access_mode varchar(32) not null default 'READ_WRITE',
  add constraint fk_directory_owner foreign key(owner_user_id) references ftp_user(id);

update directory_mapping d
join (
  select g.directory_mapping_id,g.user_id,g.access_mode
  from directory_grant g
  join (
    select directory_mapping_id,min(id) selected_id
    from directory_grant
    group by directory_mapping_id
  ) selected on selected.selected_id=g.id
) ownership on ownership.directory_mapping_id=d.id
set d.owner_user_id=ownership.user_id,d.access_mode=ownership.access_mode;

create index idx_directory_owner on directory_mapping(owner_user_id);
alter table directory_mapping drop index name;
alter table directory_mapping add constraint uq_directory_owner_virtual_path
  unique(owner_user_id,virtual_path);
alter table directory_mapping add constraint chk_directory_access_mode
  check (access_mode in ('READ_ONLY','READ_WRITE'));
drop table directory_grant;

drop table quota_reservation;
drop table usage_window;
alter table traffic_policy drop column upload_burst_bytes;
alter table traffic_policy drop column download_burst_bytes;
alter table traffic_policy drop column max_upload_transfers;
alter table traffic_policy drop column max_download_transfers;
alter table traffic_policy drop column period;
alter table traffic_policy drop column period_upload_files;
alter table traffic_policy drop column period_download_files;
alter table traffic_policy drop column period_upload_bytes;
alter table traffic_policy drop column period_download_bytes;
alter table traffic_policy drop column time_zone;

create table system_setting (
  setting_key varchar(128) primary key,
  setting_value varchar(2048) not null,
  updated_at datetime(6) not null
);

insert into system_setting(setting_key,setting_value,updated_at)
values('monitoring_panel_enabled','true',current_timestamp(6));
