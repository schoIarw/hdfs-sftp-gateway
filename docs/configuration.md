# HFG 配置参考

## Gateway 必填项

| 环境变量 | 用途 | 示例 |
|---|---|---|
| HFG_GATEWAY_ID | 全局唯一节点 ID | hfg-gateway-a01 |
| HFG_SERVICE_GROUP_ID | 固定服务组 | group-a |
| HFG_SNAPSHOT_PUBLIC_KEY_BASE64 | Ed25519 X.509 公钥 Base64 | Secret 注入 |
| HFG_HDFS_DEFAULT_FS | HDFS URI/HA nameservice | hdfs://nameservice1 |
| HFG_HDFS_RESOURCES | core-site/hdfs-site，逗号分隔 | /etc/hadoop/core-site.xml,/etc/hadoop/hdfs-site.xml |
| HFG_KERBEROS_PRINCIPAL | 服务 Principal | hfg/host@REALM |
| HFG_KERBEROS_KEYTAB | keytab 路径 | /etc/hfg/hfg.keytab |
| HFG_RPC_HOST | Manager gRPC/VIP | hfg-manager.internal |
| HFG_MANAGER_URL | Manager HTTPS 基址，用于事件与配额 | https://hfg-manager.internal |
| HFG_MANAGER_USERNAME / HFG_MANAGER_PASSWORD | Gateway 服务账号 | Secret 注入 |
| HFG_RPC_CA | gRPC CA 文件 | /etc/hfg/pki/ca.crt |
| HFG_RPC_CLIENT_CERT | 客户端证书 | /etc/hfg/pki/gateway.crt |
| HFG_RPC_CLIENT_KEY | 客户端私钥 | /etc/hfg/pki/gateway.key |
| HFG_RPC_SERVER_NAME | TLS 服务名 | hfg-manager |
| HFG_SFTP_HOST_KEY | 持久化 SSH 主机密钥 | /etc/hfg/ssh_host_ed25519_key |
| HFG_VIP | FTP PASV 返回地址 | 10.0.10.20 |

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
| HFG_DB_URL / HFG_DB_USERNAME / HFG_DB_PASSWORD | PostgreSQL |
| HFG_ADMIN_USERNAME / HFG_ADMIN_PASSWORD | 管理 API 初始管理员 |
| HFG_SNAPSHOT_PRIVATE_KEY_BASE64 | Ed25519 PKCS#8 私钥 Base64 |
| HFG_RPC_SERVER_CERT / HFG_RPC_SERVER_KEY / HFG_RPC_CA | gRPC 双向 TLS |
| HFG_PROMETHEUS_URL | 固定 Prometheus 服务地址 |

HDFS 集群记录中的 `keytab_secret_ref` 只接受 `file:` URI，防止 Manager 任意解析外部 Secret。生产部署由 Secret 管理系统把 keytab 挂载到该文件路径。

## Ed25519 快照密钥

示例命令仅用于生成初始密钥；私钥输出必须进入 Secret 管理系统：

```bash
openssl genpkey -algorithm ED25519 -out hfg-snapshot-private.pem
openssl pkey -in hfg-snapshot-private.pem -outform DER | base64 -w0
openssl pkey -in hfg-snapshot-private.pem -pubout -outform DER | base64 -w0
```

Manager 配置第一行产生的 PKCS#8 DER Base64，Gateway 配置第二行产生的 X.509 公钥 DER Base64。不要提交 PEM、DER 或 Base64 值。

## Keepalived

为每个服务组从 `deploy/keepalived/keepalived.conf.template` 生成配置。主备必须使用相同 VRID/认证信息和不同优先级，单播地址互指。健康脚本同时检查 systemd、21/22 监听端口和 readiness。角色通知写入 `/var/lib/hfg/role`，再通过服务环境映射为 HFG_ROLE。
