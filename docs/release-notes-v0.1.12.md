# HFG 0.1.12 发布说明

## 重点变更

- 网关传输并发与背压：新增节点级并发控制，默认同时最多 120 个传输、上传 80、下载 80（`HFG_MAX_ACTIVE_TRANSFERS` / `HFG_MAX_UPLOADS` / `HFG_MAX_DOWNLOADS`）。容量暂满时请求最多等待 10 秒（`HFG_TRANSFER_ACQUIRE_TIMEOUT`，默认 `PT10S`），超时直接返回限流错误，不再堆积无界任务队列。
- 协议层连接上限：FTP 默认允许 200 个登录、128 个工作线程（`HFG_FTP_MAX_LOGINS` / `HFG_FTP_WORKER_THREADS`）；SFTP 默认 200 个会话、节点 200 个 channel、单会话 4 个 channel，并复用 128 个工作线程（`HFG_SFTP_MAX_SESSIONS` / `HFG_SFTP_MAX_CHANNELS` / `HFG_SFTP_MAX_CHANNELS_PER_SESSION` / `HFG_SFTP_WORKER_THREADS` / `HFG_SFTP_IDLE_TIMEOUT`），避免单个 SSH 会话占满节点资源。
- 可观测性：新增 `hfg_transfers_active`、`hfg_transfers_waiting`、`hfg_transfers_rejected_total` 指标，并在 `deploy/prometheus/hfg-rules.yaml` 增加“持续等待容量”和“持续被拒绝”两条告警。
- 资源保护：HDFS 大文件继续流式读写并复用每工作线程 64 KiB 缓冲；目录列表最多扫描 10000 个条目，超过时明确失败，避免超大目录拖垮堆内存与 NameNode。
- 与用户级流控的关系：Manager 下发的每用户上传速率、下载速率和最大连接数策略继续生效，节点级参数只做本机资源保护，两者同时起作用；主备节点各自独立计数，VIP 切换后从新节点重新计数。

## 升级说明

1. 本次没有数据库结构变更；升级前仍建议备份管理库、日志库和 `/etc/hfg`。
2. 新增配置项全部带默认值，现有 `hfg-gateway.env` 不改也能升级；需要调整时参考 `deploy/env/hfg-gateway.env.example`。
3. 只需替换 Gateway JAR，建议按服务组滚动重启（先 Standby、切换 VIP、再重启另一台），使两台都加载新的并发策略。
4. 上线后关注 `hfg_transfers_waiting` 与 `hfg_transfers_rejected_total`：持续等待说明节点容量偏紧，应先确认 HDFS、网络与 JVM 容量再逐步上调上限，不要只提高协议连接数。

## 介质

- `hfg-0.1.12-linux-x86_64-native.tar.gz`：Native JAR、Java 17 运行说明、systemd、Keepalived、Prometheus 配置和文档。
- `hfg-0.1.12-linux-x86_64-docker.tar.gz`：linux/amd64 Manager/Gateway 镜像、Compose、Keepalived、Prometheus 配置和文档。
- `SHA256SUMS`：两个独立压缩包的 SHA-256。
