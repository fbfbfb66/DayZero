-- DayZero user profile schema for non-anonymous Supabase Auth accounts.
-- auth.users remains the source of truth for credentials and email identity.

create table if not exists public.user_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text,
  display_name text null,
  role text not null default 'user',
  status text not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz null,
  constraint user_profiles_role_check check (role in ('user', 'admin')),
  constraint user_profiles_status_check check (status in ('active', 'disabled'))
);

alter table public.user_profiles add column if not exists email text;
alter table public.user_profiles add column if not exists display_name text null;
alter table public.user_profiles add column if not exists role text not null default 'user';
alter table public.user_profiles add column if not exists status text not null default 'active';
alter table public.user_profiles add column if not exists created_at timestamptz not null default now();
alter table public.user_profiles add column if not exists updated_at timestamptz not null default now();
alter table public.user_profiles add column if not exists last_seen_at timestamptz null;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'user_profiles_role_check'
      and conrelid = 'public.user_profiles'::regclass
  ) then
    alter table public.user_profiles
      add constraint user_profiles_role_check check (role in ('user', 'admin'));
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'user_profiles_status_check'
      and conrelid = 'public.user_profiles'::regclass
  ) then
    alter table public.user_profiles
      add constraint user_profiles_status_check check (status in ('active', 'disabled'));
  end if;
end;
$$;

create index if not exists user_profiles_status_role_idx
  on public.user_profiles (status, role);

alter table public.user_profiles enable row level security;

drop policy if exists user_profiles_select_own on public.user_profiles;

create policy user_profiles_select_own
on public.user_profiles
for select
to authenticated
using (
  (select auth.uid()) = id
  and coalesce((select auth.jwt()) ->> 'is_anonymous', 'false') <> 'true'
);

drop trigger if exists user_profiles_set_updated_at on public.user_profiles;
create trigger user_profiles_set_updated_at
before update on public.user_profiles
for each row execute function public.dayzero_set_updated_at();

create or replace function public.dayzero_sync_user_profile_from_auth()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if coalesce(new.is_anonymous, false) then
    return new;
  end if;

  insert into public.user_profiles (id, email, updated_at)
  values (new.id, new.email, now())
  on conflict (id) do update
    set email = excluded.email,
        updated_at = now();

  return new;
end;
$$;

revoke all on function public.dayzero_sync_user_profile_from_auth() from public;
revoke all on function public.dayzero_sync_user_profile_from_auth() from anon;
revoke all on function public.dayzero_sync_user_profile_from_auth() from authenticated;

drop trigger if exists dayzero_auth_user_profile_insert on auth.users;
create trigger dayzero_auth_user_profile_insert
after insert on auth.users
for each row execute function public.dayzero_sync_user_profile_from_auth();

drop trigger if exists dayzero_auth_user_profile_email_update on auth.users;
create trigger dayzero_auth_user_profile_email_update
after update of email, is_anonymous on auth.users
for each row execute function public.dayzero_sync_user_profile_from_auth();

insert into public.user_profiles (id, email, updated_at)
select users.id, users.email, now()
from auth.users as users
where coalesce(users.is_anonymous, false) = false
on conflict (id) do update
  set email = excluded.email,
      updated_at = now();

grant usage on schema public to anon, authenticated;
revoke all on public.user_profiles from anon;
revoke all on public.user_profiles from authenticated;
grant select on public.user_profiles to authenticated;

comment on table public.user_profiles is
  'Application-side profile and management metadata for non-anonymous Supabase Auth users. Credentials remain only in auth.users/Auth.';
comment on column public.user_profiles.id is
  'Primary key shared with auth.users.id. Anonymous auth users are intentionally excluded.';
comment on column public.user_profiles.role is
  'Application management role. Ordinary Android clients are not granted update permission for this column.';
comment on column public.user_profiles.status is
  'Application account status. Ordinary Android clients are not granted update permission for this column.';
