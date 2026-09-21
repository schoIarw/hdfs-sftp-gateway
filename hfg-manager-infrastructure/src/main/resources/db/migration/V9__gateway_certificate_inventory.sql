-- 证书清单需要保存已签发的证书正文，节点表记录该节点实际上报的证书指纹，
-- 用于在网关上线时校验证书归属并阻止同一张证书被两台主机共用。
alter table gateway_certificate add column certificate_pem text;
alter table gateway_node add column certificate_fingerprint varchar(64);

create index idx_gateway_certificate_gateway on gateway_certificate(gateway_id,service_group_id);
