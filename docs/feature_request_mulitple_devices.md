# Multi-Device Plan — MikeProducts (products)

> Part of the MikeOS **multi-device** rollout (one user `mikaelwestoo@gmail.com`, two phones:
> **Note 10** + **Pixel 10 Pro**). Read the authoritative design first:
> **`mikeos-architecture/docs/MULTI-DEVICE.md`** — this file applies it to **this app**.
> Repo: `mikeos-products` · Cloud: `mikeos-products-cloud` · Category: **user-shared**.

## 0. Bottom line
**Your data is about the USER, not the phone.** Both handsets read and write the SAME cloud rows, so state converges automatically. Your job: make sure you **pull fresh state on every heartbeat** (not only push), and resolve concurrent edits from two phones cleanly.

**What this app manages:** product knowledge/catalog, find-where-to-buy

## 1. Sync rule for this app
Cloud is the source of truth (user-scoped). Each device's Room cache is just a cache — refresh it on the beat. For mutable records use **last-write-wins by ISO-8601 `updated_at`**; for lists/sets use **append + soft-delete (`deleted_at`)** so two phones never clobber each other.

> App-specific: User-level knowledge shared across phones.

## 2. Identity — how the system tells the two phones apart
- Keep `X-API-KEY → user_id` (both phones = same user → shared data). **Do not split the user.**
- **Send `X-DEVICE-ID: <daemon device_id>` on every cloud call** (comes free from the shared core once the
  fleet-wide core change lands). This is how the cloud knows which phone is calling. It is
  **write-scoped, never admin** — it identifies only.
- Your agent already registers in the hive as `mikaelwestoo/<device>/MikeProducts` — the device
  segment is your per-device identity in the hive.

## 3. Cloud changes (`mikeos-products-cloud`)
none (user-scoped); add updated_at
- Migrations idempotent (`IF NOT EXISTS`), no reserved-keyword columns, parameterized SQL, ISO-8601
  timestamps, never-trust-200 (verify the row stored, including its `device_id` when relevant).
- If adding `device_id`: make it nullable, include it in the unique key where rows are per-device, and
  **backfill existing rows with the Note 10 id** (`3b5a6e4d-541e-4891-93fb-b77234e3ebf5`).

## 4. App changes (`mikeos-products`)
pull catalog on beat; either phone identifies/adds
- Ensure a **pull-on-heartbeat** path so this device's Room cache reflects the shared cloud state.
- Keep it offline-first: the local cache must still work with no network and reconcile on the next beat.
- Deterministic on the heartbeat (not LLM-gated) for any proactive/sync action.

## 5. Hive / cross-agent
product.resolve same-device
- **Peer queries prefer the SAME device** (match the `<device>` segment) — don't reach the other phone's
  agents unless the feature is explicitly user-level.

## 6. Notifications — no double-buzz
Rule for this app: **primary-only**.
- User-level notifications: only the **primary/active device** raises them (gate on
  `GET /api/devices/primary` or a cloud 'notified' claim keyed by event id).
- Device-local notifications: always local.

## 7. Checklist
- [ ] Core change landed (app sends `X-DEVICE-ID`) — fleet-wide, tracked in the master plan.
- [ ] Cloud: add updated_at for LWW where missing.
- [ ] App: pull-on-heartbeat refresh; merge concurrent edits (LWW / append+soft-delete).
- [ ] Notifications deduped per rule above.
- [ ] Verify with BOTH phones registered (`GET /api/devices` shows 2).

## 8. Acceptance criteria
- With both phones on, `GET /api/devices` lists Note 10 **and** Pixel 10 as the same user.
- The same product knowledge/catalog appears identically on both phones (shared).
- No duplicate notifications for the same user-level event.
- No data from one phone incorrectly applied to the other.

## 9. House rules (unchanged)
`X-DEVICE-ID` identifies only (never admin). ISO-8601 timestamps. Numeric fields numeric. Idempotent
migrations, no reserved-keyword columns, parameterized SQL. Never trust HTTP 200 — verify stored data.
One shared location (daemon `/api/location`). Free GPU only; cost = zero. Never load a whole file into
RAM (cap ~30 MB).

---
*Generated from `mikeos-architecture/docs/multi-device-index.json` by the multi-device planner cron.
Master design: `mikeos-architecture/docs/MULTI-DEVICE.md`.*
