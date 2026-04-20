# FIP Architecture

## Purpose

FIP is a standalone governed identity reconstruction engine.

FIP is not generic chatbot memory, not a monolithic profile store, and not a plain retrieval layer. Its purpose is to store identity-related material in fractured form and support controlled, bounded reconstruction only for authorized runtime use.

The architectural intent comes from the earlier provisional basis for FIP, but the current build is the controlling implementation direction for the standalone non-provisional path.

## Core architectural principles

1. Identity is not stored monolithically.
2. Reconstruction is controlled, bounded, and intentional.
3. Identity should not exist as a globally assembled resting artifact.
4. Reconstruction should occur only for active runtime use.
5. Storage, mapping, reconstruction, and write-back are distinct concerns.
6. Provenance must exist for inclusion and exclusion decisions.
7. Protected/system classes must remain default-denied unless explicitly allowed.
8. FIP must remain provider-agnostic in core.

## What FIP is

FIP is a governed identity reconstruction system composed of separable layers:

- shard storage
- shard classification
- selection and mapping logic
- reconstruction policy
- reconstruction engine
- provenance and integrity tracking
- write-back / re-sharding logic
- storage abstraction

## What FIP is not

FIP is not:

- a chat history dump
- a generic memory file
- a vector database wrapper
- a persona toy
- a profile cache
- a provider-owned continuity layer

## Current intended shard taxonomy

The current preferred shard taxonomy is:

- `IDENTITY_CORE`
- `IDENTITY_PREFS`
- `BEHAVIOR_RULES`
- `MEMORY_ANCHORS`
- `MEMORY_EPHEMERAL`
- `ACL_BASELINE`
- `ACL_STATE`
- `BCAL_REFINEMENTS`
- `SYSTEM_META`

These types are preferred over generic social-memory categories because they better reflect the governed architecture and future filing direction.

## Meaning of the shard taxonomy

### `IDENTITY_CORE`
Durable core identity facts or stable identity descriptors needed for continuity.

### `IDENTITY_PREFS`
Persistent user preferences or operating preferences relevant to reconstruction.

### `BEHAVIOR_RULES`
Stable behavior constraints, response tendencies, or operating rules.

### `MEMORY_ANCHORS`
Durable memory reference points that are important for continuity.

### `MEMORY_EPHEMERAL`
Temporary or highly perishable memory that should generally not be reconstructed by default.

### `ACL_BASELINE`
Adaptive cognitive baseline material if used by the system.

### `ACL_STATE`
Current adaptive/drift state material that is typically protected and not default reconstruction material.

### `BCAL_REFINEMENTS`
Refinement-layer material derived from governed adaptation processes and typically protected by default.

### `SYSTEM_META`
Protected system-level metadata. This must remain default-denied unless explicitly allowed.

## Default reconstruction stance

The default reconstruction stance is bounded and conservative.

Preferred default inclusion set for early reconstruction work:

- `IDENTITY_CORE`
- `IDENTITY_PREFS`
- `BEHAVIOR_RULES`
- `MEMORY_ANCHORS`

Preferred default exclusion set unless explicitly allowed:

- `MEMORY_EPHEMERAL`
- `ACL_STATE`
- `BCAL_REFINEMENTS`
- `SYSTEM_META`

Other types may be included by policy, but the default posture should remain narrow.

## Reconstruction constraints

A reconstruction request should be shaped by active-use context, not by a desire to assemble everything.

Reconstruction should be constrained by values such as:

- subject or entity identifier
- request identifier
- task type
- surface
- allowed shard types
- excluded shard types
- maximum shard count
- maximum payload budget
- explicit allow rules for protected classes
- provenance inclusion

## Reconstruction result expectations

A reconstruction result should provide:

- the request identity
- the target subject identity
- included shards
- excluded shards or exclusion metadata
- provenance for inclusion and exclusion decisions
- boundedness indicators
- any truncation or payload-limit indicators

The result should remain infrastructure-oriented and not adopt assistant/chat framing.

## Provenance requirements

FIP must preserve provenance for reconstruction decisions.

At minimum, provenance should capture:

- request identifier
- shard identifier
- shard type
- decision
- reason
- timestamp

This is required for auditability, explainability, and policy verification.

## Integrity and validation principles

FIP should support integrity validation around:

- shard identity validity
- type validity
- subject consistency
- protected-type default denial
- bounded result size
- reconstruction consistency
- stale or orphaned shard detection later

## Write-back and re-sharding direction

Write-back should not be treated as simple in-place append behavior.

The intended direction is:

1. active runtime update occurs
2. updated identity representation is evaluated
3. new fractured representation is produced
4. shards may be reissued or rewritten
5. protected/system classes remain controlled
6. provenance and integrity state are updated

The exact mechanics can evolve in the current build, but the architecture should preserve the fractured-identity principle and avoid stable monolithic rest states.

## Storage abstraction direction

FIP core should not depend on one storage form.

Storage should remain abstract enough to support:

- local file-backed storage
- future database-backed storage
- future distributed storage
- future hardware-backed protection

The first implementation path is file-backed storage.

## Module expectations

### `fip-core`
Contains domain models, policies, reconstruction logic, provenance models, and core validation. No Android dependencies. No provider logic.

### `fip-storage-file`
Contains file-backed storage implementation for shards and related metadata.

### `fip-cli`
Contains testing and operator-facing commands for inspection, reconstruction, and proof workflows.

### `fip-service`
Contains service/API wrapper layers around the core engine.

## Non-goals for current core work

Do not drift into:

- provider-specific integrations
- Android-specific code in core
- generic chat-memory abstractions
- broad refactors unrelated to FIP
- consumer-facing assistant behavior logic inside core

## Engineering posture

Favor:

- strong typing
- boundedness
- explicit defaults
- auditability
- provider independence
- surgical changes
- test-backed behavior

Avoid:

- vague memory language
- overbroad reconstruction
- hidden inclusion behavior
- implicit protected-type access
- architecture drift away from fractured identity
