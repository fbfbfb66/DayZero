-- DayZero DB-SYNC-11A canonical remote schema.
-- Migration is safe to review and safe to run against an empty or partially-created public schema.
-- Table Editor is only for verification; this file is the versioned schema source of truth.

create extension if not exists pgcrypto;

create or replace function public.dayzero_set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create table if not exists public.daily_records (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  local_date date not null,
  timezone text null,
  note text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1
);

create table if not exists public.meals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  daily_record_client_id text not null,
  meal_type text null,
  logged_at timestamptz null,
  display_order int null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1
);

create table if not exists public.food_entries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  meal_client_id text not null,
  name text not null,
  amount_text text null,
  grams numeric null,
  calories numeric null,
  protein_g numeric null,
  carbs_g numeric null,
  fat_g numeric null,
  confidence numeric null,
  source text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1
);

create table if not exists public.weight_records (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  client_id text not null,
  local_date date not null,
  measured_at timestamptz null,
  weight_kg numeric not null,
  source text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  schema_version int not null default 1
);

alter table public.daily_records add column if not exists id uuid default gen_random_uuid();
alter table public.daily_records add column if not exists user_id uuid references auth.users(id) on delete cascade;
alter table public.daily_records add column if not exists client_id text;
alter table public.daily_records add column if not exists local_date date;
alter table public.daily_records add column if not exists timezone text;
alter table public.daily_records add column if not exists note text;
alter table public.daily_records add column if not exists created_at timestamptz not null default now();
alter table public.daily_records add column if not exists updated_at timestamptz not null default now();
alter table public.daily_records add column if not exists deleted_at timestamptz null;
alter table public.daily_records add column if not exists schema_version int not null default 1;

alter table public.meals add column if not exists id uuid default gen_random_uuid();
alter table public.meals add column if not exists user_id uuid references auth.users(id) on delete cascade;
alter table public.meals add column if not exists client_id text;
alter table public.meals add column if not exists daily_record_client_id text;
alter table public.meals add column if not exists meal_type text;
alter table public.meals add column if not exists logged_at timestamptz;
alter table public.meals add column if not exists display_order int;
alter table public.meals add column if not exists created_at timestamptz not null default now();
alter table public.meals add column if not exists updated_at timestamptz not null default now();
alter table public.meals add column if not exists deleted_at timestamptz null;
alter table public.meals add column if not exists schema_version int not null default 1;

alter table public.food_entries add column if not exists id uuid default gen_random_uuid();
alter table public.food_entries add column if not exists user_id uuid references auth.users(id) on delete cascade;
alter table public.food_entries add column if not exists client_id text;
alter table public.food_entries add column if not exists meal_client_id text;
alter table public.food_entries add column if not exists name text;
alter table public.food_entries add column if not exists amount_text text;
alter table public.food_entries add column if not exists grams numeric;
alter table public.food_entries add column if not exists calories numeric;
alter table public.food_entries add column if not exists protein_g numeric;
alter table public.food_entries add column if not exists carbs_g numeric;
alter table public.food_entries add column if not exists fat_g numeric;
alter table public.food_entries add column if not exists confidence numeric;
alter table public.food_entries add column if not exists source text;
alter table public.food_entries add column if not exists created_at timestamptz not null default now();
alter table public.food_entries add column if not exists updated_at timestamptz not null default now();
alter table public.food_entries add column if not exists deleted_at timestamptz null;
alter table public.food_entries add column if not exists schema_version int not null default 1;

alter table public.weight_records add column if not exists id uuid default gen_random_uuid();
alter table public.weight_records add column if not exists user_id uuid references auth.users(id) on delete cascade;
alter table public.weight_records add column if not exists client_id text;
alter table public.weight_records add column if not exists local_date date;
alter table public.weight_records add column if not exists measured_at timestamptz;
alter table public.weight_records add column if not exists weight_kg numeric;
alter table public.weight_records add column if not exists source text;
alter table public.weight_records add column if not exists created_at timestamptz not null default now();
alter table public.weight_records add column if not exists updated_at timestamptz not null default now();
alter table public.weight_records add column if not exists deleted_at timestamptz null;
alter table public.weight_records add column if not exists schema_version int not null default 1;

do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'daily_records' and column_name = 'record_date'
  ) then
    update public.daily_records
      set local_date = coalesce(local_date, record_date)
      where local_date is null;
  end if;

  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'weight_records' and column_name = 'measured_date'
  ) then
    update public.weight_records
      set local_date = coalesce(local_date, measured_date)
      where local_date is null;
  end if;

  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'food_entries' and column_name = 'quantity'
  ) then
    update public.food_entries
      set amount_text = coalesce(amount_text, quantity)
      where amount_text is null;
  end if;

  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'food_entries' and column_name = 'estimated_calories'
  ) then
    update public.food_entries
      set calories = coalesce(calories, estimated_calories::numeric)
      where calories is null;
  end if;
end;
$$;

update public.daily_records set local_date = coalesce(local_date, current_date) where local_date is null;
update public.weight_records set local_date = coalesce(local_date, current_date) where local_date is null;
update public.food_entries set name = coalesce(nullif(name, ''), 'unknown') where name is null or name = '';

alter table public.daily_records alter column id set default gen_random_uuid();
alter table public.daily_records alter column user_id set not null;
alter table public.daily_records alter column client_id set not null;
alter table public.daily_records alter column local_date set not null;
alter table public.daily_records alter column created_at set default now();
alter table public.daily_records alter column updated_at set default now();
alter table public.daily_records alter column schema_version set default 1;

alter table public.meals alter column id set default gen_random_uuid();
alter table public.meals alter column user_id set not null;
alter table public.meals alter column client_id set not null;
alter table public.meals alter column daily_record_client_id set not null;
alter table public.meals alter column created_at set default now();
alter table public.meals alter column updated_at set default now();
alter table public.meals alter column schema_version set default 1;

alter table public.food_entries alter column id set default gen_random_uuid();
alter table public.food_entries alter column user_id set not null;
alter table public.food_entries alter column client_id set not null;
alter table public.food_entries alter column meal_client_id set not null;
alter table public.food_entries alter column name set not null;
alter table public.food_entries alter column created_at set default now();
alter table public.food_entries alter column updated_at set default now();
alter table public.food_entries alter column schema_version set default 1;

alter table public.weight_records alter column id set default gen_random_uuid();
alter table public.weight_records alter column user_id set not null;
alter table public.weight_records alter column client_id set not null;
alter table public.weight_records alter column local_date set not null;
alter table public.weight_records alter column weight_kg set not null;
alter table public.weight_records alter column created_at set default now();
alter table public.weight_records alter column updated_at set default now();
alter table public.weight_records alter column schema_version set default 1;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'daily_records_user_client_unique'
      and conrelid = 'public.daily_records'::regclass
  ) then
    alter table public.daily_records
      add constraint daily_records_user_client_unique unique (user_id, client_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'meals_user_client_unique'
      and conrelid = 'public.meals'::regclass
  ) then
    alter table public.meals
      add constraint meals_user_client_unique unique (user_id, client_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'food_entries_user_client_unique'
      and conrelid = 'public.food_entries'::regclass
  ) then
    alter table public.food_entries
      add constraint food_entries_user_client_unique unique (user_id, client_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'weight_records_user_client_unique'
      and conrelid = 'public.weight_records'::regclass
  ) then
    alter table public.weight_records
      add constraint weight_records_user_client_unique unique (user_id, client_id);
  end if;
end;
$$;

create index if not exists daily_records_user_local_date_idx on public.daily_records (user_id, local_date);
create index if not exists daily_records_user_updated_at_idx on public.daily_records (user_id, updated_at);
create index if not exists daily_records_user_deleted_at_idx on public.daily_records (user_id, deleted_at);

create index if not exists meals_user_daily_record_client_id_idx on public.meals (user_id, daily_record_client_id);
create index if not exists meals_user_updated_at_idx on public.meals (user_id, updated_at);
create index if not exists meals_user_deleted_at_idx on public.meals (user_id, deleted_at);

create index if not exists food_entries_user_meal_client_id_idx on public.food_entries (user_id, meal_client_id);
create index if not exists food_entries_user_updated_at_idx on public.food_entries (user_id, updated_at);
create index if not exists food_entries_user_deleted_at_idx on public.food_entries (user_id, deleted_at);

create index if not exists weight_records_user_local_date_idx on public.weight_records (user_id, local_date);
create index if not exists weight_records_user_updated_at_idx on public.weight_records (user_id, updated_at);
create index if not exists weight_records_user_deleted_at_idx on public.weight_records (user_id, deleted_at);

alter table public.daily_records enable row level security;
alter table public.meals enable row level security;
alter table public.food_entries enable row level security;
alter table public.weight_records enable row level security;

drop policy if exists daily_records_select_own on public.daily_records;
drop policy if exists daily_records_insert_own on public.daily_records;
drop policy if exists daily_records_update_own on public.daily_records;
drop policy if exists daily_records_delete_own on public.daily_records;
drop policy if exists meals_select_own on public.meals;
drop policy if exists meals_insert_own on public.meals;
drop policy if exists meals_update_own on public.meals;
drop policy if exists meals_delete_own on public.meals;
drop policy if exists food_entries_select_own on public.food_entries;
drop policy if exists food_entries_insert_own on public.food_entries;
drop policy if exists food_entries_update_own on public.food_entries;
drop policy if exists food_entries_delete_own on public.food_entries;
drop policy if exists weight_records_select_own on public.weight_records;
drop policy if exists weight_records_insert_own on public.weight_records;
drop policy if exists weight_records_update_own on public.weight_records;
drop policy if exists weight_records_delete_own on public.weight_records;

do $$
begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'daily_records' and policyname = 'daily_records_select_own') then
    create policy daily_records_select_own on public.daily_records for select using ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'daily_records' and policyname = 'daily_records_insert_own') then
    create policy daily_records_insert_own on public.daily_records for insert with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'daily_records' and policyname = 'daily_records_update_own') then
    create policy daily_records_update_own on public.daily_records for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'daily_records' and policyname = 'daily_records_delete_own') then
    create policy daily_records_delete_own on public.daily_records for delete using ((select auth.uid()) = user_id);
  end if;

  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'meals' and policyname = 'meals_select_own') then
    create policy meals_select_own on public.meals for select using ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'meals' and policyname = 'meals_insert_own') then
    create policy meals_insert_own on public.meals for insert with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'meals' and policyname = 'meals_update_own') then
    create policy meals_update_own on public.meals for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'meals' and policyname = 'meals_delete_own') then
    create policy meals_delete_own on public.meals for delete using ((select auth.uid()) = user_id);
  end if;

  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'food_entries' and policyname = 'food_entries_select_own') then
    create policy food_entries_select_own on public.food_entries for select using ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'food_entries' and policyname = 'food_entries_insert_own') then
    create policy food_entries_insert_own on public.food_entries for insert with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'food_entries' and policyname = 'food_entries_update_own') then
    create policy food_entries_update_own on public.food_entries for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'food_entries' and policyname = 'food_entries_delete_own') then
    create policy food_entries_delete_own on public.food_entries for delete using ((select auth.uid()) = user_id);
  end if;

  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'weight_records' and policyname = 'weight_records_select_own') then
    create policy weight_records_select_own on public.weight_records for select using ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'weight_records' and policyname = 'weight_records_insert_own') then
    create policy weight_records_insert_own on public.weight_records for insert with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'weight_records' and policyname = 'weight_records_update_own') then
    create policy weight_records_update_own on public.weight_records for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'weight_records' and policyname = 'weight_records_delete_own') then
    create policy weight_records_delete_own on public.weight_records for delete using ((select auth.uid()) = user_id);
  end if;
end;
$$;

drop trigger if exists daily_records_set_updated_at on public.daily_records;
create trigger daily_records_set_updated_at
before update on public.daily_records
for each row execute function public.dayzero_set_updated_at();

drop trigger if exists meals_set_updated_at on public.meals;
create trigger meals_set_updated_at
before update on public.meals
for each row execute function public.dayzero_set_updated_at();

drop trigger if exists food_entries_set_updated_at on public.food_entries;
create trigger food_entries_set_updated_at
before update on public.food_entries
for each row execute function public.dayzero_set_updated_at();

drop trigger if exists weight_records_set_updated_at on public.weight_records;
create trigger weight_records_set_updated_at
before update on public.weight_records
for each row execute function public.dayzero_set_updated_at();

grant usage on schema public to anon, authenticated;
revoke all on public.daily_records from anon;
revoke all on public.meals from anon;
revoke all on public.food_entries from anon;
revoke all on public.weight_records from anon;
grant select, insert, update, delete on public.daily_records to authenticated;
grant select, insert, update, delete on public.meals to authenticated;
grant select, insert, update, delete on public.food_entries to authenticated;
grant select, insert, update, delete on public.weight_records to authenticated;
