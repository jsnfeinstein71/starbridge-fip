# FIP CLI Usage

FIP CLI commands operate on a file-backed shard store selected with `--store`.
The current storage format is local files under that directory. Production encryption-at-rest and key management are not implemented yet.

Supported development content modes:

- `PLAINTEXT`: stores inspectable development content.
- `ENCRYPTED_PLACEHOLDER`: stores a protected-content placeholder for operator testing. This is not production cryptography and cannot be reconstructed by the plaintext development exposure path.

Run commands through Gradle during development:

```bash
./gradlew :fip-cli:run --args="<command> <options>"
```

## Save A Shard

```bash
./gradlew :fip-cli:run --args="save-shard --store ./data/fip-shards --id shard-core-001 --subject user-123 --type IDENTITY_CORE --version 1 --payload 'stable identity descriptor' --source operator-import --observed-at 2026-04-20T15:00:00Z --content-mode PLAINTEXT --tag durable --tag governed"
```

To create a placeholder protected-content shard for testing:

```bash
./gradlew :fip-cli:run --args="save-shard --store ./data/fip-shards --id shard-core-protected-001 --subject user-123 --type IDENTITY_CORE --version 1 --payload 'operator test protected content' --source operator-import --observed-at 2026-04-20T15:05:00Z --content-mode ENCRYPTED_PLACEHOLDER --tag protected-demo"
```

## Load A Shard

```bash
./gradlew :fip-cli:run --args="load-shard --store ./data/fip-shards --id shard-core-001"
```

Load and list output includes `contentMode` and `contentAlgorithm`. Placeholder encrypted records print `payload=<protected-content>` rather than the original input text.

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
Shards with placeholder encrypted content are excluded with `CONTENT_NOT_EXPOSABLE` provenance in the current plaintext development mode.
To write a durable reconstruction evidence artifact, pass `--artifact-out <path>`. The artifact is an explicit key-value text file with operation type, timestamp, summary counts, selected/included/skipped shards, reconstruction exclusions, and provenance entries.

To seed reconstruction with explicit shards, pass repeatable `--shard-id <id>`.
To use a stored subject graph map when one exists, pass `--use-graph-map`. If no graph map exists for the subject, reconstruction falls back to the default bounded selector.
When graph-aware reconstruction expands from explicit seed shards, explicit seeds are selected first, then directly linked shards are ordered by stronger link weight and node priority.
Reconstruction output distinguishes `selected`, `selection-skip`, and `reconstruction-exclusion` lines so operators can see selection-stage skips separately from runtime exclusions such as `CONTENT_NOT_EXPOSABLE`.
Missing explicit shard ids are reported as `explicit-unresolved` lines and do not receive `EXPLICIT_SEED` influence.

```bash
./gradlew :fip-cli:run --args="reconstruct --store ./data/fip-shards --request-id recon-graph-001 --subject user-123 --task-type operator-check --surface cli --max-shards 8 --shard-id shard-core-001 --use-graph-map"
```

```bash
./gradlew :fip-cli:run --args="reconstruct --store ./data/fip-shards --request-id recon-audit-001 --subject user-123 --task-type operator-check --surface cli --max-shards 8 --artifact-out ./data/fip-artifacts/recon-audit-001.properties"
```

## Save A Graph Map

Graph maps are stored as explicit file-backed metadata for shard selection demos. Nodes are subject-scoped and links can be represented both as node adjacency and weighted link records. Node priorities and link weights must be non-negative integers.

```bash
./gradlew :fip-cli:run --args="save-graph-map --store ./data/fip-shards --subject user-123 --node shard-core-001:IDENTITY_CORE:100 --node shard-prefs-001:IDENTITY_PREFS:80 --node-link shard-core-001:shard-prefs-001 --link shard-core-001:shard-prefs-001:75"
```

## Load A Graph Map

```bash
./gradlew :fip-cli:run --args="load-graph-map --store ./data/fip-shards --subject user-123"
```

## Rebuild A Graph Map

Regenerate a subject graph map from current shard files. This creates nodes for the subject's shards, preserves valid existing graph metadata when present, and adds simple deterministic links from anchor shards to related shards. The CLI prints `regenerationRule=SUBJECT_SHARDS_WITH_ANCHOR_LINKS` so the generated map is identifiable.

```bash
./gradlew :fip-cli:run --args="rebuild-graph-map --store ./data/fip-shards --subject user-123"
```

## Write Back

```bash
./gradlew :fip-cli:run --args="write-back --store ./data/fip-shards --request-id wb-001 --subject user-123 --task-type operator-update --surface cli --type IDENTITY_PREFS --payload 'prefers concise operational summaries' --source operator-update --content-mode PLAINTEXT --replace-id shard-prefs-001 --tag preference --max-replacement-source-shards 4 --max-payload-bytes 2048"
```

Write-back issues replacement shards and marks selected prior shard ids for deletion. It does not mutate existing shard records in place.
If a stored graph map exists for the subject, write-back removes deleted shard ids from that map, inserts replacement shard ids, remaps direct links where possible, and prints `graphMapUpdated=true`. Without a subject graph map, write-back preserves the existing fallback behavior and prints `graphMapUpdated=false`.
To export write-back evidence, pass `--artifact-out <path>`. The artifact records request and subject identifiers, created/replaced/deleted counts, boundedness, decision time, and created/replaced/deleted shard ids.

To issue a placeholder protected-content replacement:

```bash
./gradlew :fip-cli:run --args="write-back --store ./data/fip-shards --request-id wb-protected-001 --subject user-123 --task-type operator-update --surface cli --type IDENTITY_PREFS --payload 'operator test protected preference' --source operator-update --content-mode ENCRYPTED_PLACEHOLDER --replace-id shard-prefs-001 --tag protected-demo"
```

```bash
./gradlew :fip-cli:run --args="write-back --store ./data/fip-shards --request-id wb-audit-001 --subject user-123 --task-type operator-update --surface cli --type IDENTITY_PREFS --payload 'prefers concise operational summaries' --source operator-update --replace-id shard-prefs-001 --artifact-out ./data/fip-artifacts/wb-audit-001.properties"
```

## Check Integrity

```bash
./gradlew :fip-cli:run --args="check-integrity --store ./data/fip-shards"
```

The integrity check reports checked, valid, and invalid record counts, then prints each validation issue with location, severity, code, message, shard id, and subject id when available.
To export integrity evidence, pass `--artifact-out <path>`. The artifact records checked/valid/invalid counts, overall validity, each checked record, and each validation issue.

```bash
./gradlew :fip-cli:run --args="check-integrity --store ./data/fip-shards --artifact-out ./data/fip-artifacts/integrity-001.properties"
```
