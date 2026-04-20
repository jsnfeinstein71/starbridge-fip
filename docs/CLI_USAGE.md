# FIP CLI Usage

FIP CLI commands operate on a file-backed shard store selected with `--store`.
The current storage format is local files under that directory. Encryption-at-rest is not implemented yet.

Run commands through Gradle during development:

```bash
./gradlew :fip-cli:run --args="<command> <options>"
```

## Save A Shard

```bash
./gradlew :fip-cli:run --args="save-shard --store ./data/fip-shards --id shard-core-001 --subject user-123 --type IDENTITY_CORE --version 1 --payload 'stable identity descriptor' --source operator-import --observed-at 2026-04-20T15:00:00Z --tag durable --tag governed"
```

## Load A Shard

```bash
./gradlew :fip-cli:run --args="load-shard --store ./data/fip-shards --id shard-core-001"
```

## List Shards For A Subject

```bash
./gradlew :fip-cli:run --args="list-shards --store ./data/fip-shards --subject user-123"
```

## Delete A Shard

```bash
./gradlew :fip-cli:run --args="delete-shard --store ./data/fip-shards --id shard-core-001"
```

## Reconstruct

```bash
./gradlew :fip-cli:run --args="reconstruct --store ./data/fip-shards --request-id recon-001 --subject user-123 --task-type operator-check --surface cli --max-shards 8 --max-payload-bytes 4096 --allowed-types IDENTITY_CORE,IDENTITY_PREFS,BEHAVIOR_RULES,MEMORY_ANCHORS"
```

`SYSTEM_META` remains default-denied. To include it, pass both `--allow-system-meta` and an allowed type set that includes `SYSTEM_META`.

## Write Back

```bash
./gradlew :fip-cli:run --args="write-back --store ./data/fip-shards --request-id wb-001 --subject user-123 --task-type operator-update --surface cli --type IDENTITY_PREFS --payload 'prefers concise operational summaries' --source operator-update --replace-id shard-prefs-001 --tag preference --max-replacement-source-shards 4 --max-payload-bytes 2048"
```

Write-back issues replacement shards and marks selected prior shard ids for deletion. It does not mutate existing shard records in place.

## Check Integrity

```bash
./gradlew :fip-cli:run --args="check-integrity --store ./data/fip-shards"
```

The integrity check reports checked, valid, and invalid record counts, then prints each validation issue with location, severity, code, message, shard id, and subject id when available.
