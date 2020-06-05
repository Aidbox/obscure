# Obscure

## Obscure envs

```
KUBE_BASE=http://kube-url/
KUBE_TOKEN=sa-token
TELEGRAM_TOKEN=tg-bot-token
GHOST_CONFIG=/path/to/ghost.yaml
GHOST_DATA=/path/to/persistent/dir
```

## Obscure config

```yaml
telegram:
  chats:
    default: -123532
    common: 8798743

instances:
  prod-pg:
    kind: Deployment
    namespace: prod
    name: pg

jobs:
  basebackup:
    desc: Makes basebackups
    when:
      every: day
      at: {hour: 7}

    steps:
    - type: tg
      send: Creating basebackup
    - type: bash
      instance: prod-pg
      script: |
        source /data/.env && /data/wal-g backup-push /data
      on-error:
      - type: tg
        chat: common # if you not specify chat, deafault will be taken
        send: Failed creating basebackup
    - type: tg
      update: Created basebackup
```




# Release notes

## 1.0

Init

# Dreams

Manage postgresql and possibly aidbox
services in kubernetes

## Alerts

PostgreSQL

* database is alive
* locks in database
* disk space
* replication monitoring 
  * log shiping 
    * wal logs are published and stored
  * streaming replication
    * check replicas status
    * check slots

Aidbox

* exceptions
* jvm memory

## Maintains

* base backups & walls (+ cleanup)
 * check backups
* backups (SQL)
 * check backups

* create replica (warm or streaming)
* switchover 
* [failover - in future]


## Metrics & Reports

Sources:

* logs
* metric

Daily report to telegram
