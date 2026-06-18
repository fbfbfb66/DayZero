# DayZero Supabase Schema Verification

This document is a verification checklist for the remote sync schema. The Android app must continue to use Room as the local source of truth. Supabase Table Editor is only for observing and checking schema/data during development; it is not a business-data source for UI.

## Expected Tables

The current sync gateway expects these public tables:

- `daily_records`
- `meals`
- `food_entries`
- `weight_records`

The migration source of truth is `supabase/migrations/20260618120000_dayzero_core_records.sql`.

## Required Common Fields

Each business table must include:

- `id uuid primary key`
- `user_id uuid not null`
- `client_id text not null`
- `local_owner_id text null`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`
- `deleted_at timestamptz null`
- `schema_version int not null default 1`

Each table must include `unique(user_id, client_id)` for idempotent PostgREST upsert.

## Table-Specific Fields

`daily_records`:

- `record_date date not null`
- `status text not null`
- `total_calories int not null default 0`
- `weight_kg numeric(6,2) null`
- `ai_summary text null`

`meals`:

- `daily_record_id uuid not null`
- `daily_record_client_id text not null`
- `meal_type text not null`
- `has_photo boolean not null default false`
- `subtotal_calories int not null default 0`

`food_entries`:

- `meal_id uuid not null`
- `daily_record_id uuid not null`
- `meal_client_id text not null`
- `daily_record_client_id text not null`
- `name text not null`
- `quantity text not null`
- `estimated_calories int not null`
- `confidence text not null default 'high'`
- `raw_estimate_json jsonb null`

`weight_records`:

- `daily_record_id uuid null`
- `daily_record_client_id text null`
- `measured_date date not null`
- `weight_kg numeric(6,2) not null`
- `source text not null default 'confirm_card'`

## RLS And Policies

RLS must be enabled on every table:

- `alter table public.daily_records enable row level security;`
- `alter table public.meals enable row level security;`
- `alter table public.food_entries enable row level security;`
- `alter table public.weight_records enable row level security;`

Each table must have policies that restrict rows to `user_id = auth.uid()` for:

- `select`
- `insert`
- `update`
- `delete`

Postgres RLS requires a matching `select` policy for updates to find rows. Do not remove select policies when debugging update failures.

## Data API Access

Recent Supabase projects may not expose new tables to the Data API automatically. If PostgREST returns table-access errors while RLS policies are correct, check the project's Data API settings and grants. When granting access to `anon` or `authenticated`, RLS must remain enabled.

The Android client may use only the configured publishable/anon key. A `service_role` key must never be stored in Android source, resources, build config, logs, or runtime preferences.

## Sync Semantics

`client_id` is the stable local entity id used for idempotent upsert. The gateway sends PostgREST writes with `on_conflict=user_id,client_id`.

`updated_at` is updated by the Android gateway on each remote write attempt that reaches Supabase.

Deletes are soft deletes:

- Set `deleted_at` to a timestamp.
- Set `updated_at` to the current timestamp.
- Do not use SQL `DELETE` for normal record sync.

`local_owner_id` is only a client migration/binding helper. It must not be used as a remote authorization boundary.

## Table Editor Checklist

For each table, verify:

- The table exists in the expected schema.
- Required common fields exist with expected types.
- Table-specific fields exist with expected types.
- `deleted_at` exists and is nullable.
- `updated_at` exists and is `timestamptz`.
- `unique(user_id, client_id)` exists.
- RLS is enabled.
- Select/insert/update/delete policies use `user_id = auth.uid()`.
- The table is exposed to the Data API if the app needs PostgREST access.
- The `authenticated` role can select/insert/update/delete these RLS-protected tables through the Data API.

## Idempotency Verification

To verify duplicate upsert behavior:

1. Use one authenticated anonymous user.
2. Upsert a row with a fixed `client_id`.
3. Upsert the same logical row again with the same `user_id` and `client_id`.
4. Confirm the table still has one row for that pair.
5. Confirm `updated_at` changes on the second write.

## RLS Verification

To verify isolation:

1. Create or use two different authenticated users.
2. Insert rows for user A.
3. Query as user B through PostgREST.
4. Confirm user B cannot select user A rows.
5. Confirm user B cannot update or soft-delete user A rows.

## Current Non-Goals

The current stage does not implement:

- Remote pull into Room.
- Multi-device conflict merging.
- Realtime.
- Chat transcript sync.
- User-visible login UI.
- Any Android use of `service_role`.
