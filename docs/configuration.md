# HFG 配置参考

Manager 的管理页面已经打包在 `hfg-manager.jar` 中，和 REST API 共用 `HFG_MANAGER_PORT`；生产运行不需要独立前端配置或 Nginx。Spring Boot 支持环境变量及外部 YAML，推荐将非敏感配置放在 `/etc/hfg/application.yaml`，用下面的方式加载：

```bash
java -jar /opt/hfg/hfg-manager.jar \
  --spring.config.additional-location=file:/etc/hfg/application.yaml
```

本文和 `deploy/env/*.env.example` 采用环境变量作为标准部署接口。systemd 通过 `EnvironmentFile` 加载；手工运行时需先执行 `set -a; source <env-file>; set +a`，否则 shell 中未导出的变量不会传入 Java。

## Gateway 必填项

| 环境变量 | 用途 | 示例 |
|---|---|---|
| HFG_GATEWAY_ID | 全局唯一节点 ID | hfg-gateway-a01 |
| HFG_SERVICE_GROUP_ID | 固定服务组 | group-a |
| HFG_SNAPSHOT_PUBLIC_KEY_BASE64 | Ed25519 X.509 公钥 Base64 | Secret 注入 |
| HFG_HDFS_RUNTIME_PATH | 管理端下发的 HDFS 配置落盘目录 | /var/lib/hfg/hdfs-runtime |
| HFG_HDFS_REFRESH_INTERVAL | HDFS 配置包同步周期 | PT1M |
| HFG_RPC_HOST | Manager gRPC/VIP | hfg-manager.internal |
| HFG_MANAGER_URL | Manager HTTPS 基址，用于事件与配额 | https://hfg-manager.internal |
| HFG_MANAGER_USERNAME / HFG_MANAGER_PASSWORD | Gateway 服务账号 | Secret 注入 |
| HFG_RPC_CA | gRPC CA 文件 | /etc/hfg/pki/ca.crt |
| HFG_RPC_CLIENT_CERT | 客户端证书 | /etc/hfg/pki/gateway.crt |
| HFG_RPC_CLIENT_KEY | 客户端私钥 | /etc/hfg/pki/gateway.key |
| HFG_RPC_SERVER_NAME | TLS 服务名 | hfg-manager |
| HFG_SFTP_HOST_KEY | 持久化 SSH 主机密钥 | /etc/hfg/ssh_host_ed25519_key |
| HFG_VIP | FTP PASV 返回地址 | 10.0.10.20 |
| HFG_MANAGEMENT_BIND / HFG_MANAGEMENT_PORT | readiness/metrics 监听 | 127.0.0.1 / 18080 |
| HFG_NODE_IP | 心跳上报的节点服务 IP | 10.0.10.11 |

生产使用 gRPC mTLS 分发快照，同时仍通过 Manager HTTPS REST 上报事件和申请精确配额。只有不使用周期配额且允许不汇总业务事件的隔离测试环境才可以省略 HFG_MANAGER_URL。本地开发可把 `HFG_RPC_ENABLED` 设为 false，此时同一 REST 基址还承担快照轮询。

## Gateway 端口

| 端口 | 默认值 | 范围 |
|---|---:|---|
| FTP 控制 | 21 | HFG_FTP_PORT |
| SFTP | 22 | HFG_SFTP_PORT |
| Actuator | 18080 | 仅管理网 |
| FTP PASV | 30000-31000 | 防火墙和 Keepalived 两端一致 |
| Manager gRPC | 19090 | 网关到 Manager |

## Manager 必填项

| 环境变量 | 用途 |
|---|---|
| HFG_DB_URL / HFG_DB_USERNAME / HFG_DB_PASSWORD | PostgreSQL 17 或 MySQL 8 管理库 |
| HFG_DB_MIGRATION_LOCATION | PostgreSQL 留空；MySQL 设置 `classpath:db/mysql` |
| HFG_UUID_JDBC_TYPE | PostgreSQL 使用 `UUID`（默认）；MySQL 使用 `CHAR` |
| HFG_LOGS_DB_URL / HFG_LOGS_DB_USERNAME / HFG_LOGS_DB_PASSWORD | 可选独立业务日志库；URL 留空时复用管理库 |
| HFG_LOGS_DB_POOL_SIZE | 日志库连接池上限，默认 10 |
| HFG_LOGS_RETENTION_DAYS | UTC 日分区保留天数，默认 180 |
| HFG_LOGS_PRECREATE_DAYS | 预建未来日分区数，默认 7 |
| HFG_ADMIN_USERNAME / HFG_ADMIN_PASSWORD | 管理 API 初始管理员 |
| HFG_SNAPSHOT_PRIVATE_KEY_BASE64 | Ed25519 PKCS#8 私钥 Base64 |
| HFG_RPC_SERVER_CERT / HFG_RPC_SERVER_KEY / HFG_RPC_CA | gRPC 双向 TLS |
| HFG_RPC_CA_KEY | 签发 Gateway 客户端证书的 CA PKCS#8 私钥 |
| HFG_GATEWAY_CERT_VALIDITY_DAYS | Gateway 证书有效天数，默认 365 |
| HFG_HDFS_BUNDLE_PATH | HDFS ZIP 与安全解压内容保存目录 |
| HFG_PROMETHEUS_URL | 固定 Prometheus 服务地址 |
| HFG_MANAGER_PORT | Manager 页面与 REST API 端口，默认 8080 |

HDFS 接入只接受最大 32 MiB 的 ZIP，包内至少包含一个 `.keytab` 和定义了 `fs.defaultFS` 的 Hadoop XML。Manager 会防止 Zip Slip、限制解压后总体积、忽略其他文件，从 keytab 自动读取 principal，并保存 SHA-256。FTP/SFTP 用户没有 HDFS 用户字段，所有 HDFS 操作均使用 keytab 服务身份，数据权限由 HFG 虚拟目录 ACL 控制。

Gateway 不配置 HDFS URI、XML、principal 或 keytab。它通过 Manager mTLS gRPC 控制通道按服务组取得配置包，落盘到 `HFG_HDFS_RUNTIME_PATH` 后热更新 HDFS 客户端。客户端证书 CN、请求中的 Gateway ID 和证书登记的服务组必须一致，无需为 HDFS 包分发配置额外账号。Manager REST 仍须置于 HTTPS 反向代理和管理网访问控制之后。

## Ed25519 快照密钥

使用发布介质内的纯 Java 工具生成初始密钥；该方式依赖 JDK 17 自带的 Ed25519 实现，兼容 CentOS 7.9 自带 OpenSSL 1.0.2 不支持 Ed25519 的环境：

```bash
sudo install -d -o root -g hfg -m 0750 /etc/hfg/keys
sudo /opt/hfg/jdk-17/bin/java -cp bin/hfg-keytool.jar \
  io.github.scholiarw.hfg.contract.SnapshotKeyTool /etc/hfg/keys
```

工具生成 `hfg-snapshot-manager.env`（私钥，0600）和 `hfg-snapshot-gateway.env`（公钥，0644），且拒绝覆盖已有文件。将对应文件中的值写入 Manager/Gateway 环境配置；私钥需进入 Secret 管理系统，不要提交生成文件或 Base64 值。命令只输出公钥指纹，不输出私钥。

## Keepalived

为每个服务组从 `deploy/keepalived/keepalived.conf.template` 生成配置。主备必须使用相同 VRID/认证信息和不同优先级，单播地址互指。健康脚本同时检查 systemd、21/22 监听端口和 readiness。角色通知写入 `/var/lib/hfg/role`，再通过服务环境映射为 HFG_ROLE。


## 管理库与业务日志库

HFG 支持 PostgreSQL 17 和 MySQL 8.0+（推荐 8.4 LTS）。管理库保存用户、目录、权限、配置快照、审计和强一致配额账本。业务日志库的 `logs` 表保存上传/下载方向、协议、状态、用户、虚拟路径、文件名、文件字节数、开始/结束时间、耗时、平均传输速率，以及配额窗口快照。

`logs` 按 UTC 日期分区。Manager 启动时执行独立 Flyway 日志迁移，创建当天和未来分区，并按保留期删除过期分区。所有 Manager 主机和数据库会话应使用 UTC。日志库可与管理库同库，也可独立部署；独立部署时数据库需预先创建，账号需具有建表、建索引和分区 DDL 权限。

PostgreSQL 示例：

```bash
HFG_DB_URL=jdbc:postgresql://db:5432/hfg
HFG_LOGS_DB_URL=jdbc:postgresql://logs-db:5432/hfg_logs
```

MySQL 示例：

```bash
HFG_DB_URL='jdbc:mysql://db:3306/hfg?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8'
HFG_DB_MIGRATION_LOCATION=classpath:db/mysql
HFG_UUID_JDBC_TYPE=CHAR
HFG_LOGS_DB_URL='jdbc:mysql://logs-db:3306/hfg_logs?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8'
```

周期配额判断仍使用管理库的 `usage_window` 和 `quota_reservation` 事务行锁；`logs` 保存事务提交后的快照供看板查询，不参与并发扣减。
