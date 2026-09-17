# HFG 0.1.4 Linux x86_64 编译介质部署手册

本文只使用发布页下载的编译后介质部署，不要求目标服务器具有源码、Git、Maven、Node.js 或 npm。HFG 分为两个进程：

- `hfg-manager.jar`：管理端、REST API、gRPC 控制面和内嵌 React 页面；
- `hfg-gateway.jar`：FTP/SFTP 数据面，直接读写 HDFS。

Native 与 Docker 分别提供独立、自包含的压缩包，不再提供混合介质。用户只需下载计划采用的部署方式：

| 文件 | 用途 |
|---|---|
| `hfg-0.1.4-linux-x86_64-native.tar.gz` | Native JAR、配置、systemd、Keepalived、Prometheus 和文档，不含 Docker 镜像 |
| `hfg-0.1.4-linux-x86_64-docker.tar.gz` | Docker 镜像、Compose、配置、Keepalived、Prometheus 和文档，不含 Manager/Gateway Native JAR |
| `SHA256SUMS` | 两个介质的 SHA-256 校验值 |
| `bom.json` | CycloneDX SBOM |

## 1. 下载、校验和解压

```bash
mkdir -p /tmp/hfg-install
cd /tmp/hfg-install

curl -fLO https://github.com/schoIarw/hdfs-sftp-gateway/releases/download/v0.1.4/hfg-0.1.4-linux-x86_64-native.tar.gz
curl -fLO https://github.com/schoIarw/hdfs-sftp-gateway/releases/download/v0.1.4/SHA256SUMS
sha256sum --check --ignore-missing SHA256SUMS

tar -xzf hfg-0.1.4-linux-x86_64-native.tar.gz
cd hfg-0.1.4-linux-x86_64-native
uname -m
cat RELEASE-INFO.txt
```

`uname -m` 应输出 `x86_64`。如果服务器输出 `aarch64`/`arm64`，不要使用 Docker 镜像介质；JAR 理论上可运行，但该版本未按 ARM 平台交付验证。

解压后的目录：

```text
bin/          Manager/Gateway 可执行 JAR 与 Java 初始化工具
config/       Manager/Gateway 环境变量模板
systemd/      systemd unit
keepalived/   主备切换配置和脚本
prometheus/   运行指标采集与告警规则
docs/         部署、配置和测试说明
```

## 2. 上线前规划

### 2.1 服务器角色

至少规划一台 Manager 和一对 Gateway。生产建议：

| 角色 | 建议数量 | 说明 |
|---|---:|---|
| Manager | 2 | 接同一个管理库和日志库，由外部 LB 提供入口 |
| PostgreSQL/MySQL | 高可用实例 | 管理库；日志量较大时另建 `hfg_logs` |
| Gateway | 每服务组 2 台 | Active/Standby，共用一个 VIP |
| Prometheus | 1 套 | 只存 JVM、进程、线程、连接池和健康指标 |
| HDFS | 已有集群 | Manager 保存上传的 XML/keytab 包，Gateway 动态获取 |

多个 FTP/SFTP 对外服务组重复部署 Gateway 主备对，每组使用不同 VIP、服务组 ID 和 Keepalived VRID。

### 2.2 网络端口

| 来源 | 目标 | 端口 | 用途 |
|---|---|---:|---|
| 管理员浏览器/LB | Manager | 8080/TCP | 管理页面和 REST API |
| Gateway | Manager | 19090/TCP | mTLS gRPC 控制通道 |
| FTP 客户端 | Gateway VIP | 21/TCP | FTP 控制通道 |
| SFTP 客户端 | Gateway VIP | 22/TCP | SFTP |
| FTP 客户端 | Gateway VIP | 30000-31000/TCP | FTP PASV 数据通道 |
| Prometheus/本机 | Gateway | 18080/TCP | Actuator/运行指标，建议仅管理网可达 |
| Manager/Gateway | HDFS/KDC | 按集群配置 | NameNode、DataNode、Kerberos |

所有主机、数据库会话和日志分区统一使用 UTC。确保主备 Gateway 之间允许 VRRP，或在不支持组播时使用模板中的单播配置。

## 3. Native 部署

### 3.1 安装 Java 和系统组件

所有 Manager/Gateway 主机安装 64 位 Java 17：

```bash
java -version
java -XshowSettings:properties -version 2>&1 | grep -E 'os.arch|java.version'
```

应看到 Java 17 和 `os.arch = amd64`。Gateway 主机还需安装 Keepalived、curl 和 iproute2。

RHEL/Rocky/AlmaLinux：

```bash
sudo dnf install -y java-17-openjdk-headless keepalived curl iproute
```

Ubuntu/Debian：

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless keepalived curl iproute2
```

#### CentOS 7.9 专项说明

CentOS 7.9 的系统 OpenSSL 1.0.2 不提供 Ed25519，且 systemd 219 不支持通用 unit 中的部分新指令。不要使用 `openssl genpkey -algorithm ED25519`。建议给 HFG 安装独立的 Linux x64 JDK 17 到固定目录，并使用介质中的 CentOS 7 unit：

```bash
sudo yum install -y keepalived curl iproute libcap
sudo install -d -o root -g root -m 0755 /opt/hfg/jdk-17
# 将已下载并校验的 JDK 17 x64 tar.gz 解压到临时目录后复制内容：
sudo cp -a /tmp/jdk-17/. /opt/hfg/jdk-17/
/opt/hfg/jdk-17/bin/java -version

sudo install -o root -g root -m 0644 systemd/centos7/hfg-manager.service /etc/systemd/system/hfg-manager.service
sudo install -o root -g root -m 0644 systemd/centos7/hfg-gateway.service /etc/systemd/system/hfg-gateway.service
```

Gateway 使用默认 21/22 端口时，仅对 HFG 专用 JDK 的 Java 可执行文件授予低端口能力：

```bash
sudo setcap cap_net_bind_service=+ep /opt/hfg/jdk-17/bin/java
getcap /opt/hfg/jdk-17/bin/java
```

不要对系统共享的 `/usr/bin/java` 设置 capability。JDK 升级会替换二进制，升级后必须重新执行并验证 `setcap`。若安全规范禁止文件 capability，则将 FTP/SFTP 改为 2121/2222，并通过防火墙/NAT 映射外部端口。CentOS 7 unit 有意不配置 Gateway 的 `NoNewPrivileges`，否则文件 capability 无法在启动时生效。

### 3.2 创建运行账号并安装介质

Manager 和所有 Gateway 节点均执行：

```bash
sudo useradd --system --home-dir /var/lib/hfg --shell /sbin/nologin hfg 2>/dev/null || true
sudo install -d -o hfg -g hfg -m 0750 /opt/hfg /var/lib/hfg
sudo install -d -o root -g hfg -m 0750 /etc/hfg /etc/hfg/pki
```

Manager 主机：

```bash
sudo install -o hfg -g hfg -m 0550 bin/hfg-manager.jar /opt/hfg/hfg-manager.jar
sudo install -o root -g hfg -m 0550 bin/hfg-bootstrap.jar /opt/hfg/hfg-bootstrap.jar
```

每台 Gateway：

```bash
sudo install -o hfg -g hfg -m 0550 bin/hfg-gateway.jar /opt/hfg/hfg-gateway.jar
```

CentOS 8/RHEL 8+/Rocky/AlmaLinux 及常规现代发行版安装通用 unit：

```bash
sudo install -o root -g root -m 0644 systemd/hfg-manager.service /etc/systemd/system/
sudo install -o root -g root -m 0644 systemd/hfg-gateway.service /etc/systemd/system/
```

CentOS 7 只安装兼容 unit，不要再用通用 unit 覆盖：

```bash
sudo install -o root -g root -m 0644 systemd/centos7/hfg-manager.service /etc/systemd/system/hfg-manager.service
sudo install -o root -g root -m 0644 systemd/centos7/hfg-gateway.service /etc/systemd/system/hfg-gateway.service
```

### 3.3 创建管理库和日志库

应用启动时 Flyway 自动建表；数据库本身和账号需先创建。

PostgreSQL 17：

```sql
CREATE ROLE hfg LOGIN PASSWORD 'REPLACE_WITH_STRONG_PASSWORD';
CREATE DATABASE hfg OWNER hfg ENCODING 'UTF8';
CREATE DATABASE hfg_logs OWNER hfg ENCODING 'UTF8';
```

MySQL 8.0+/8.4：

```sql
CREATE DATABASE hfg CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE hfg_logs CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'hfg'@'%' IDENTIFIED BY 'REPLACE_WITH_STRONG_PASSWORD';
GRANT ALL PRIVILEGES ON hfg.* TO 'hfg'@'%';
GRANT ALL PRIVILEGES ON hfg_logs.* TO 'hfg'@'%';
```

日志库账号必须具有建表、建索引和分区 DDL 权限。生产升级前同时备份 `hfg` 与 `hfg_logs`。如不使用独立日志库，可将 `HFG_LOGS_DB_URL` 留空。

### 3.4 一键初始化 CA、Manager 证书和快照密钥（推荐）

只在安全的 Manager 主机执行一次。`--server-name` 和 `--server-ip` 必须是 Gateway 实际访问
Manager 时使用的 DNS 名/IP，它们会写入服务端证书 SAN：

```bash
sudo /opt/hfg/jdk-17/bin/java -jar /opt/hfg/hfg-bootstrap.jar \
  --output /etc/hfg \
  --server-name hfg-manager.example.com \
  --server-ip 10.0.10.10

sudo chown root:hfg /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
sudo chmod 0640 /etc/hfg/hfg-manager-bootstrap.env /etc/hfg/pki/*.key
sudo chmod 0644 /etc/hfg/pki/*.crt
```

工具使用 Java 密码学 API，生成 RSA 3072 位 CA、RSA 3072 位 Manager 证书、Ed25519 快照密钥，
以及 `/etc/hfg/hfg-manager-bootstrap.env`。它不调用 OpenSSL，适用于 CentOS 7/8，并拒绝覆盖
已有文件，防止误换 CA。CA 私钥和快照私钥必须进入受控备份或 Secret 管理系统。

验证生成结果：

```bash
sudo test -r /etc/hfg/pki/ca.crt -a -r /etc/hfg/pki/manager.crt
openssl verify -CAfile /etc/hfg/pki/ca.crt /etc/hfg/pki/manager.crt
openssl x509 -in /etc/hfg/pki/ca.crt -noout -subject -fingerprint -sha256
openssl x509 -in /etc/hfg/pki/manager.crt -noout -subject -issuer -dates
openssl x509 -in /etc/hfg/pki/manager.crt -noout -text | grep -A2 'Subject Alternative Name'
sudo awk -F= '/^HFG_(SNAPSHOT_PRIVATE_KEY|RPC_CA_KEY)=/{print $1"=<configured>"}' \
  /etc/hfg/hfg-manager-bootstrap.env
```

第一条 `openssl verify` 必须输出 `OK`，SAN 必须包含 Gateway 使用的 Manager 地址。

#### 3.4.1 CentOS 7/8 使用 OpenSSL 手工生成 CA（备选）

推荐优先使用上一节 Java 一键初始化。仅在安全规范要求由 OpenSSL/外部 PKI 管理 CA 时使用本节。
HFG 的 RPC CA 和 Manager 证书使用 RSA，不使用 Ed25519，因此兼容 CentOS 7.9 的 OpenSSL 1.0.2。
快照签名仍由 Java 生成 Ed25519 密钥，不能在 CentOS 7 上执行
`openssl genpkey -algorithm ED25519`。

CentOS 7 安装并确认版本：

```bash
sudo yum install -y openssl
openssl version
# 预期为 OpenSSL 1.0.2k-fips 或同系列版本
```

CentOS 8/Rocky 8/AlmaLinux 8 安装并确认版本：

```bash
sudo dnf install -y openssl
openssl version
# 通常为 OpenSSL 1.1.1 系列
```

以下生成命令同时兼容 CentOS 7 和 8。只在 Manager 主机执行；示例地址必须替换：

```bash
sudo install -d -o root -g hfg -m 0750 /etc/hfg/pki
sudo sh -c 'umask 077; openssl genrsa 3072 | openssl pkcs8 -topk8 -nocrypt -out /etc/hfg/pki/ca.key'

sudo tee /etc/hfg/pki/ca.cnf >/dev/null <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
C = CN
O = HFG
OU = HFG RPC
CN = HFG RPC Root CA
[v3_ca]
basicConstraints = critical,CA:true,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF

sudo openssl req -new -x509 -sha256 -days 3650 \
  -key /etc/hfg/pki/ca.key \
  -out /etc/hfg/pki/ca.crt \
  -config /etc/hfg/pki/ca.cnf

sudo sh -c 'umask 077; openssl genrsa 3072 | openssl pkcs8 -topk8 -nocrypt -out /etc/hfg/pki/manager.key'

sudo tee /etc/hfg/pki/manager.cnf >/dev/null <<'EOF'
[req]
distinguished_name = dn
req_extensions = server_ext
prompt = no
[dn]
C = CN
O = HFG
OU = HFG Manager
CN = hfg-manager.example.com
[server_ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @san
[san]
DNS.1 = hfg-manager.example.com
IP.1 = 10.0.10.10
EOF

sudo openssl req -new -sha256 \
  -key /etc/hfg/pki/manager.key \
  -out /etc/hfg/pki/manager.csr \
  -config /etc/hfg/pki/manager.cnf
sudo openssl x509 -req -sha256 -days 825 \
  -in /etc/hfg/pki/manager.csr \
  -CA /etc/hfg/pki/ca.crt \
  -CAkey /etc/hfg/pki/ca.key \
  -CAcreateserial \
  -out /etc/hfg/pki/manager.crt \
  -extfile /etc/hfg/pki/manager.cnf \
  -extensions server_ext

sudo rm -f /etc/hfg/pki/manager.csr /etc/hfg/pki/ca.srl
sudo chown root:hfg /etc/hfg/pki/ca.key /etc/hfg/pki/manager.key
sudo chmod 0640 /etc/hfg/pki/ca.key /etc/hfg/pki/manager.key
sudo chmod 0644 /etc/hfg/pki/ca.crt /etc/hfg/pki/manager.crt
```

验证证书用途、链路、SAN 和公私钥匹配：

```bash
openssl verify -CAfile /etc/hfg/pki/ca.crt /etc/hfg/pki/manager.crt
openssl x509 -in /etc/hfg/pki/ca.crt -noout -text | grep -A2 'Basic Constraints'
openssl x509 -in /etc/hfg/pki/manager.crt -noout -text | grep -A2 'Subject Alternative Name'
openssl x509 -in /etc/hfg/pki/manager.crt -pubkey -noout | openssl dgst -sha256
openssl pkey -in /etc/hfg/pki/manager.key -pubout | openssl dgst -sha256
```

CentOS 7 若不支持 `openssl pkey`，最后一条改为：

```bash
openssl rsa -in /etc/hfg/pki/manager.key -pubout | openssl dgst -sha256
```

证书链应输出 `OK`；CA 必须显示 `CA:TRUE`；Manager SAN 必须包含实际访问地址；最后两条
SHA-256 值必须一致。手工生成时仍需创建 `/etc/hfg/hfg-manager-bootstrap.env` 并写入上述四个
PKI 路径，同时使用介质中的 Java 工具生成快照签名密钥：

```bash
sudo /opt/hfg/jdk-17/bin/java -cp /opt/hfg/hfg-bootstrap.jar \
  io.github.scholiarw.hfg.contract.SnapshotKeyTool /etc/hfg/snapshot-key
sudo sh -c 'umask 027; cat /etc/hfg/snapshot-key/hfg-snapshot-manager.env /etc/hfg/snapshot-key/hfg-snapshot-gateway.env > /etc/hfg/hfg-manager-bootstrap.env; printf "%s\n" "HFG_RPC_SERVER_CERT=/etc/hfg/pki/manager.crt" "HFG_RPC_SERVER_KEY=/etc/hfg/pki/manager.key" "HFG_RPC_CA=/etc/hfg/pki/ca.crt" "HFG_RPC_CA_KEY=/etc/hfg/pki/ca.key" >> /etc/hfg/hfg-manager-bootstrap.env'
sudo chown root:hfg /etc/hfg/hfg-manager-bootstrap.env
sudo chmod 0640 /etc/hfg/hfg-manager-bootstrap.env
```

除非接入外部 PKI，建议使用 3.4 的一键命令，避免漏配扩展用途或 SAN。

### 3.5 配置 Manager

```bash
sudo install -o root -g hfg -m 0640 config/hfg-manager.env.example /etc/hfg/hfg-manager.env
sudo vi /etc/hfg/hfg-manager.env
```

PostgreSQL 最小配置：

```ini
HFG_DB_URL=jdbc:postgresql://db.example.com:5432/hfg
HFG_DB_USERNAME=hfg
HFG_DB_PASSWORD=REPLACE_WITH_STRONG_PASSWORD
HFG_DB_MIGRATION_LOCATION=classpath:db/migration
HFG_UUID_JDBC_TYPE=UUID
HFG_LOGS_DB_URL=jdbc:postgresql://db.example.com:5432/hfg_logs
HFG_LOGS_DB_USERNAME=hfg
HFG_LOGS_DB_PASSWORD=REPLACE_WITH_STRONG_PASSWORD
HFG_ADMIN_USERNAME=admin
HFG_ADMIN_PASSWORD=REPLACE_WITH_ADMIN_PASSWORD
```

MySQL 必须改为：

```ini
HFG_DB_URL=jdbc:mysql://db.example.com:3306/hfg?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8
HFG_DB_MIGRATION_LOCATION=classpath:db/mysql
HFG_UUID_JDBC_TYPE=CHAR
HFG_LOGS_DB_URL=jdbc:mysql://db.example.com:3306/hfg_logs?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8
```

证书和密钥变量不再写入主配置；3.4 生成的 `/etc/hfg/hfg-manager-bootstrap.env` 自动提供。
`manager.crt` 必须包含 Gateway 配置的 `HFG_RPC_HOST` 或 `HFG_RPC_SERVER_NAME` 对应 SAN。
`ca.key` 只供 Manager 内置 Java 逻辑签发 Gateway 客户端证书。Gateway 客户端证书由管理页面生成下载。

### 3.6 首次启动 Manager

先前台启动以观察迁移错误：

```bash
sudo -u hfg bash -c 'set -a; source /etc/hfg/hfg-manager.env; source /etc/hfg/hfg-manager-bootstrap.env; set +a; exec java -XX:MaxRAMPercentage=75 -jar /opt/hfg/hfg-manager.jar'
```

确认启动正常后按 `Ctrl+C` 停止，启用 systemd：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now hfg-manager
sudo systemctl --no-pager --full status hfg-manager
sudo journalctl -u hfg-manager -n 200 --no-pager
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

浏览器访问 `http://<manager-ip>:8080/`。前端已经包含在 JAR 内，无需另装 Nginx 或 Node.js。

### 3.7 上传 HDFS/Kerberos 配置

准备 ZIP 包，至少包含实际集群需要的：

```text
core-site.xml
hdfs-site.xml
krb5.conf（如环境需要随包下发）
一个 .keytab 文件
```

在“系统管理 → HDFS 接入”上传 ZIP。Manager 会自动解压、校验 XML、识别 keytab principal 并使配置生效。HDFS 不配置独立“HDFS 用户”，访问身份以 keytab 中的 Kerberos principal 为准。随后创建服务组，填写该组 VIP 并绑定 HDFS 集群。

### 3.8 生成和安装 Gateway 证书

在“系统管理 → Gateway 节点”中为每个节点填写唯一 Gateway ID 和所属服务组，然后生成并下载证书 ZIP。
Manager 使用 Java 密码学 API 签发，ZIP 包包含 `gateway.crt`、`gateway.key`、`ca.crt`、
`hfg-gateway-bootstrap.env` 和安装说明。

在对应 Gateway 节点执行：

```bash
sudo install -o root -g hfg -m 0640 gateway.crt /etc/hfg/pki/gateway.crt
sudo install -o root -g hfg -m 0640 gateway.key /etc/hfg/pki/gateway.key
sudo install -o root -g hfg -m 0640 ca.crt /etc/hfg/pki/ca.crt
sudo install -o root -g hfg -m 0640 hfg-gateway-bootstrap.env /etc/hfg/hfg-gateway-bootstrap.env
```

证书 CN、`HFG_GATEWAY_ID`、生成证书时选择的服务组必须一致。

### 3.9 配置 Gateway

```bash
sudo install -o root -g hfg -m 0640 config/hfg-gateway.env.example /etc/hfg/hfg-gateway.env
sudo vi /etc/hfg/hfg-gateway.env
```

每台节点的主配置只需两个现场值：

```ini
HFG_RPC_HOST=hfg-manager.example.com
HFG_NODE_IP=10.0.10.11
```

ID、服务组、快照公钥和证书路径均由上一步的 `hfg-gateway-bootstrap.env` 提供。
FTP/SFTP/被动端口和本地管理端口都有默认值，只有现场不同才覆盖。

证书和运行目录均使用约定默认路径，只有路径变化时才覆盖 `HFG_RPC_CA`、`HFG_RPC_CLIENT_CERT`、`HFG_RPC_CLIENT_KEY`、`HFG_SFTP_HOST_KEY` 或 `HFG_HDFS_RUNTIME_PATH`。`HFG_RPC_SERVER_NAME` 默认等于 `HFG_RPC_HOST`，仅在连接地址与证书 SAN 名称不同时设置。服务组 VIP 由 Manager 下发；主机名和软件版本由程序自动获取；不再配置角色、Manager HTTP 地址或管理账号密码。

Gateway 不配置 HDFS XML、keytab、principal 或 HDFS 用户。它凭 Manager 下发的客户端证书认证，通过控制通道获取服务组 HDFS 包并写入 `/var/lib/hfg/hdfs-runtime`。

主备节点必须使用同一份 SFTP host key，避免 VIP 切换后客户端出现指纹变化：

```bash
sudo ssh-keygen -t ed25519 -N '' -f /etc/hfg/ssh_host_ed25519_key
sudo chown root:hfg /etc/hfg/ssh_host_ed25519_key
sudo chmod 0640 /etc/hfg/ssh_host_ed25519_key
```

在第一台生成后，通过安全渠道复制到同组 Standby。

### 3.10 启动 Gateway

首次前台验证时同时加载两个环境文件：

```bash
sudo -u hfg bash -c 'set -a; source /etc/hfg/hfg-gateway.env; source /etc/hfg/hfg-gateway-bootstrap.env; set +a; exec java -XX:MaxRAMPercentage=75 -jar /opt/hfg/hfg-gateway.jar'
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now hfg-gateway
sudo systemctl --no-pager --full status hfg-gateway
sudo journalctl -u hfg-gateway -n 200 --no-pager
curl --fail http://127.0.0.1:18080/actuator/health/readiness
ss -lntp | grep -E ':(21|22|18080)\b'
```

systemd unit 仅授予绑定 21/22 所需的 `CAP_NET_BIND_SERVICE`。若安全规范禁止 capability，将端口改为 2121/2222 并同步修改入口 NAT/防火墙。

### 3.11 配置 Active/Standby 与 VIP

两台 Gateway 都执行：

```bash
sudo install -o root -g root -m 0755 keepalived/hfg-gateway-health.sh /usr/local/bin/
sudo install -o root -g root -m 0755 keepalived/hfg-role-change.sh /usr/local/bin/
sudo install -o root -g root -m 0600 keepalived/keepalived.conf.template /etc/keepalived/keepalived.conf
sudo vi /etc/keepalived/keepalived.conf
sudo keepalived --config-test=/etc/keepalived/keepalived.conf
sudo systemctl enable --now keepalived
ip address show
```

替换网卡名、VIP、VRID、本机/对端 IP 和优先级。Active 候选节点优先级高于 Standby；同组 VRID 和 VIP 相同，不同服务组的 VRID 必须不同。确认只有一台持有 VIP，停止当前 Active 的 Gateway 后 VIP 应迁移到 Standby。

### 3.12 Native 功能验收

在管理页面创建 FTP/SFTP 用户，绑定虚拟目录和 HDFS 目录，配置读写权限、流控与配额，发布配置快照，然后执行：

```bash
ftp <VIP>
sftp -P 22 <user>@<VIP>
```

至少验证：

1. 未登录不能列目录或传输；
2. 用户只能看见已绑定的虚拟目录；
3. 只读目录禁止上传、重命名和删除；
4. 上传通过临时文件原子提交到 HDFS；
5. 下载内容和大小一致；
6. 达到流速或周期配额后按策略拒绝；
7. `logs` 日分区出现传输记录和配额快照；
8. 用户看板、流控看板能查询相同业务数据；
9. Prometheus 只展示 JVM/进程等运行指标；
10. Active 故障后 VIP 切换，Standby 可建立新连接。

## 4. Docker 部署

Docker 方式使用发布页提供的已编译 amd64 镜像，目标机不进行 Maven/npm 构建。

### 4.1 下载并加载镜像

```bash
cd /tmp/hfg-install
curl -fLO https://github.com/schoIarw/hdfs-sftp-gateway/releases/download/v0.1.4/hfg-0.1.4-linux-x86_64-docker.tar.gz
curl -fLO https://github.com/schoIarw/hdfs-sftp-gateway/releases/download/v0.1.4/SHA256SUMS
sha256sum --check --ignore-missing SHA256SUMS
tar -xzf hfg-0.1.4-linux-x86_64-docker.tar.gz
cd hfg-0.1.4-linux-x86_64-docker
docker load -i images/hfg-images.tar

docker image inspect hfg-manager:0.1.4 --format '{{.Os}}/{{.Architecture}}'
docker image inspect hfg-gateway:0.1.4 --format '{{.Os}}/{{.Architecture}}'
```

两条命令都应输出 `linux/amd64`。镜像包只包含 HFG 镜像及 JRE 基础层；Compose 中 PostgreSQL、MySQL、Prometheus 镜像仍需从镜像仓库获取，完全离线环境应提前另行导入这些第三方镜像。

### 4.2 快速启动 Manager 验收环境

PostgreSQL：

```bash
cd /tmp/hfg-install/hfg-0.1.4-linux-x86_64-docker
export HFG_VERSION=0.1.4
export HFG_DB_PASSWORD='REPLACE_WITH_DB_PASSWORD'
export HFG_ADMIN_PASSWORD='REPLACE_WITH_ADMIN_PASSWORD'
# 演示栈关闭 gRPC；如需发布配置快照，先按 3.4 生成并 source bootstrap 文件
docker compose -f docker/compose.postgresql.yaml up -d
docker compose -f docker/compose.postgresql.yaml ps
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

MySQL：

```bash
export HFG_VERSION=0.1.4
export HFG_DB_PASSWORD='REPLACE_WITH_DB_PASSWORD'
export HFG_MYSQL_ROOT_PASSWORD='REPLACE_WITH_ROOT_PASSWORD'
export HFG_ADMIN_PASSWORD='REPLACE_WITH_ADMIN_PASSWORD'
docker compose -f docker/compose.mysql.yaml up -d
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

这两套 Compose 关闭 gRPC，只用于功能验收。生产应使用外部高可用数据库，把 Manager 证书和数据目录以 volume/Secret 挂载，并启用 19090 mTLS。

### 4.3 生产运行 Manager 容器

Docker 介质的 `tools/hfg-bootstrap.jar` 可直接借用 Manager 镜像内的 JRE 完成一键初始化，
宿主机不必另装 Java：

```bash
sudo install -d -m 0750 /etc/hfg /var/lib/hfg
docker run --rm --user 0:0 --entrypoint java \
  -v "$PWD/tools/hfg-bootstrap.jar:/tool.jar:ro" \
  -v /etc/hfg:/etc/hfg \
  hfg-manager:0.1.4 \
  -jar /tool.jar --output /etc/hfg \
  --server-name hfg-manager.example.com --server-ip 10.0.10.10
```

按 Native 章节准备 `/etc/hfg/hfg-manager.env` 和 `/var/lib/hfg`。容器使用固定 UID/GID 10001，挂载目录需显式授权：

```bash
sudo chown root:10001 /etc/hfg
sudo chmod 0750 /etc/hfg
sudo chown -R 10001:10001 /etc/hfg/pki /var/lib/hfg
sudo chmod 0700 /etc/hfg/pki
docker run -d --name hfg-manager --restart unless-stopped \
  --platform linux/amd64 \
  --env-file /etc/hfg/hfg-manager.env \
  --env-file /etc/hfg/hfg-manager-bootstrap.env \
  -p 8080:8080 -p 19090:19090 \
  -v /var/lib/hfg:/var/lib/hfg \
  -v /etc/hfg:/etc/hfg:ro \
  hfg-manager:0.1.4

docker logs --tail 200 hfg-manager
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

### 4.4 运行 Gateway 容器

FTP PASV 和 VIP 涉及大段端口及返回地址，Linux 生产节点推荐 host 网络。先复制配置并安装 Manager 下发的证书：

```bash
sudo cp config/hfg-gateway.env.example /etc/hfg/hfg-gateway.env
sudo vi /etc/hfg/hfg-gateway.env
sudo chown root:10001 /etc/hfg
sudo chmod 0750 /etc/hfg
sudo chown -R 10001:10001 /etc/hfg/pki /var/lib/hfg
sudo chown 10001:10001 /etc/hfg/ssh_host_ed25519_key
sudo chmod 0700 /etc/hfg/pki
sudo chmod 0600 /etc/hfg/ssh_host_ed25519_key
export HFG_VERSION=0.1.4
docker compose -f docker/compose.gateway.yaml up -d
docker compose -f docker/compose.gateway.yaml ps
curl --fail http://127.0.0.1:18080/actuator/health/readiness
```

也可直接运行：

```bash
docker run -d --name hfg-gateway --restart unless-stopped \
  --platform linux/amd64 --network host --cap-add NET_BIND_SERVICE \
  --user 10001:10001 \
  --env-file /etc/hfg/hfg-gateway.env \
  --env-file /etc/hfg/hfg-gateway-bootstrap.env \
  -v /var/lib/hfg:/var/lib/hfg \
  -v /etc/hfg:/etc/hfg:ro \
  hfg-gateway:0.1.4
```

Keepalived 仍运行在宿主机，使用本机 18080 readiness 和 21/22 监听状态决定是否持有 VIP。

### 4.5 Docker 验收与停止

```bash
docker logs --tail 200 hfg-manager
docker logs --tail 200 hfg-gateway
docker stop hfg-gateway hfg-manager
docker rm hfg-gateway hfg-manager
```

验收 Compose 使用 `docker compose -f docker/compose.postgresql.yaml down`。不要在生产环境随意增加 `--volumes`，否则会删除 Compose 管理的数据库卷。

## 5. 升级与回滚

Native Gateway 先升级 Standby：备份旧 JAR、替换 Standby、验证、切换 VIP、再升级另一台。Manager 多实例滚动升级。

Docker 先 `docker load` 新镜像，再使用新版本 tag 重建容器。Flyway 不执行 destructive undo，升级前必须备份管理库和日志库。应用回滚使用上一版 JAR/镜像；配置回滚应基于历史内容重新发布更高版本的签名快照，因为 Gateway 会拒绝低版本快照。

## 6. 常见故障

| 现象 | 排查 |
|---|---|
| Manager Flyway 失败 | 检查数据库、DDL 权限、MySQL 迁移目录和 UUID 类型 |
| Manager 页面 404 | 执行 `unzip -l bin/hfg-manager.jar | grep static/index.html` |
| Gateway mTLS 失败 | 检查 CA、证书 CN、Gateway ID、服务组和 Manager DNS SAN |
| Gateway 无 HDFS 配置 | 检查 HDFS ZIP、节点证书绑定、19090 网络和日志 |
| Kerberos 失败 | 检查 keytab principal、KDC、DNS、时钟和 krb5.conf |
| FTP PASV 超时 | 检查 VIP、30000-31000 防火墙/NAT 和被动端口配置 |
| VIP 不切换 | 执行健康脚本，检查 VRID、优先级、单播对端和 VRRP 网络 |
| 看板无数据 | 检查 `logsDatabase` readiness、UTC 分区和 Gateway 事件上报 |

完整环境变量见 `docs/configuration.md`，测试与验收口径见 `docs/testing.md`。
