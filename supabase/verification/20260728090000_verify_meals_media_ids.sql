-- Read-only verification for 20260728090000_add_meals_media_ids.sql.
-- Every row should return passed = true; this file does not modify remote data.
with checks as (
  select 'media_ids exists' as check_name, exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'meals' and column_name = 'media_ids'
  ) as passed
  union all select 'media_ids is text array, non-null, with empty-array default', exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'meals' and column_name = 'media_ids'
      and data_type = 'ARRAY' and udt_name = '_text' and is_nullable = 'NO'
      and column_default like '%{}%'
  )
  union all select 'existing rows have empty array or ordered values', not exists (
    select 1 from public.meals where media_ids is null
  )
  union all select 'meals RLS remains enabled', exists (
    select 1 from pg_class where oid = 'public.meals'::regclass and relrowsecurity = true
  )
  union all select 'meals retains owner-scoped policies', exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'meals'
      and (qual like '%auth.uid%' or with_check like '%auth.uid%')
  )
)
select check_name, passed from checks order by check_name;
