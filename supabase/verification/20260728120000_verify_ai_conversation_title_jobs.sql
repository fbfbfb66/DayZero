do $$
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'ai_conversations'
      and column_name = 'title_source'
  ) then raise exception 'missing ai_conversations.title_source'; end if;

  if not exists (
    select 1 from information_schema.tables
    where table_schema = 'public' and table_name = 'ai_conversation_title_jobs'
  ) then raise exception 'missing ai_conversation_title_jobs'; end if;

  if not exists (
    select 1 from pg_trigger
    where tgname = 'ai_conversations_protect_title_source' and not tgisinternal
  ) then raise exception 'missing title source protection trigger'; end if;

  if to_regprocedure(
    'public.claim_ai_conversation_title_jobs(text,integer,integer,integer)'
  ) is null then raise exception 'missing claim RPC'; end if;

  if not (
    select relrowsecurity
    from pg_class
    where oid = 'public.ai_conversation_title_jobs'::regclass
  ) then raise exception 'RLS is not enabled on title jobs'; end if;

  if exists (
    select 1 from information_schema.role_table_grants
    where table_schema = 'public' and table_name = 'ai_conversation_title_jobs'
      and grantee = 'anon'
  ) then raise exception 'job table exposed to anon'; end if;

  if exists (
    select 1 from information_schema.role_table_grants
    where table_schema = 'public' and table_name = 'ai_conversation_title_jobs'
      and grantee = 'authenticated'
      and privilege_type in ('UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER')
  ) then raise exception 'authenticated role can mutate title job state'; end if;

  if not exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'ai_conversation_title_jobs'
      and policyname = 'ai_conversation_title_jobs_insert_own_pending'
  ) then raise exception 'missing strict title job insert policy'; end if;

  if has_function_privilege(
    'authenticated',
    'public.claim_ai_conversation_title_jobs(text,integer,integer,integer)',
    'EXECUTE'
  ) then raise exception 'authenticated can execute title claim RPC'; end if;

  if not has_function_privilege(
    'service_role',
    'public.claim_ai_conversation_title_jobs(text,integer,integer,integer)',
    'EXECUTE'
  ) then raise exception 'service role cannot execute title claim RPC'; end if;
end;
$$;
