# HFG — HDFS FTP/SFTP Gateway

HFG (`hdfs-sftp-gateway`) exposes authenticated FTP and SFTP endpoints while storing files directly in HDFS. It separates the always-on data plane (`hfg-gateway-app`) from the management/control plane (`hfg-manager-api`). The React management UI is compiled into the Manager executable JAR, so production runtime does not require Node.js or a separate Nginx service.

## Architecture

- Multiple service groups, each consisting of one VIP and an Active/Standby HFG Gateway pair.
- Direct HDFS streaming through a storage abstraction; protocol modules never import Hadoop classes.
- Versioned, signed configuration snapshots allow gateways to continue serving published users while Manager is unavailable.
- Manager imports Hadoop XML and keytab as a validated ZIP; gateways receive HDFS configuration through the authenticated control channel.
- Manager issues downloadable per-node gateway client certificates using Java cryptography APIs.
- PostgreSQL 17 or MySQL 8 stores configuration and audit data; a configurable `logs` database stores partitioned transfer and quota analytics.
- Prometheus is limited to JVM, process, thread, connection-pool and health metrics; business charts query the `logs` table.
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
| `hfg-manager-infrastructure` | JPA, PostgreSQL/MySQL migrations and snapshot persistence |
| `hfg-manager-api` | REST management API, Prometheus proxy and embedded management UI |
| `hfg-manager-web` | React + TypeScript + Semi Design UI source (bundled into Manager JAR) |

## Build

```bash
make package
```

The command validates the frontend and backend, builds the UI, and packages it into:

- `hfg-manager-api/target/hfg-manager-api-0.1.2.jar`
- `hfg-gateway-app/target/hfg-gateway-app-0.1.2.jar`

Only Java 17 is needed to run these artifacts. Node.js is a build-time dependency only.

Run the Docker development baseline:

```bash
docker compose -f deploy/docker/compose.yaml up -d
```

Before production deployment, read:

- [System design and implementation](docs/implementation.md)
- [Protocol capability matrix](docs/protocol-capability.md)
- [Configuration reference](docs/configuration.md)
- [Deployment runbook](docs/deployment.md)
- [Compiled Linux x86_64 medium deployment](docs/package-deployment.md)
- [Test and acceptance plan](docs/testing.md)


## Database selection

PostgreSQL is the default. For MySQL 8, set `HFG_DB_URL=jdbc:mysql://...` and
`HFG_DB_MIGRATION_LOCATION=classpath:db/mysql`. Set `HFG_LOGS_DB_URL` to use a
separate analytics database; leaving it empty stores the partitioned `logs` table in the
management database. See the configuration and deployment guides for all variables.
