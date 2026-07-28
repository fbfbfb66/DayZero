-- DayZero media_assets sync schema and Storage security verification.
-- Expected result: every row returns passed = true.

with checks as (
  select
    'media_assets table exists' as check_name,
    exists (
      select 1 from information_schema.tables
      where table_schema = 'public' and table_name = 'media_assets'
    ) as passed
  union all
  select
    'user_id fk references auth.users(id) on delete cascade',
    exists (
      select 1 from pg_constraint c
      where c.conrelid = 'public.media_assets'::regclass
        and c.contype = 'f'
        and c.confrelid = 'auth.users'::regclass
        and c.confdeltype = 'c'
    )
  union all
  select
    'owner-scoped conversation fk exists',
    exists (
      select 1 from pg_constraint
      where conname = 'media_assets_conversation_owner_fk'
        and conrelid = 'public.media_assets'::regclass
        and contype = 'f'
    )
  union all
  select
    'id,user_id unique constraint exists',
    exists (
      select 1 from pg_constraint
      where conname = 'media_assets_id_user_id_unique'
        and conrelid = 'public.media_assets'::regclass
        and contype = 'u'
    )
  union all
  select
    'rls enabled',
    exists (
      select 1 from pg_class
      where oid = 'public.media_assets'::regclass and relrowsecurity = true
    )
  union all
  select
    'server cursor index exists',
    exists (
      select 1 from pg_indexes
      where schemaname = 'public' and tablename = 'media_assets'
        and indexname = 'media_assets_user_server_cursor_idx'
    )
  union all
  select
    'select-own policy exists',
    exists (
      select 1 from pg_policies
      where schemaname = 'public' and tablename = 'media_assets'
        and policyname = 'media_assets_select_own'
        and cmd = 'SELECT'
        and qual like '%user_id%'
    )
  union all
  select
    'insert-own policy checks parent ownership',
    exists (
      select 1 from pg_policies
      where schemaname = 'public' and tablename = 'media_assets'
        and policyname = 'media_assets_insert_own_conversation'
        and cmd = 'INSERT'
        and with_check like '%ai_conversations%'
    )
  union all
  select
    'update-own policy exists',
    exists (
      select 1 from pg_policies
      where schemaname = 'public' and tablename = 'media_assets'
        and policyname = 'media_assets_update_own_conversation'
        and cmd = 'UPDATE'
    )
  union all
  select
    'no delete policy (soft-delete only)',
    not exists (
      select 1 from pg_policies
      where schemaname = 'public' and tablename = 'media_assets'
        and cmd = 'DELETE'
    )
  union all
  select
    'server_updated_at trigger exists',
    exists (
      select 1 from pg_trigger
      where tgname = 'media_assets_set_server_updated_at'
        and tgrelid = 'public.media_assets'::regclass
        and not tgisinternal
    )
  union all
  select
    'authenticated has select/insert/update but not delete',
    (
      has_table_privilege('authenticated', 'public.media_assets', 'select')
      and has_table_privilege('authenticated', 'public.media_assets', 'insert')
      and has_table_privilege('authenticated', 'public.media_assets', 'update')
      and not has_table_privilege('authenticated', 'public.media_assets', 'delete')
    )
  union all
  select
    'anon has no table privileges',
    (
      not has_table_privilege('anon', 'public.media_assets', 'select')
      and not has_table_privilege('anon', 'public.media_assets', 'insert')
      and not has_table_privilege('anon', 'public.media_assets', 'update')
      and not has_table_privilege('anon', 'public.media_assets', 'delete')
    )
  union all
  select
    'media-assets storage bucket exists and is private',
    exists (
      select 1 from storage.buckets
      where id = 'media-assets' and public = false
    )
  union all
  select
    'storage select policy scoped to owner folder',
    exists (
      select 1 from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname = 'media_assets_objects_select_own'
        and qual like '%media-assets%'
        and qual like '%foldername%'
    )
  union all
  select
    'storage insert policy scoped to owner folder',
    exists (
      select 1 from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname = 'media_assets_objects_insert_own'
        and with_check like '%media-assets%'
        and with_check like '%foldername%'
    )
  union all
  select
    'no storage delete policy for bucket (retain bytes)',
    not exists (
      select 1 from pg_policies
      where schemaname = 'storage' and tablename = 'objects'
        and policyname like 'media_assets_objects_%'
        and cmd = 'DELETE'
    )
)
select *
from checks
order by check_name;
