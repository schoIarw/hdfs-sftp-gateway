create table alert_channel (
 id uuid primary key, name varchar(128) not null unique, channel_type varchar(32) not null,
 config_secret_ref varchar(1024) not null, enabled boolean not null default true,
 created_at timestamptz not null, updated_at timestamptz not null
);
create table alert_rule_channel (
 alert_rule_id uuid not null references alert_rule(id) on delete cascade,
 alert_channel_id uuid not null references alert_channel(id) on delete cascade,
 primary key(alert_rule_id,alert_channel_id)
);
