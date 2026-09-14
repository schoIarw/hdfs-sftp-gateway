# HFG 测试与验收方案

## 1. 自动化门禁

```bash
./mvnw --batch-mode clean verify
cd hfg-manager-web
npm ci
npm run lint
npm test -- --run
npm run build
```

后端 verify 执行 Java 17/Maven 版本限制、依赖收敛、Spotless 格式检查、单元测试、可执行 JAR 打包和 CycloneDX SBOM。CI 对 main push 和 pull request 执行相同门禁，并分别启动 PostgreSQL 17 与 MySQL 8.4 验证两套 Flyway 迁移和 Manager 上下文。

## 2. 当前自动化覆盖

| 范围 | 自动化断言 |
|---|---|
| 路径 | `..` 越界拒绝、最长前缀映射 |
| 权限 | READ_ONLY 拒绝上传 |
| 上传 | 目标提交前不可见，rename 后可见 |
| 续传 | 只允许暂存 EOF，拒绝任意偏移 |
| HDFS 适配 | 本地 Hadoop FileSystem 的读写、列表、rename |
| 限速 | 确定性时钟下 Token Bucket refill、大请求分段 |
| 周期 | DAY/WEEK/MONTH、时区与 DST 边界 |
| 并发 | 配额提交异常仍释放 Gateway 并发许可 |
| 快照 | SHA-256、Ed25519、篡改拒绝 |
| SFTP | authorized_keys 注释规范化、公钥校验 |
| API | OpenSSH 公钥格式和 SHA-256 指纹 |
| HDFS 配置包 | 仅提取 XML/keytab、ZIP Slip 拒绝 |
| 用户 | 密码哈希与 revision 冲突 |
| 前端 | 字节格式化、登录凭据生命周期、签名快照版本解析、TypeScript |
| 数据库 | PostgreSQL/MySQL 管理迁移、日志迁移、UTC 日分区创建与 Manager 启动 |

## 3. 集成环境

准备两台 Gateway、一套 HDFS HA、Kerberos KDC、PostgreSQL 或 MySQL、独立 logs 库、两台 Manager、Prometheus、Keepalived VIP 和一台协议测试机。测试账号至少包含：读写、只读、禁用、过期、无配额、低配额、低速率和公钥认证用户。

## 4. 协议验收

| ID | 场景 | 预期 |
|---|---|---|
| FTP-01 | 正确/错误密码登录 | 成功 / 530 |
| FTP-02 | PASV 上传 1 B、64 MiB、10 GiB | HDFS 内容与摘要一致 |
| FTP-03 | LIST/RETR/REST | 列表正确、下载摘要一致、偏移正确 |
| FTP-04 | 只读目录 STOR/DELE/RNTO | 550 |
| FTP-05 | 上传中断后从 EOF 续传 | 最终摘要一致 |
| FTP-06 | 非 EOF 续传 | 明确拒绝且暂存文件不损坏 |
| SFTP-01 | 密码与公钥认证 | 都可登录；未知 key 拒绝 |
| SFTP-02 | put/get/readdir/stat/rename/remove | 与 HDFS 一致 |
| SFTP-03 | symlink/setstat/shell/exec | 拒绝 |
| SFTP-04 | 路径包含多级 `..` | 不越出虚拟根 |
| BOTH-01 | 同一目录跨 FTP/SFTP 读写 | 权限和内容一致 |

每个上传完成后比较客户端 SHA-256 与 HDFS `hdfs dfs -checksum`/下载 SHA-256，并确认最终目录没有遗留已提交的 part 文件。

## 5. 流控与配额验收

1. 将上传和下载设为 10 MiB/s，分别单流、双流持续 120 秒，统计 10 秒滑窗；长期平均应在配置值允许误差内。
2. 并发设为 2，同时发起 3 个传输；第三个应快速返回限流错误，不得无限等待。
3. DAY/WEEK/MONTH 分别测试文件数和字节数达到上限、超限拒绝、边界后恢复。
4. 在 Asia/Shanghai 与 America/New_York 的 DST 切换点测试窗口计算。
5. 传输失败、Gateway kill -9、Manager 暂停时验证租约过期回收且不重复结算。
6. 修改策略并发布新快照，不重启 Gateway；下一次新传输使用新策略。

## 6. 高可用与故障注入

| ID | 注入 | 预期 |
|---|---|---|
| HA-01 | stop Active hfg-gateway | Keepalived 在阈值后迁移 VIP |
| HA-02 | 上传中 kill Active | 当前 TCP 失败；重连 Standby 后从暂存 EOF 续传 |
| HA-03 | Manager 全停 | 已有签名快照继续认证和传输，事件进入 WAL |
| HA-04 | Manager 恢复 | WAL 幂等上报，未重复统计 |
| HA-05 | HDFS Active NameNode 切换 | Hadoop 客户端自动重试；无半成品最终文件 |
| HA-06 | Kerberos ticket 过期 | UGI 自动续票或健康告警；恢复后无需重建数据 |
| HA-07 | 在 Manager-B 发布快照，网关连 Manager-A | 心跳发现版本并收到跨实例更新 |
| HA-08 | 篡改本地/网络快照 | 验签失败，继续使用最后有效版本 |
| HA-09 | PostgreSQL 主库切换 | Manager 短暂失败后恢复，版本不重复 |

故障切换测试记录 VRRP 日志、VIP 邻居缓存更新时间、客户端失败时间、Gateway readiness 和快照版本。验收指标应由项目现场 SLA 确认，不在代码中伪造固定秒数。

## 7. 安全测试

- 密码、keytab、私钥、Authorization 和快照私钥不出现在日志、审计 before/after 或 Prometheus。
- 验证目录穿越、超长路径、控制字符、重复 slash、Unicode 文件名。
- 验证管理 API 未认证返回 401，mutating API 产生 audit_log，健康和指标端点按网络策略开放。
- 使用依赖扫描和生成的 `target/bom.json` 做上线前漏洞审查。
- 使用其他 Gateway 的证书或篡改请求 Gateway ID/服务组，gRPC 心跳、快照订阅和 HDFS 包下载均应拒绝。
- 上传含 `../`、绝对路径、超出 32 MiB 压缩包或超出 128 MiB 解压内容的 HDFS ZIP，均应拒绝且不得写出目标目录。
- 生产反向代理验证 HTTPS、HSTS、请求体限制和管理网访问控制。
- FTP 若启用，仅允许可信 CIDR；公网场景只验收 SFTP。

## 8. 容量与耐久性

以目标峰值连接数执行 4 小时 soak test，文件尺寸覆盖小文件、典型文件和大文件。观测 JVM heap、GC、线程、打开文件数、HDFS RPC、NameNode handler、PostgreSQL连接池、事件 WAL 增长和 p95/p99 吞吐。kill -9 后检查暂存文件可续传，过期暂存文件清理策略由运维作业按业务保留期执行。


## 9. 业务日志验收

1. 分别以 PostgreSQL 和 MySQL 启动 Manager，确认管理迁移与日志迁移 history 表互不冲突。
2. 上报 STARTED/COMPLETED、FAILED、ABORTED 事件，核对用户名、文件名、字节、开始结束时间、耗时和平均速率，并验证重复 WAL 上报不重复计数。
3. 跨 UTC 00:00 传输，确认记录归属开始事件的日期分区且结束更新成功。
4. 触发配额预留、提交、过期释放，确认 `QUOTA` 快照与管理库最终值一致。
5. 查询总览、用户历史、实时连接、流控当前/历史接口，确认 SQL 使用 `log_date` 分区裁剪。
6. 抓取 `/actuator/prometheus`，确认不再存在文件路径、用户名、transfer ID 或业务传输计数；JVM、进程、线程、Hikari 和健康指标仍可用。
