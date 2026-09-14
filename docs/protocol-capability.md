# HFG 协议能力矩阵

## FTP

| 能力 | 状态 | 说明 |
|---|---|---|
| USER/PASS | 支持 | BCrypt 密码与账号状态/有效期 |
| LIST/NLST/MLSD | 支持 | 目录映射与 ACL 后读取 HDFS |
| RETR | 支持 | HDFS 流式下载与 REST 偏移 |
| STOR | 支持 | HDFS 暂存文件 + 原子提交 |
| APPE/REST + STOR | 受限支持 | 只允许从暂存文件 EOF 继续 |
| DELE/RNFR/RNTO/MKD/RMD | 支持 | 受 READ_WRITE 权限限制 |
| PASV/EPSV | 支持 | 固定端口范围和 VIP 外部地址 |
| PORT/EPRT | 默认关闭 | 设置 HFG_FTP_ACTIVE_MODE 才启用 |
| 匿名登录 | 不支持 | 所有访问必须认证 |
| FTPS | 不支持 | 当前版本未配置 TLS listener |
| 跨故障会话恢复 | 不支持 | VIP 切换后客户端重连并按 EOF 续传 |

## SFTP

| 能力 | 状态 | 说明 |
|---|---|---|
| 密码认证 | 支持 | BCrypt |
| OpenSSH 公钥认证 | 支持 | Manager REST 维护公钥 |
| stat/lstat/fstat | 支持 | 受目录可见性限制 |
| readdir | 支持 | HDFS 目录列表 |
| read | 支持 | 顺序读取与合法 seek |
| create/write | 支持 | 暂存后原子 rename |
| EOF 续传 | 支持 | 偏移必须等于暂存文件长度 |
| 任意偏移写 | 不支持 | 返回 SSH_FX_OP_UNSUPPORTED |
| mkdir/rmdir/remove/rename | 支持 | 受 READ_WRITE 权限限制 |
| symlink/readlink | 不支持 | 防止目录边界绕过 |
| chmod/chown/setstat | 不支持 | 避免绕过 HDFS 管理策略 |
| shell/exec/port forwarding | 不提供 | 服务端仅注册 SFTP 子系统 |
| 主机密钥 | 持久化 | 主备节点必须使用同一受控密钥 |

## 错误语义

| HFG 错误 | FTP 语义 | SFTP 语义 |
|---|---|---|
| AUTH_INVALID / ACCOUNT_DISABLED | 530 | 认证失败 |
| PATH_NOT_FOUND | 550 | SSH_FX_NO_SUCH_FILE |
| PERMISSION_DENIED | 550 | SSH_FX_PERMISSION_DENIED |
| ALREADY_EXISTS | 550 | SSH_FX_FILE_ALREADY_EXISTS |
| QUOTA_EXCEEDED / RATE_LIMITED | 450/552 | SSH_FX_FAILURE |
| UNSUPPORTED_OFFSET | 504 | SSH_FX_OP_UNSUPPORTED |
| HDFS_UNAVAILABLE | 451 | SSH_FX_CONNECTION_LOST/FAILURE |
| INVALID_PATH | 550 | SSH_FX_INVALID_FILENAME |
