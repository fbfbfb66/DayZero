-- DayZero core record sync draft.
-- This migration is intentionally a design artifact for review and is not applied automatically.

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  user_id uuid not null,
  client_id text not null,
  local_owner_id text null,
  display_name text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1,
  constraint profiles_user_client_unique unique (user_id, client_id),
  constraint profiles_user_is_self check (id = user_id)
);

create table if not exists public.daily_records (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  local_owner_id text null,
  record_date date not null,
  status text not null,
  total_calories int not null default 0,
  weight_kg numeric(6,2) null,
  ai_summary text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1,
  constraint daily_records_user_client_unique unique (user_id, client_id)
);

create table if not exists public.meals (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  daily_record_id uuid not null references public.daily_records(id) on delete cascade,
  client_id text not null,
  local_owner_id text null,
  daily_record_client_id text not null,
  meal_type text not null,
  has_photo boolean not null default false,
  subtotal_calories int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1,
  constraint meals_user_client_unique unique (user_id, client_id)
);

create table if not exists public.food_entries (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  meal_id uuid not null references public.meals(id) on delete cascade,
  daily_record_id uuid not null references public.daily_records(id) on delete cascade,
  client_id text not null,
  local_owner_id text null,
  meal_client_id text not null,
  daily_record_client_id text not null,
  name text not null,
  quantity text not null,
  estimated_calories int not null,
  confidence text not null default 'high',
  raw_estimate_json jsonb null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1,
  constraint food_entries_user_client_unique unique (user_id, client_id)
);

create table if not exists public.weight_records (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  daily_record_id uuid null references public.daily_records(id) on delete set null,
  client_id text not null,
  local_owner_id text null,
  daily_record_client_id text null,
  measured_date date not null,
  weight_kg numeric(6,2) not null,
  source text not null default 'confirm_card',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1,
  constraint weight_records_user_client_unique unique (user_id, client_id)
);

alter table public.profiles enable row level security;
alter table public.daily_records enable row level security;
alter table public.meals enable row level security;
alter table public.food_entries enable row level security;
alter table public.weight_records enable row level security;

create policy "profiles_select_own" on public.profiles for select using (user_id = auth.uid());
create policy "profiles_insert_own" on public.profiles for insert with check (user_id = auth.uid());
create policy "profiles_update_own" on public.profiles for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "profiles_delete_own" on public.profiles for delete using (user_id = auth.uid());

create policy "daily_records_select_own" on public.daily_records for select using (user_id = auth.uid());
create policy "daily_records_insert_own" on public.daily_records for insert with check (user_id = auth.uid());
create policy "daily_records_update_own" on public.daily_records for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "daily_records_delete_own" on public.daily_records for delete using (user_id = auth.uid());

create policy "meals_select_own" on public.meals for select using (user_id = auth.uid());
create policy "meals_insert_own" on public.meals for insert with check (user_id = auth.uid());
create policy "meals_update_own" on public.meals for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "meals_delete_own" on public.meals for delete using (user_id = auth.uid());

create policy "food_entries_select_own" on public.food_entries for select using (user_id = auth.uid());
create policy "food_entries_insert_own" on public.food_entries for insert with check (user_id = auth.uid());
create policy "food_entries_update_own" on public.food_entries for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "food_entries_delete_own" on public.food_entries for delete using (user_id = auth.uid());

create policy "weight_records_select_own" on public.weight_records for select using (user_id = auth.uid());
create policy "weight_records_insert_own" on public.weight_records for insert with check (user_id = auth.uid());
create policy "weight_records_update_own" on public.weight_records for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "weight_records_delete_own" on public.weight_records for delete using (user_id = auth.uid());

comment on column public.profiles.local_owner_id is
  'Client-side migration/binding helper only. Do not use for remote authorization.';
comment on column public.daily_records.local_owner_id is
  'Client-side migration/binding helper only. Do not use for remote authorization.';
comment on column public.meals.local_owner_id is
  'Client-side migration/binding helper only. Do not use for remote authorization.';
comment on column public.food_entries.local_owner_id is
  'Client-side migration/binding helper only. Do not use for remote authorization.';
comment on column public.weight_records.local_owner_id is
  'Client-side migration/binding helper only. Do not use for remote authorization.';
