-- DayZero user_profiles schema and security verification.
-- Expected result: every row returns passed = true.

with checks as (
  select
    'user_profiles table exists' as check_name,
    exists (
      select 1
      from information_schema.tables
      where table_schema = 'public'
        and table_name = 'user_profiles'
    ) as passed
  union all
  select
    'primary key references auth.users(id) on delete cascade',
    exists (
      select 1
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
      join pg_constraint pk on pk.conrelid = c.confrelid and pk.contype = 'p'
      where n.nspname = 'public'
        and t.relname = 'user_profiles'
        and c.contype = 'f'
        and c.confrelid = 'auth.users'::regclass
        and c.confdeltype = 'c'
    )
  union all
  select
    'rls enabled',
    exists (
      select 1
      from pg_class
      where oid = 'public.user_profiles'::regclass
        and relrowsecurity = true
    )
  union all
  select
    'select-own policy exists',
    exists (
      select 1
      from pg_policies
      where schemaname = 'public'
        and tablename = 'user_profiles'
        and policyname = 'user_profiles_select_own'
        and cmd = 'SELECT'
        and roles = '{authenticated}'
        and qual like '%is_anonymous%'
    )
  union all
  select
    'authenticated has select only',
    (
      has_table_privilege('authenticated', 'public.user_profiles', 'select')
      and not has_table_privilege('authenticated', 'public.user_profiles', 'insert')
      and not has_table_privilege('authenticated', 'public.user_profiles', 'update')
      and not has_table_privilege('authenticated', 'public.user_profiles', 'delete')
    )
  union all
  select
    'anon has no table privileges',
    (
      not has_table_privilege('anon', 'public.user_profiles', 'select')
      and not has_table_privilege('anon', 'public.user_profiles', 'insert')
      and not has_table_privilege('anon', 'public.user_profiles', 'update')
      and not has_table_privilege('anon', 'public.user_profiles', 'delete')
    )
  union all
  select
    'non-anonymous auth users have profiles',
    not exists (
      select 1
      from auth.users u
      left join public.user_profiles p on p.id = u.id
      where coalesce(u.is_anonymous, false) = false
        and p.id is null
    )
  union all
  select
    'anonymous auth users have no profiles',
    not exists (
      select 1
      from auth.users u
      join public.user_profiles p on p.id = u.id
      where coalesce(u.is_anonymous, false) = true
    )
  union all
  select
    'auth insert trigger exists',
    exists (
      select 1
      from pg_trigger
      where tgname = 'dayzero_auth_user_profile_insert'
        and tgrelid = 'auth.users'::regclass
        and not tgisinternal
    )
  union all
  select
    'auth email update trigger exists',
    exists (
      select 1
      from pg_trigger
      where tgname = 'dayzero_auth_user_profile_email_update'
        and tgrelid = 'auth.users'::regclass
        and not tgisinternal
    )
  union all
  select
    'trigger function execute revoked from public clients',
    (
      not has_function_privilege('anon', 'public.dayzero_sync_user_profile_from_auth()', 'execute')
      and not has_function_privilege('authenticated', 'public.dayzero_sync_user_profile_from_auth()', 'execute')
    )
)
select *
from checks
order by check_name;
