# FIP repository instructions

## What this project is
FIP is a standalone governed identity reconstruction engine.
It is infrastructure, not generic chatbot memory.

## Core architecture rules
- Keep FIP Linux-hostable and Kotlin/JVM-first.
- Do not introduce Android dependencies into fip-core.
- Keep provider-specific logic out of fip-core.
- Favor strongly typed domain models and auditable outputs.
- File-backed storage first, service/API second.

## Governance rules
- Default-deny SYSTEM_META unless explicitly allowed.
- Preserve bounded reconstruction behavior.
- Track inclusion and exclusion decisions through provenance.
- Avoid uncontrolled full-context loading patterns.

## Working style
- Make small, surgical changes.
- Do not refactor broadly unless asked.
- Prefer tests alongside behavior changes.
- Keep names clear and infrastructure-oriented.

## Build and verification
- Use ./gradlew build
- Use ./gradlew test
