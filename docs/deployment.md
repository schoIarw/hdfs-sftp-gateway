# HFG 安装与运行手册

HFG 提供两种安装方式：Native（直接运行 Java JAR）和 Docker。生产环境采用相同的双进程架构：`hfg-manager.jar` 是管理 API 与 React 页面合并包，`hfg-gateway.jar` 提供 FTP/SFTP 数据面。前端不再单独部署，访问 Manager 的 `http(s)://<host>:8080/` 即可打开管理页面。

如使用 GitHub Release 中已经编译完成的 Linux x86_64 介质，请直接阅读 [编译介质分步部署手册](package-deployment.md)。Native 与 Docker 为两个独立、自包含的压缩包，按部署方式下载其一即可；目标服务器不需要源码、Maven、Node.js 或 npm。

Native 模式更适合使用 systemd、Keepalived 和宿主机 VIP 的生产主备节点；Docker 模式适合开发、验收及已有容器运维体系的环境。两种模式都需要外部 HDFS，生产环境还应使用独立 PostgreSQL/MySQL、Prometheus 和证书/Secret 管理设施。

## 一、构建发布包

### 1. 构建机要求

- Linux x86_64/arm64；
- JDK 17，`JAVA_HOME` 指向该 JDK；
- Node.js 24 和 npm（只在构建时使用）；
- 可访问 Maven Central 与 npm registry。

```bash
git clone git@github.com:schoIarw/hdfs-sftp-gateway.git
cd hdfs-sftp-gateway
make package
```

`make package` 依次执行前端类型检查、前端测试、Vite 构建以及全部 Maven 测试。成功后交付：

| 文件 | 用途 |
|---|---|
| `hfg-manager-api/target/hfg-manager-api-0.1.13.jar` | Manager、REST API 与管理页面合并包 |
| `hfg-gateway-app/target/hfg-gateway-app-0.1.13.jar` | FTP/SFTP Gateway |
| `hfg-common-contract/target/hfg-common-contract-0.1.13-bootstrap.jar` | 一键生成 CA、Manager 证书和快照密钥 |
| `target/bom.json` | CycloneDX 软件物料清单 |

验证页面确实进入 Manager JAR：

```bash
unzip -l hfg-manager-api/target/hfg-manager-api-*.jar \
  | grep 'BOOT-INF/classes/static/index.html'
```

构建完成后的运行主机只需 Java 17，不需要安装 Node.js、npm 或 Nginx。

## 二、Native 安装

### 1. 运行环境

- 所有 Manager/Gateway 主机安装 Java 17 JRE；
- PostgreSQL 17 或 MySQL 8.0+（推荐 MySQL 8.4 LTS；开发可单实例，生产建议高可用）；
- 可访问 HDFS NameNode/DataNode；管理员需准备包含 Hadoop XML 与 keytab 的 ZIP 配置包；
- Gateway 主备节点安装 Keepalived、curl、iproute2；
- 网络放通 21、22、FTP PASV 端口段、8080、19090，以及 HDFS 所需端口。
- Manager 与 Gateway 操作系统预先配置可用的 `/etc/krb5.conf`、DNS 和 NTP/Chrony；HDFS ZIP 不下发 `krb5.conf`。

建立运行用户与目录：

```bash
sudo useradd --system --home /var/lib/hfg --shell /usr/sbin/nologin hfg
sudo install -d -o hfg -g hfg /opt/hfg /var/lib/hfg
sudo install -d -o root -g hfg -m 0750 /etc/hfg /etc/hfg/pki
sudo install -o hfg -g hfg -m 0550 \
  hfg-manager-api/target/hfg-manager-api-*.jar /opt/hfg/hfg-manager.jar
sudo install -o hfg -g hfg -m 0550 \
  hfg-gateway-app/target/hfg-gateway-app-*.jar /opt/hfg/hfg-gateway.jar
sudo install -o root -g hfg -m 0550 \
  hfg-common-contract/target/hfg-common-contract-*-bootstrap.jar /opt/hfg/hfg-bootstrap.jar
```

CentOS 7.9 使用 systemd 219 和 OpenSSL 1.0.2。应将独立 JDK 17 安装到 `/opt/hfg/jdk-17`，使用 `deploy/systemd/centos7/` 中的 unit；默认绑定 21/22 时执行 `setcap cap_net_bind_service=+ep /opt/hfg/jdk-17/bin/java`。不要对共享的 `/usr/bin/java` 授权，JDK 升级后需重新设置 capability。也可改用 2121/2222 避免 capability。

### 2. 初始化数据库

以下命令由数据库管理员执行，密码必须替换：

```sql
CREATE ROLE hfg LOGIN PASSWORD 'CHANGE_ME';
CREATE DATABASE hfg OWNER hfg ENCODING 'UTF8';
```

如选择 MySQL：

```sql
CREATE DATABASE hfg CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'hfg'@'%' IDENTIFIED BY 'CHANGE_ME';
GRANT ALL PRIVILEGES ON hfg.* TO 'hfg'@'%';
```

MySQL 环境文件必须额外设置：

```bash
HFG_DB_URL='jdbc:mysql://mysql.example.com:3306/hfg?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8'
HFG_DB_MIGRATION_LOCATION=classpath:db/mysql
HFG_UUID_JDBC_TYPE=CHAR
```

Manager 启动时由 Flyway 自动执行数据库迁移。生产发布前应备份数据库；迁移账号可在迁移完成后切换为满足运行期最小权限的账号。生产推荐单独创建 `hfg_logs` 数据库并通过 `HFG_LOGS_DB_URL` 接入；开发环境可留空并与管理表共库。

### 3. 初始化 CA、Manager 证书和快照密钥

```bash
sudo /opt/hfg/jdk-17/bin/java -jar /opt/hfg/hfg-bootstrap.jar \
  --output /etc/hfg \
  --server-name hfg-manager.example.com \
  --server-ip 10.0.10.10
sudo chown root:hfg /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
sudo chmod 0640 /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
```

工具使用 Java 一次生成 RSA CA、Manager 服务端证书和 Ed25519 快照密钥，不依赖 OpenSSL，
并拒绝覆盖已有文件。完整的 CentOS 7/8 OpenSSL 备选流程和验证命令见
[编译介质分步部署手册](package-deployment.md#341-centos-78-使用-openssl-手工生成-ca备选)。

双 Manager 必须共享同一 CA、快照密钥和 HDFS bundle 存储，连接同一数据库；19090 使用支持 HTTP/2 的四层 mTLS 透传。仅共用数据库不足以构成可用的 Manager 高可用部署。

### 4. 配置并启动 Manager

复制模板并填写数据库、管理员密码；证书密钥由单独的 bootstrap 文件提供：

```bash
sudo install -o root -g hfg -m 0640 deploy/env/hfg-manager.env.example /etc/hfg/hfg-manager.env
sudoedit /etc/hfg/hfg-manager.env
```

systemd 会自动加载 `/etc/hfg/hfg-manager-bootstrap.env`。CA 私钥只用于 Java 内部签发 Gateway 客户端证书。

前台试运行便于查看错误：

以下命令使用 CentOS 7 独立 JDK 路径；其他发行版替换为已验证的 Java 17 绝对路径。

```bash
sudo -u hfg bash -c 'set -a; source /etc/hfg/hfg-manager.env; source /etc/hfg/hfg-manager-bootstrap.env; set +a; \
  exec /opt/hfg/jdk-17/bin/java -XX:MaxRAMPercentage=75 -jar /opt/hfg/hfg-manager.jar'
```

服务化运行：

```bash
sudo install -o root -g root -m 0644 deploy/systemd/hfg-manager.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now hfg-manager
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

浏览器访问 `http://<manager-host>:8080/`。页面和 API 由同一个 JAR、同一个端口提供；API 使用 `HFG_ADMIN_USERNAME`/`HFG_ADMIN_PASSWORD` 进行 HTTP Basic 认证。

### 5. 配置并启动 Gateway

在每台主备节点分别复制模板：

```bash
sudo install -o root -g hfg -m 0640 deploy/env/hfg-gateway.env.example /etc/hfg/hfg-gateway.env
sudoedit /etc/hfg/hfg-gateway.env
```

先在管理页面“系统管理”上传 HDFS ZIP；Manager 会自动读取 keytab principal 和 XML。然后按
Gateway 标识及服务组生成证书 ZIP，把其中 `gateway.crt`、`gateway.key`、`ca.crt` 安装到
Gateway 的 `/etc/hfg/pki`，并把 `hfg-gateway-bootstrap.env` 安装到 `/etc/hfg/`。
整个签发过程由 Java 完成，不调用 openssl。

HDFS ZIP 只放 Hadoop XML 和一个目标 keytab；`krb5.conf` 会被忽略。上传前使用 `klist -kte` 检查 keytab。创建服务组后，先创建用户、虚拟目录、ACL、流控和配额；Manager 默认在 2 秒内自动发布首个快照。确认页面出现快照版本后再启动 Gateway，否则 readiness 会因为没有有效快照而返回 `OUT_OF_SERVICE`。页面“强制发布”仅用于主动重推或故障恢复。

Gateway 主配置只需人工填写 `HFG_RPC_HOST` 和 `HFG_NODE_IP`。节点 ID、服务组、快照公钥和
证书路径来自下载的 bootstrap 文件。服务组 VIP 由 Manager 下发，无需在 Gateway 重复配置。
同组两台节点仍须安全共享同一份 SFTP host key，避免 VIP 切换后主机指纹变化。
同一服务组的操作系统主机名必须唯一。修改服务组 VIP 后需滚动重启 Gateway，使新的 FTP PASV 地址生效。

直接前台运行：

```bash
sudo -u hfg bash -c 'set -a; source /etc/hfg/hfg-gateway.env; source /etc/hfg/hfg-gateway-bootstrap.env; set +a; \
  exec /opt/hfg/jdk-17/bin/java -XX:MaxRAMPercentage=75 -jar /opt/hfg/hfg-gateway.jar'
```

生产建议使用仓库提供的 systemd unit。它以非 root 用户运行，并仅授予绑定 21/22 低位端口所需的 `CAP_NET_BIND_SERVICE`：

```bash
sudo install -o root -g root -m 0644 deploy/systemd/hfg-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now hfg-gateway
curl --fail http://127.0.0.1:18080/actuator/health/readiness
```

若不使用 systemd capability，可把 `HFG_FTP_PORT`/`HFG_SFTP_PORT` 改为 2121/2222，并相应修改防火墙、健康检查与入口端口映射。

### 6. 配置 Keepalived 与 VIP

每个 Active/Standby 服务组使用一份独立 VRID。将健康脚本安装到模板引用的位置，再按节点替换网卡、VRID、优先级、本机/对端地址和 VIP：

```bash
sudo install -o root -g root -m 0755 deploy/keepalived/hfg-gateway-health.sh /usr/local/bin/
sudo install -o root -g root -m 0755 deploy/keepalived/hfg-role-change.sh /usr/local/bin/
sudo cp deploy/keepalived/keepalived.conf.template /etc/keepalived/keepalived.conf
sudoedit /etc/keepalived/keepalived.conf
sudo keepalived -t -f /etc/keepalived/keepalived.conf
sudo systemctl enable --now keepalived
```

主节点优先级应高于备节点；两端 VRID、认证信息和 VIP 相同，单播地址互指。启动后用 `ip address show` 确认只有 Active 节点持有 VIP。

### 7. Native 验收

```bash
systemctl --no-pager --full status hfg-manager hfg-gateway keepalived
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:18080/actuator/health/readiness
curl --fail http://127.0.0.1:8080/ | grep '<div id="root">'
ftp <VIP>
sftp -P 22 <ftp-user>@<VIP>
```

完成用户、目录、ACL 和流控策略配置并确认自动发布的快照版本后，再验证列表、上传、下载、临时文件原子提交、配额拒绝和主备切换。

## 三、Docker 安装

### 1. Docker 环境要求

- Docker Engine 24+ 与 Compose v2；
- 至少 4 GiB 可用内存；
- 可拉取基础镜像并可访问 HDFS；
- 生产 Secret 和 Gateway 证书通过只读 volume 或 Secret 挂载；HDFS XML/keytab 由 Manager 配置包同步到可写数据卷。

### 2. 启动 Manager 演示栈

仓库提供 PostgreSQL 和 MySQL 两套 Compose。两者都会构建合并版 Manager 并启动 Prometheus，不再启动独立 Web/Nginx 容器：

```bash
export HFG_DB_PASSWORD='CHANGE_ME_DB'
export HFG_ADMIN_PASSWORD='CHANGE_ME_ADMIN'
export HFG_SNAPSHOT_PRIVATE_KEY_BASE64='<PKCS8_DER_BASE64>'
docker compose -f deploy/docker/compose.yaml up -d --build
docker compose -f deploy/docker/compose.yaml ps

# 或 MySQL 8.4
export HFG_MYSQL_ROOT_PASSWORD='CHANGE_ME_ROOT'
docker compose -f deploy/docker/compose.mysql.yaml up -d --build
docker compose -f deploy/docker/compose.mysql.yaml ps
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

管理页面地址为 `http://127.0.0.1:8080/`。此 Compose 关闭 gRPC 且不包含 HDFS，仅作为本地开发/验收基线，不代表生产高可用拓扑。

### 3. 构建并运行 Gateway 镜像

```bash
docker build -f deploy/docker/Dockerfile.gateway -t hfg-gateway:0.1.13 .
```

FTP PASV 与宿主机 VIP 涉及多端口和返回地址，Linux 生产节点推荐 host 网络。示例：

```bash
docker run -d --name hfg-gateway --restart unless-stopped \
  --network host \
  --env-file /etc/hfg/hfg-gateway.env \
  --env-file /etc/hfg/hfg-gateway-bootstrap.env \
  -v /var/lib/hfg:/var/lib/hfg \
  -v /etc/hfg:/etc/hfg:ro \
  -v /etc/krb5.conf:/etc/krb5.conf:ro \
  hfg-gateway:0.1.13
```

镜像内使用 UID 10001。宿主机的 `/var/lib/hfg` 必须允许 UID 10001 写入，证书和 SSH host key 必须允许 UID 10001 读取。HDFS 配置包会自动写入该数据目录。绑定 21/22 时若容器运行时默认移除了低位端口能力，增加 `--cap-add NET_BIND_SERVICE`。

每个主备节点分别运行一个 Gateway 容器，配置原则与 Native 模式相同。Keepalived 建议仍运行在宿主机；健康脚本支持名为 `hfg-gateway` 的容器和 Compose 的 `hfg-gateway` 服务，并按环境文件读取管理端口。中央 Prometheus 抓取时需设置 `HFG_MANAGEMENT_BIND=0.0.0.0` 或管理网 IP，并通过防火墙限制来源。

### 4. Docker 验收与停止

```bash
docker logs --tail 200 hfg-gateway
curl --fail http://127.0.0.1:18080/actuator/health/readiness
docker compose -f deploy/docker/compose.yaml logs --tail 200 hfg-manager
docker compose -f deploy/docker/compose.yaml down
```

`down` 不删除命名卷；只有明确需要清除本地演示数据库时才使用 `down --volumes`。

## 四、升级与回滚

Native 升级先在 Standby 替换 JAR 并重启、验证后切换 VIP，再升级另一台；Manager 应逐实例滚动升级。Docker 使用新版本 tag 重建/替换容器，不要复用不可追踪的 `latest`。

应用回滚使用上一版 JAR 或镜像。数据库迁移采用向前兼容策略，不自动执行 destructive undo；升级前必须备份。配置回滚应基于历史内容重新发布一个更高版本的签名快照，因为 Gateway 会拒绝安装低版本快照。


## 五、业务日志运维

`logs` 使用 UTC 日分区，默认预建未来 7 天并保留 180 天。Manager readiness 包含 `logsDatabase` 健康项。上线前验证日志账号可执行 Flyway 和分区 DDL。

业务看板仅查询 `logs`；Prometheus 只采集 JVM、GC、线程、进程 CPU、Hikari 连接池和 readiness 等运行指标。升级前同时备份管理库和日志库。旧版 `transfer_event` 表作为兼容历史表保留，但新事件不再写入；如需展示旧数据，应在上线前离线回填到 `logs`。
