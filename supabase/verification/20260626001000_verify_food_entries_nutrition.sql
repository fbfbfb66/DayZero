-- DayZero food_entries nutrition schema and security verification.
-- Expected result: every row returns passed = true.

with checks as (
  -- 1. Verify public.food_entries table exists
  select
    'food_entries table exists' as check_name,
    exists (
      select 1
      from information_schema.tables
      where table_schema = 'public'
        and table_name = 'food_entries'
    ) as passed
  union all
  -- 2. Verify protein_g field exists and is numeric and nullable
  select
    'protein_g field is numeric and nullable',
    exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'food_entries'
        and column_name = 'protein_g'
        and data_type = 'numeric'
        and is_nullable = 'YES'
    )
  union all
  -- 3. Verify carbs_g field exists and is numeric and nullable
  select
    'carbs_g field is numeric and nullable',
    exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'food_entries'
        and column_name = 'carbs_g'
        and data_type = 'numeric'
        and is_nullable = 'YES'
    )
  union all
  -- 4. Verify fat_g field exists and is numeric and nullable
  select
    'fat_g field is numeric and nullable',
    exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'food_entries'
        and column_name = 'fat_g'
        and data_type = 'numeric'
        and is_nullable = 'YES'
    )
  union all
  -- 5. Verify fiber_g field exists and is numeric and nullable
  select
    'fiber_g field is numeric and nullable',
    exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'food_entries'
        and column_name = 'fiber_g'
        and data_type = 'numeric'
        and is_nullable = 'YES'
    )
  union all
  -- 6. Verify user_id relationship exists (foreign key references auth.users(id))
  select
    'user_id constraint exists',
    exists (
      select 1
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
      where n.nspname = 'public'
        and t.relname = 'food_entries'
        and c.contype = 'f'
        and c.confrelid = 'auth.users'::regclass
    )
  union all
  -- 7. Verify meal relationship exists (foreign key references public.meals)
  select
    'meal constraint exists',
    exists (
      select 1
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
      where n.nspname = 'public'
        and t.relname = 'food_entries'
        and c.contype = 'f'
        and c.confrelid = 'public.meals'::regclass
    )
  union all
  -- 8. Verify RLS is enabled on public.food_entries
  select
    'rls enabled on food_entries',
    exists (
      select 1
      from pg_class
      where oid = 'public.food_entries'::regclass
        and relrowsecurity = true
    )
  union all
  -- 9. Verify owner-scoped policy exists for SELECT (or all policies in general)
  select
    'owner policy exists on food_entries',
    exists (
      select 1
      from pg_policies
      where schemaname = 'public'
        and tablename = 'food_entries'
    )
)
select *
from checks
order by check_name;
