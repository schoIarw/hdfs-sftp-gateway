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

## 虚拟目录

用户可以有多个虚拟目录映射（例如 `/` → `/h01`、`/c` → `/hive/tablea`）。列出某个目录时，网关会把该目录下
**其它虚拟映射**也一并列出，并在名字后加 `(v)` 标注，便于和真实 HDFS 目录区分：

```
ls /
a
b
c (v)
```

- 标注只影响显示：客户端把 `c (v)` 原样回传（`cd "c (v)"`、`get "c (v)/x"`）时，网关会自动还原为 `/c`，
  进入目录、上传、下载都不受影响；直接用 `/c` 访问同样有效。
- 真实目录优先：如果真实路径里已经存在同名目录（例如 HDFS 里已有 `/h01/c`），该虚拟映射即失效并被遮蔽，
  列表里只显示真实目录 `c`（无标注），`/c`、`/c (v)` 下的上传下载也都落在真实目录 `/h01/c` 上。
- 遮蔽按真实路径判断：只要按上层映射算出的真实路径存在（目录或文件），更深的虚拟挂载点就不再参与解析；
  因此“先建真实目录、后加同名映射”会使映射立即失效。
- 通过网关自身无法创建与虚拟映射同名的真实目录：该名字的写操作属于映射目标。

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
