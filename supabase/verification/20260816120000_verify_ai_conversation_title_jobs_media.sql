-- Verification for 20260816120000_ai_conversation_title_jobs_media.sql
-- Run after applying the migration; every block raises on failure.

do $$
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public'
      and table_name = 'ai_conversation_title_jobs'
      and column_name = 'first_user_media_count'
      and column_default = '0'
  ) then raise exception 'missing ai_conversation_title_jobs.first_user_media_count'; end if;

  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = 'enqueue_ai_conversation_title_job'
      and pg_get_function_identity_arguments(p.oid) =
        'p_request_id text, p_conversation_id uuid, p_first_user_message_id uuid, p_first_user_text text, p_first_user_media_count integer'
  ) then raise exception 'enqueue_ai_conversation_title_job 5-arg signature missing'; end if;

  if exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = 'enqueue_ai_conversation_title_job'
      and p.pronargs = 4
  ) then raise exception 'stale 4-arg enqueue_ai_conversation_title_job overload still present'; end if;

  if not exists (
    select 1 from pg_policy
    where polrelid = 'public.ai_conversation_title_jobs'::regclass
      and polname = 'ai_conversation_title_jobs_insert_own_pending'
      and pg_get_expr(polqual, polrelid) like '%first_user_media_count%'
  ) then raise exception 'insert policy was not relaxed for media-count'; end if;
end;
$$;
