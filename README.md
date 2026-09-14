# HFG — HDFS FTP/SFTP Gateway

HFG (`hdfs-sftp-gateway`) exposes authenticated FTP and SFTP endpoints while storing files directly in HDFS. It separates the always-on data plane (`hfg-gateway-app`) from the management/control plane (`hfg-manager-api` and `hfg-manager-web`).

## Architecture

- Multiple service groups, each consisting of one VIP and an Active/Standby HFG Gateway pair.
- Direct HDFS streaming through a storage abstraction; protocol modules never import Hadoop classes.
- Versioned, signed configuration snapshots allow gateways to continue serving published users while Manager is unavailable.
- PostgreSQL stores configuration, audit and transfer records; Prometheus/Alertmanager handle operational metrics and alerts.
- Uploads are invisible until committed: write `.uploading/<transfer-id>.part`, close, then atomically rename.

## Modules

| Module | Responsibility |
|---|---|
| `hfg-common-contract` | IDs, DTOs, enums, errors and snapshot contracts |
| `hfg-storage-api` | Storage-neutral read/write/list/quota API |
| `hfg-storage-hdfs` | Hadoop `FileSystem` implementation and Kerberos `doAs` |
| `hfg-policy-engine` | Virtual path confinement, ACL and source-IP policy |
| `hfg-traffic-control` | Bandwidth, concurrency and periodic quota enforcement |
| `hfg-transfer-core` | Transfer lifecycle, staging, commit and audit events |
| `hfg-protocol-ftp` | Apache FtpServer adapter |
| `hfg-protocol-sftp` | Apache MINA SSHD adapter |
| `hfg-gateway-control-client` | Versioned control-plane snapshot client |
| `hfg-gateway-app` | Gateway process, readiness and metrics |
| `hfg-manager-domain` | Management domain model and services |
| `hfg-manager-infrastructure` | JPA, PostgreSQL and snapshot persistence |
| `hfg-manager-api` | REST management API and Prometheus proxy |
| `hfg-manager-web` | React + TypeScript + Semi Design UI |

## Build

```bash
./mvnw clean verify
cd hfg-manager-web && npm ci && npm run build && npm test
```

Run local dependencies:

```bash
docker compose -f deploy/docker/compose.yaml up -d
```

Before production deployment, read:

- [System design and implementation](docs/implementation.md)
- [Protocol capability matrix](docs/protocol-capability.md)
- [Configuration reference](docs/configuration.md)
- [Deployment runbook](docs/deployment.md)
- [Test and acceptance plan](docs/testing.md)
