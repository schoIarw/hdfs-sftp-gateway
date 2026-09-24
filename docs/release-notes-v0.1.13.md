# HFG 0.1.13 发布说明

## 重点变更

- 修复高并发下 FTP 目录列表放大成海量 HDFS RPC：`listFiles()` 直接把列表扫描得到的条目状态传入条目对象，LIST 回复读取目录/文件/权限/属主/属组/大小/时间等属性不再逐条发起 `getFileStatus`；路径新建对象也改为单实例内最多一次 stat 缓存。此前 1000 条目目录的一次 LIST 约触发 7000 次 NameNode RPC，多个并发 LIST 会把 NameNode RPC 队列打满并拖垮正常传输。
- 修复协议工作线程池小于传输上限导致“FTP 卡死”的风险：Apache FtpServer 的控制命令与阻塞式数据拷贝共用同一工作池，SFTP 子系统线程池执行传输 channel。默认 `HFG_FTP_WORKER_THREADS` / `HFG_SFTP_WORKER_THREADS` 由 128 提升到 256，并在启动校验中强制 `FTP worker-threads ≥ HFG_MAX_ACTIVE_TRANSFERS + 32`、`SFTP worker-threads ≥ HFG_SFTP_MAX_CHANNELS + 32`，避免把两个值配反后现场出现控制面被数据面占满。
- 修复 HDFS 配置热加载掐断在途传输：配置替换后旧客户端池延迟 5 分钟再关闭（宽限期内启动的传输继续使用旧池），不再立刻 `close()` 导致正在读写的传输以 “Filesystem closed” 失败。
- 修复 FTP 目录列表失败时返回 null 导致客户端收到内部错误：超过 10000 条目扫描上限或其它列表失败时记录明确日志并返回空列表，控制通道保持可用。
- 新增每用户传输并发上限：默认 `HFG_MAX_TRANSFERS_PER_USER:16`，防止单个用户或脚本占满整节点容量导致其它用户全部限流；设为 0 可关闭。
- 修复 SFTP channel 许可在异常断连边角路径下的泄漏：全局 channel 许可按会话登记，`sessionClosed` 兜底释放该会话仍持有的许可。
- 修复 WAL 写线程异常分支静默丢弃整批审计事件：非 IO 异常时回退入队，队列满则转入 `.failed` 文件隔离。

## 升级说明

1. 本次没有数据库结构变更；升级前仍建议备份管理库、日志库和 `/etc/hfg`。
2. 新增配置项带默认值，现有 `hfg-gateway.env` 不改也能升级；若现场显式设置了 `HFG_FTP_WORKER_THREADS` 或 `HFG_SFTP_WORKER_THREADS`，请确认其满足新的启动校验关系（FTP：≥ `HFG_MAX_ACTIVE_TRANSFERS`+32；SFTP：≥ `HFG_SFTP_MAX_CHANNELS`+32），否则网关将拒绝启动并给出明确报错。
3. 每用户并发上限默认 16：高并发多段下载客户端（单用户并行流超过 16）如遇到限流，可按业务需要调高 `HFG_MAX_TRANSFERS_PER_USER` 或设为 0 关闭。
4. 只需替换 Gateway JAR，建议按服务组滚动重启（先 Standby、切换 VIP、再重启另一台）。

## 介质

- `hfg-0.1.13-linux-x86_64-native.tar.gz`：Native JAR、Java 17 运行说明、systemd、Keepalived、Prometheus 配置和文档。
- `hfg-0.1.13-linux-x86_64-docker.tar.gz`：linux/amd64 Manager/Gateway 镜像、Compose、Keepalived、Prometheus 配置和文档。
- `SHA256SUMS`：两个独立压缩包的 SHA-256。
