# HFG 部署步骤

## 1. 构建产物

执行 `make verify`，产物为：

- `hfg-gateway-app/target/hfg-gateway-app-0.1.0-SNAPSHOT.jar`
- `hfg-manager-api/target/hfg-manager-api-0.1.0-SNAPSHOT.jar`
- `hfg-manager-web/dist`
- `target/bom.json`

## 2. Manager

1. 创建 PostgreSQL 数据库与专用账号。
2. 注入数据库、管理员、Ed25519 和 gRPC mTLS Secret。
3. 启动至少两个 Manager 实例，REST 与 gRPC 前分别配置健康检查负载均衡。
4. Flyway 在启动时自动迁移；生产数据库账号需具备迁移权限，迁移完成后可切换最小权限账号。
5. 配置 HDFS 集群、服务组和 Gateway 节点，再创建用户、目录、授权和流控策略。
6. 发布服务组快照并确认 Gateway 的 `hfg_snapshot_version` 大于零。

`deploy/docker/compose.yaml` 是开发/演示基线，默认关闭 gRPC 且不包含 HDFS，不代表生产高可用拓扑。

## 3. Gateway 主备

1. 两台节点安装同一 JAR、Hadoop XML、keytab、CA/客户端证书和相同 SFTP host key。
2. 为两台节点设置不同 HFG_GATEWAY_ID，相同 HFG_SERVICE_GROUP_ID 和 VIP。
3. 放通 FTP/SFTP、PASV 范围、Manager gRPC、HDFS RPC/DataNode 与本机 Actuator。
4. 安装 systemd unit、健康脚本和按节点渲染的 Keepalived 配置。
5. 先启动 Gateway，确认 readiness 和 21/22 监听，再启动 Keepalived。
6. 确认只有 Active 持有 VIP，Standby 已取得相同签名快照。

## 4. 上线顺序

HDFS/Kerberos → PostgreSQL → Manager → Prometheus/告警 → Gateway Standby → Gateway Active → Keepalived/VIP → Web 反向代理。先用测试服务组和测试路径验证协议，再开放生产用户。

## 5. 回滚

应用版本回滚使用上一版 JAR/镜像。数据库迁移采用向前兼容策略，不自动执行 destructive undo。配置回滚必须重新发布一个更高版本、内容取自历史配置的签名快照；Gateway 拒绝安装低版本快照，不能通过修改文件版本强制回退。
