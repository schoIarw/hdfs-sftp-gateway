# HFG 配置参考

Manager 的管理页面已经打包在 `hfg-manager.jar` 中，和 REST API 共用 `HFG_MANAGER_PORT`；生产运行不需要独立前端配置或 Nginx。Spring Boot 支持环境变量及外部 YAML，推荐将非敏感配置放在 `/etc/hfg/application.yaml`，用下面的方式加载：

```bash
java -jar /opt/hfg/hfg-manager.jar \
  --spring.config.additional-location=file:/etc/hfg/application.yaml
```

本文和 `deploy/env/*.env.example` 采用环境变量作为标准部署接口。systemd 通过 `EnvironmentFile` 加载；手工运行时需先执行 `set -a; source <env-file>; set +a`，否则 shell 中未导出的变量不会传入 Java。

## Gateway 最小配置

Gateway 主配置文件 `/etc/hfg/hfg-gateway.env` 只需填写两个现场值：

| 环境变量 | 用途 | 示例 |
|---|---|---|
| HFG_RPC_HOST | Manager gRPC 地址，必须能通过服务端证书 SAN 校验 | hfg-manager.internal |
| HFG_NODE_IP | 心跳上报的本节点 FTP/SFTP 服务 IP | 10.0.10.11 |

Manager 生成的每节点证书 ZIP 同时包含 `hfg-gateway-bootstrap.env`。它自动提供
`HFG_GATEWAY_ID`、`HFG_SERVICE_GROUP_ID`、`HFG_SNAPSHOT_PUBLIC_KEY_BASE64` 和三个证书路径。
将该文件原样安装到 `/etc/hfg/hfg-gateway-bootstrap.env`；systemd 会在主配置之后加载它。
手工启动或 Docker 启动时也必须同时加载这两个环境文件。

Gateway 统一通过 gRPC mTLS 获取快照和 HDFS 包、上报心跳与传输事件，不再配置 Manager HTTP URL、用户名或密码。FTP PASV 对外地址由 Manager 根据 `HFG_SERVICE_GROUP_ID` 下发服务组 VIP；节点角色固定上报为 `SERVING`，实际流量归属由 Keepalived 决定。

以下参数有默认值，只在现场值不同时配置：

| 环境变量 | 默认值/用途 |
|---|---|
| HFG_RPC_PORT | `19090` |
| HFG_RPC_SERVER_NAME | 默认等于 `HFG_RPC_HOST`；仅当连接地址与 Manager 证书 SAN 名称不同时覆盖 |
| HFG_RPC_CA | `/etc/hfg/pki/ca.crt`，校验 Manager 身份 |
| HFG_RPC_CLIENT_CERT | `/etc/hfg/pki/gateway.crt`，证明 Gateway 身份和服务组授权 |
| HFG_RPC_CLIENT_KEY | `/etc/hfg/pki/gateway.key`，客户端证书私钥 |
| HFG_SFTP_HOST_KEY | 默认 `/etc/hfg/ssh_host_ed25519_key`；启用 SFTP 时文件必须存在且可读，同组节点必须一致 |
| HFG_HDFS_RUNTIME_PATH | `/var/lib/hfg/hdfs-runtime`，Manager 下发的 HDFS 配置落盘目录 |
| HFG_HDFS_REFRESH_INTERVAL | `PT1M`，检查 HDFS 配置包更新的周期 |
| HFG_MANAGEMENT_BIND / HFG_MANAGEMENT_PORT | `127.0.0.1:18080`，Gateway 自身 readiness/Prometheus 端口，不是 Manager 地址；中央 Prometheus 抓取时需绑定管理网 IP 或 `0.0.0.0` 并配合防火墙 |
| HFG_FTP_PORT / HFG_SFTP_PORT | `21` / `22` |
| HFG_FTP_PASSIVE_PORTS | `30000-31000` |
| HFG_SNAPSHOT_PATH / HFG_EVENT_WAL_PATH | `/var/lib/hfg/snapshot.json` / `/var/lib/hfg/events.wal` |
| HFG_EVENT_REPORT_INTERVAL / HFG_HEARTBEAT_INTERVAL | `PT5S` / `PT10S`，ISO-8601 Duration |
| HFG_FTP_ENABLED / HFG_SFTP_ENABLED | 均为 `true`；不用某协议时可关闭 |
| HFG_FTP_BIND / HFG_SFTP_BIND | 均为 `0.0.0.0` |
| HFG_FTP_ACTIVE_MODE / HFG_FTP_IDLE_TIMEOUT | `false` / `300` 秒 |
| HFG_MAX_ACTIVE_TRANSFERS | `120`，单节点同时进行的上传与下载总数 |
| HFG_MAX_UPLOADS / HFG_MAX_DOWNLOADS | `80` / `80`，分方向上限，避免单向流量耗尽节点 |
| HFG_TRANSFER_ACQUIRE_TIMEOUT | `PT10S`，容量用尽后的最长背压等待时间 |
| HFG_FTP_MAX_LOGINS / HFG_FTP_WORKER_THREADS | `200` / `128` |
| HFG_SFTP_MAX_SESSIONS / HFG_SFTP_MAX_CHANNELS | `200` / `200`，节点级上限 |
| HFG_SFTP_MAX_CHANNELS_PER_SESSION | `4`，阻止单个 SSH 会话占满节点 channel |
| HFG_SFTP_WORKER_THREADS / HFG_SFTP_IDLE_TIMEOUT | `128` / `300` 秒 |

主机名由操作系统自动获取，软件版本从 JAR Manifest 自动读取。同一服务组内每台 Gateway 的操作系统主机名必须唯一，否则 Manager 会拒绝重复主机名的心跳。`HFG_ROLE`、`HFG_VIP`、`HFG_MANAGER_URL`、`HFG_MANAGER_USERNAME`、`HFG_MANAGER_PASSWORD`、`HFG_SOFTWARE_VERSION` 和 `HFG_RPC_HOSTNAME` 已取消。

Gateway 启动时从 Manager 读取一次服务组 VIP 作为 FTP PASV 对外地址。修改服务组 VIP 后必须滚动重启该组 Gateway，先重启 Standby、切换 VIP，再重启另一台。

## Gateway 高并发与背压

默认值面向单节点约 100 名用户同时上传/下载：FTP 允许 200 个登录并使用 128 个工作线程；SFTP 允许 200 个会话、节点 200 个 channel、单会话 4 个 channel，并复用 128 个工作线程。真正进入 HDFS 数据通道的传输由节点总量与上传/下载分方向三道公平许可共同控制，默认总量 120、上传 80、下载 80。容量短暂用尽时请求最多等待 10 秒，超时返回限流错误，不创建无界任务队列。

每用户的上传速率、下载速率和最大连接数仍由 Manager 下发的流控策略控制；节点级参数是本机资源保护，两者同时生效。主备 Gateway 的节点级计数各自独立，VIP 切换后从新节点重新计数。HDFS 大文件继续采用流式读写，直接缓冲区读写复用每工作线程 64 KiB 固定缓冲；目录列表最多扫描 10000 个条目，超过时明确失败，避免大目录把堆内存和 NameNode RPC 拖垮。

生产调优先观察 `hfg_transfers_active`、`hfg_transfers_waiting`、`hfg_transfers_rejected_total`、JVM 堆、进程 CPU、HDFS RPC 延迟和网络吞吐，再按实测逐步调整。不要只提高协议连接数而不提高 HDFS、网络和 JVM 容量。

## Gateway 端口

| 端口 | 默认值 | 范围 |
|---|---:|---|
| FTP 控制 | 21 | HFG_FTP_PORT |
| SFTP | 22 | HFG_SFTP_PORT |
| Actuator | 18080 | 仅管理网 |
| FTP PASV | 30000-31000 | 防火墙和 Keepalived 两端一致 |
| Manager gRPC | 19090 | 网关到 Manager |

## Gateway 与 HDFS 连接的对应关系

每个 Gateway 进程通过 `HFG_SERVICE_GROUP_ID` 绑定一个服务组，服务组再绑定一个 HDFS 连接，因此**单个 Gateway 只连接一个 Hadoop 集群**，`HFG_HDFS_RUNTIME_PATH` 下只保存该集群的一份配置包。需要同时接入多个 Hadoop 集群时，按服务组部署多个 Gateway 进程（各自独立的 `HFG_GATEWAY_ID`、证书、端口和运行目录），Manager 中一个 HDFS 连接可被多个服务组复用，但同一个服务组只会就近访问自己绑定的连接。

每条目录映射只归属一个用户，权限仅可设为只读或读写，不再存在多用户共享目录或交叉授权。不同用户拥有独立虚拟命名空间，因此可分别配置相同虚拟路径（例如都使用 `/`）。为避免下发 Gateway 无法解析的虚拟路径，Manager 会拒绝把其它 HDFS 连接的目录分配给用户，也会拒绝把仍拥有目录的用户迁移到绑定不同 HDFS 连接的服务组；发布快照时仅包含用户自己的归属目录。

## 删除顺序与关联保护

Manager 采用“自下而上、有关联就拦”的删除规则，所有删除前都需要二次确认，被拒绝时会弹出提示框列出具体关联对象：

1. **HDFS 连接**：只要还有服务组绑定，或该连接下还有目录映射就不能删除；需先删除服务组，并在“目录管理”中删除目录映射。
2. **VIP 服务组**：只要还有用户属于该组，或该组绑定的 HDFS 连接下还有目录映射，就阻止删除；归属该组的 Gateway 只有在**在线**（30 秒内还有心跳）时才阻止删除，已停止、异常或失联的节点会随服务组一起清理（同时清理该组的客户端证书与已发布快照）。
3. **用户**：只要仍有归属目录就不能删除；应先删除目录或把目录转移给其他用户。
4. **目录映射**：删除映射不删除 HDFS 上的实际数据，之后才能删除 HDFS 连接。

典型清理顺序：目录映射 → 用户 → 服务组 → HDFS 连接。列表操作统一通过复选框选择对象，再使用页面顶部按钮；删除失败时展示后端给出的关联原因。

## Gateway 日志输出

Gateway 只输出必要信息：Apache FtpServer 默认的逐条命令与应答日志（`FtpLoggingFilter` 的 `RECEIVED:`/`SENT:`）、各 FTP 命令实现类的 INFO 日志已关闭（`logging.level.org.apache.ftpserver: WARN`）。取而代之的是每行一条的审计信息：

- FTP/SFTP 登录成功（INFO）与被拒绝（WARN），包含账号、认证方式和来源地址；
- 每次上传/下载结束（完成 INFO、失败/中断 WARN），包含协议、方向、账号、路径、字节数、客户端地址和 Gateway ID；
- 权限或路径被拒绝的上传/下载会在同一行里给出原因。

需要逐条命令排障时，把 `logging.level.org.apache.ftpserver` 临时改为 `INFO` 并重启 Gateway 即可恢复完整协议日志。

## Manager 参数

| 环境变量 | 用途 |
|---|---|
| HFG_DB_URL / HFG_DB_USERNAME / HFG_DB_PASSWORD | PostgreSQL 17 或 MySQL 8 管理库 |
| HFG_DB_MIGRATION_LOCATION | PostgreSQL 留空；MySQL 设置 `classpath:db/mysql` |
| HFG_UUID_JDBC_TYPE | PostgreSQL 使用 `UUID`（默认）；MySQL 使用 `CHAR` |
| HFG_LOGS_DB_URL / HFG_LOGS_DB_USERNAME / HFG_LOGS_DB_PASSWORD | 可选独立业务日志库；URL 留空时复用管理库 |
| HFG_LOGS_DB_POOL_SIZE | 日志库连接池上限，默认 10 |
| HFG_LOGS_RETENTION_DAYS | UTC 日分区保留天数，默认 180 |
| HFG_LOGS_PRECREATE_DAYS | 预建未来日分区数，默认 7 |
| HFG_ADMIN_USERNAME / HFG_ADMIN_PASSWORD | 管理 API 初始管理员；用户名默认 `admin`，密码必填 |
| HFG_GATEWAY_CERT_VALIDITY_DAYS | Gateway 证书有效天数，默认 365 |
| HFG_HDFS_BUNDLE_PATH | HDFS ZIP 与安全解压内容保存目录 |
| HFG_SNAPSHOT_AUTO_PUBLISH_INTERVAL | 待发布配置合并处理间隔，默认 `PT2S` |
| HFG_SNAPSHOT_RECONCILE_INTERVAL | 全服务组配置一致性检查周期，默认 `PT5M` |
| HFG_SNAPSHOT_RECONCILE_INITIAL_DELAY | Manager 启动后首次一致性检查延迟，默认 `PT15S` |
| HFG_SNAPSHOT_RETRY_DELAY | 自动发布失败后的重试间隔，默认 `PT30S` |
| HFG_PROMETHEUS_ENABLED | 是否启用 Manager 的 Prometheus 查询，默认 true；设为 false 后界面显示“未开启”，查询接口返回 503 |
| HFG_PROMETHEUS_URL | 固定 Prometheus 服务地址 |
| HFG_PROMETHEUS_USERNAME / HFG_PROMETHEUS_PASSWORD | Prometheus 的 Basic 认证账号；留空表示匿名访问 |
| HFG_PROMETHEUS_TOKEN | Prometheus 的 Bearer Token，优先级高于 Basic；留空则不发送 |
| HFG_PROMETHEUS_TIMEOUT | 查询超时，默认 PT10S |
| HFG_MANAGER_PORT | Manager 页面与 REST API 端口，默认 8080 |
| HFG_DB_POOL_SIZE / HFG_DB_MIN_IDLE | 管理库连接池，默认 `20` / `2` |
| HFG_RPC_ENABLED / HFG_RPC_PORT | 默认 `true` / `19090`；仅本地演示可关闭 RPC |
| HFG_RPC_CA_KEY_PASSWORD | 仅外部 CA 私钥为加密 PEM 时配置；一键工具生成的私钥无需配置 |

用户、SSH 公钥、目录、流控策略和服务组通过管理 API 成功变更后，Manager 会把所有启用服务组写入持久化待发布队列，默认在 2 秒内合并并自动发布。周期一致性检查使用配置源摘要补偿进程中断或临时数据库故障；没有实际变化时不增加快照版本。页面“强制发布配置”按钮始终生成更高版本并主动重推，适用于现场故障恢复，不是正常配置生效的必需步骤。

其中真正必须人工填写的是数据库连接和初始管理员密码；独立日志库和 Prometheus 地址按部署选择填写。
`HFG_GATEWAY_CERT_VALIDITY_DAYS`、`HFG_HDFS_BUNDLE_PATH`、连接池、保留期和端口均有默认值。
`HFG_SNAPSHOT_PRIVATE_KEY_BASE64`、`HFG_SNAPSHOT_PUBLIC_KEY_BASE64`、`HFG_RPC_SERVER_CERT`、
`HFG_RPC_SERVER_KEY`、`HFG_RPC_CA`、`HFG_RPC_CA_KEY` 由初始化工具写入单独的
`/etc/hfg/hfg-manager-bootstrap.env`，不要复制到主配置文件。

Manager 的“监控告警”页面通过 `HFG_PROMETHEUS_URL` 代理 PromQL 查询：`HFG_PROMETHEUS_ENABLED=false` 时查询功能关闭（接口返回 503），需要认证时配置 `HFG_PROMETHEUS_TOKEN`（Bearer）或 `HFG_PROMETHEUS_USERNAME`/`HFG_PROMETHEUS_PASSWORD`（Basic）。系统管理中的“显示监控告警面板”开关只控制导航入口和页面可见性，配置保存在管理库，不会停止 Actuator 暴露、Prometheus 抓取或告警规则执行。

HDFS 接入只接受最大 32 MiB 的 ZIP，解压后的 XML/keytab 总量不得超过 128 MiB。包内至少包含一个 `.keytab` 和定义了 `fs.defaultFS` 的 Hadoop XML。Manager 会防止 Zip Slip、忽略其他文件、从 keytab 自动读取 principal，并保存 SHA-256。当前版本选择发现的第一个 keytab 及其第一个 principal，因此生产 ZIP 应只包含一个目标 keytab，并在上传前使用 `klist -kte` 确认身份。FTP/SFTP 用户没有 HDFS 用户字段，所有 HDFS 操作均使用 keytab 服务身份，数据权限由 HFG 虚拟目录 ACL 控制。

在“系统管理 → HDFS 接入”中，可多次上传 ZIP 新建不同的 HDFS 连接，也可对已有连接执行“重新上传认证文件”替换其 XML/keytab（标识不变，`bundle_sha256` 与 principal 同步刷新，适用于 keytab 轮换或误删 `/var/lib/hfg/hdfs-bundles` 后的恢复）。`bundle_path` 文件不存在时列表会标记“文件缺失”，此时 Gateway 取不到配置包，需要重新上传。删除连接前必须先删除或改绑引用它的服务组与目录映射，否则 Manager 返回 409 并列出具体的引用对象；删除成功后 Manager 同步删除该连接的配置包目录。

`krb5.conf` 不属于 HDFS ZIP 的生效内容，放入 ZIP 会被忽略。Manager 和 Gateway 的运行环境必须预先提供可用的 `/etc/krb5.conf`；容器部署应将宿主机文件只读挂载到容器同一路径。Kerberos 还依赖 KDC/DNS 可达和时钟同步。

Gateway 不配置 HDFS URI、XML、principal 或 keytab。它通过 Manager mTLS gRPC 控制通道按服务组取得配置包，落盘到 `HFG_HDFS_RUNTIME_PATH` 后热更新 HDFS 客户端。客户端证书 CN、请求中的 Gateway ID 和证书登记的服务组必须一致，无需为 HDFS 包分发配置额外账号。Manager REST 仍须置于 HTTPS 反向代理和管理网访问控制之后。

## Manager 安全材料一键初始化

使用 Native 介质内的纯 Java 工具，一次生成 RSA 3072 位 CA、Manager 服务端证书和
Ed25519 快照签名密钥；不调用 OpenSSL，兼容 CentOS 7/8：

```bash
sudo /opt/hfg/jdk-17/bin/java -jar /opt/hfg/hfg-bootstrap.jar \
  --output /etc/hfg \
  --server-name hfg-manager.example.com \
  --server-ip 10.0.10.10
sudo chown root:hfg /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
sudo chmod 0640 /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
```

`--server-name` 和可选的 `--server-ip` 会写入 Manager 证书 SAN，Gateway 的
`HFG_RPC_HOST`/`HFG_RPC_SERVER_NAME` 必须匹配其中之一。工具拒绝覆盖已有文件，避免误换 CA；
私钥及 bootstrap 环境文件不得提交到仓库。`hfg-gateway-bootstrap.env` 是快照公钥模板，
实际节点应使用 Manager 生成证书 ZIP 内同名文件，因为它还带有节点和服务组身份。

SFTP host key 不纳入每节点自动生成：同一 VIP 服务组的两台 Gateway 必须共享同一个 host key，
否则切换后客户端会报告主机指纹变化。应在第一台生成一次，再通过安全渠道复制到同组另一台。

## 参数精简结论

| 类别 | 人工配置 | 自动生成或自动发现 |
|---|---|---|
| Manager | 数据库、管理员密码；可选日志库/Prometheus | CA、Manager 证书、快照密钥；端口和路径使用默认值 |
| Gateway | `HFG_RPC_HOST`、`HFG_NODE_IP` | 节点 ID、服务组、证书路径、快照公钥；主机名和版本自动发现 |
| 服务组 | VIP、Keepalived VRID/优先级 | Gateway 不再配置角色或 VIP |
| HDFS | Manager 页面上传 XML/keytab ZIP | principal、HDFS URI、摘要和运行文件自动提取/下发 |
| SFTP | 每服务组生成并安全共享一份 host key | 不允许各节点静默生成不同指纹 |

## Keepalived

为每个服务组从 `deploy/keepalived/keepalived.conf.template` 生成配置。主备必须使用相同 VRID/认证信息和不同优先级，单播地址互指。健康脚本同时支持 Native systemd 与名为 `hfg-gateway`（或 Compose 服务名为 `hfg-gateway`）的 Docker 容器，并按环境文件检查已启用协议的监听端口、实际 `HFG_MANAGEMENT_PORT` 和 readiness。Keepalived 决定 VIP 当前归属，Gateway 本身不再维护 Active/Standby 角色。

## Manager 高可用约束

两台 Manager 不能只共用数据库。所有实例必须使用同一套 CA、快照签名密钥，并共享或可靠同步 `HFG_HDFS_BUNDLE_PATH`；否则 Gateway 经负载均衡访问不同实例时会出现客户端证书不被认可、快照验签不一致或 HDFS bundle 不存在。建议把 `/etc/hfg/hfg-manager-bootstrap.env`、`/etc/hfg/pki` 作为受控 Secret 分发，把 `/var/lib/hfg/hdfs-bundles` 放在共享存储，并同时备份管理库、日志库、CA 私钥、快照私钥和 HDFS bundle。

Manager 的 19090 端口使用双向 TLS gRPC。负载均衡必须采用支持 HTTP/2 长连接的四层 TCP 透传，TLS 在 Manager 进程终止，不能使用不转发客户端证书的普通七层 HTTPS 终止方式。Manager 服务端证书 SAN 应包含 Gateway 实际连接的统一 LB/DNS 地址。

## Prometheus 可达性

Gateway 默认只在 `127.0.0.1:18080` 暴露 Actuator，适合本机 Keepalived。中央 Prometheus 需要设置 `HFG_MANAGEMENT_BIND` 为管理网 IP或 `0.0.0.0`，并用防火墙仅允许 Prometheus 访问。同步修改 `deploy/prometheus/prometheus.yaml` 中的 Manager/Gateway 目标，启动后在 Prometheus Targets 页面确认全部为 `UP`。业务上传/下载明细、速率和配额仍查询 `logs`；节点当前活动传输、等待数和拒绝累计数写入 Prometheus，用于容量告警。


## 管理库与业务日志库

HFG 支持 PostgreSQL 17 和 MySQL 8.0+（推荐 8.4 LTS）。管理库保存用户、单用户归属目录及权限、流控策略、配置快照和审计。业务日志库的 `logs` 表保存上传/下载方向、协议、状态、用户、虚拟路径、文件名、文件字节数、开始/结束时间、耗时和平均传输速率。

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

流控策略只包含上传速率、下载速率和最大连接数。文件/目录项数及空间配额配置在单个目录上并由 HDFS quota 强制执行；`logs` 只用于业务指标查询，不参与流控或配额扣减。
