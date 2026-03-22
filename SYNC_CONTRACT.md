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

## Core Payloads

- `ConversationDeltaBatch`
- `MessageDeltaBatch`
- `WatchMutation`
- `MutationAck`

## Required Behavior

- Phone is authoritative for conversation and message state.
- Watch sends user actions as `WatchMutation` with `clientMutationId`.
- Phone responds with `MutationAck` and advances server cursor/version.
- All payloads must include `schemaVersion`.

## Mutation Types

- `REPLY`
- `MARK_READ`
- `ARCHIVE`
- `MUTE`
- `UNMUTE`
