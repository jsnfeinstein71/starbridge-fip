# FIP Master Spine

## Purpose

This document is the canonical in-repo context spine for FIP. Future architecture,
implementation, grant, patent, and operator-facing work should treat this file as
the first alignment reference before relying on prior chat memory or external
project discussions.

FIP means Fractured Identity Protocol.

FIP is the canonical protocol and doctrine for governed reconstruction of
fractured identity, context, and assets. It is not merely encryption, not merely
RAG permissions, not merely a filesystem, and not merely PSA or StarBridge.

## Core Doctrine

Sensitive identity, context, and assets should not be stored as one complete
reconstructable bundle by default. Stored state should be fractured into
shards or objects and encrypted at rest.

Reconstruction should occur only through an authorized runtime path. That path
must be purpose-bound, policy-bound, metadata-aware, and auditable. Authorized
views should be bounded by role, purpose, and context.

Reconstruction should occur in volatile memory by default. Full-bundle exposure
should not be the default.

The AI model or provider should not decrypt shards and should not hold
reconstruction authority. The runtime governs reconstruction, exposure, export,
and audit. Provider/model output remains proposal-only, not authoritative.

## Surface Map

The standalone FIP repo is the canonical protocol and implementation source of
truth.

FIP Vault is the near-term practical implementation target: a governed object
vault before any mounted filesystem.

FIPFS is a future filesystem or virtual-filesystem embodiment of FIP.

AIFS is category language for an AI-native filesystem. FIPFS would be the
FIP-powered implementation in that category.

cArla PSA is a practical testbed for governed views over proprietary documents
and safe reconstruction behavior, but it is not the canonical FIP
implementation.

StarBridge is the authority/runtime governance layer that decides whether
reconstruction, exposure, proposal, export, or execution is allowed.

cArla, Persona, and AAIR are human-facing intelligence layers that may consume
authorized FIP views.

Grant, patent, and NIST materials are external framing around breach-resistant
identity storage, privacy-preserving reconstruction, cryptographic/privacy
engineering, auditability, and measurable security outcomes.

## Near-Term Implementation Direction

The near-term target is FIP Vault, not a full mounted filesystem.

FIP Vault should eventually support operator capabilities such as:

- `fip put`
- `fip list`
- `fip inspect`
- `fip reconstruct`
- `fip expose`
- `fip export`
- `fip audit`

FIP Vault should demonstrate that the same source material can produce different
authorized reconstructions depending on purpose, including:

- `owner-full-local`
- `llm-safe`
- `grant-safe`
- `public-summary`
- `patent-sensitive-local`

## Non-Negotiable Principles

- Default deny for sensitive, system, and internal metadata.
- Purpose-bound reconstruction.
- Bounded authorized views.
- Native metadata-condition enforcement.
- Provenance and audit logs.
- No full-bundle exposure by default.
- Volatile reconstruction by default.
- Fault tolerance is critical.
- Disaster recovery must be considered.
- Key management must not be hand-waved.
- Provider/model output is proposal-only.
- Do not overbuild compliance polish before core FIP Vault behavior works.

## Anti-Drift Rules

- Do not redefine FIP as simple encryption.
- Do not collapse FIP into PSA.
- Do not collapse FIP into StarBridge.
- Do not collapse FIP into RAG permissions.
- Do not start with a mounted filesystem unless explicitly approved.
- Do not treat FIPFS as replacing all filesystems by default.
- Do not build a giant theoretical treatise instead of preserving actionable
  direction.
- Keep FIP Vault as the near-term build target.

## NIST And Funding Framing

FIP is a strong candidate for NIST-style cybersecurity and privacy funding
because it addresses breach-resistant identity storage, privacy-preserving
reconstruction, auditable access/reconstruction, and identity exposure
minimization.

Useful thesis:

> HIPAA governs covered entities. Encryption protects stored bytes. FIP governs
> whether identity can be reconstructed into a usable whole.

## Working Guidance

When future work is architecture-sensitive, preserve the doctrine above before
adding runtime behavior. Prefer small implementation steps that make FIP Vault
more real: governed object storage, metadata-aware policy, bounded
reconstruction, audit artifacts, resilient recovery, and credible key-management
boundaries.

Do not implement runtime code from this document alone. Use it to keep design
decisions aligned, then make scoped, test-backed changes in the relevant module.
