# Wessage Sync Contract (v1)

This document defines the shared payload contract used between:

- Wear app (`Wessage`)
- Phone app (`Messages-phone`)

## Schema Version

- `SYNC_SCHEMA_VERSION = 1`

## Data Paths

- `/wessage/sync/v1/conversations`
- `/wessage/sync/v1/messages`
- `/wessage/sync/v1/mutation`
- `/wessage/sync/v1/ack`
- `/wessage/sync/v1/bootstrap_request`
- `/wessage/sync/v1/key_exchange/request`
- `/wessage/sync/v1/key_exchange/response`

## Core Payloads

- `ConversationDeltaBatch`
- `MessageDeltaBatch`
- `WatchMutation`
- `MutationAck`
- `BootstrapRequest` (`limit`, `offset`)

## Required Behavior

- Phone is authoritative for conversation and message state.
- Watch sends user actions as `WatchMutation` with `clientMutationId`.
- Watch can request paged bootstrap windows via `BootstrapRequest`.
- Phone responds with `MutationAck` and advances server cursor/version.
- All payloads must include `schemaVersion`.
- Key exchange payloads are JSON and contain the sender P-256 public key.
- Sync payloads on `conversations`, `messages`, `mutation`, and `ack` are encrypted with AES-256-GCM using an ECDH-derived key.
- Receivers must reject replayed encrypted envelopes based on monotonic counters per sender device id.

## Mutation Types

- `REPLY`
- `MARK_READ`
- `ARCHIVE`
- `MUTE`
- `UNMUTE`
