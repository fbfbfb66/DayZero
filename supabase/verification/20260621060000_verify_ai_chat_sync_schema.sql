-- DayZero Phase 6A AI chat sync schema verification.
-- Run after supabase/migrations/20260621060000_dayzero_ai_chat_sync_schema.sql.
-- These checks are static SQL/schema checks plus optional authenticated-session RLS probes.

select table_name
from information_schema.tables
where table_schema = 'public'
  and table_name in ('ai_conversations', 'ai_chat_messages')
order by table_name;

select table_name, column_name, data_type, is_nullable
from information_schema.columns
where table_schema = 'public'
  and table_name in ('ai_conversations', 'ai_chat_messages')
order by table_name, ordinal_position;

select conrelid::regclass as table_name, conname, contype
from pg_constraint
where conrelid in ('public.ai_conversations'::regclass, 'public.ai_chat_messages'::regclass)
order by table_name::text, conname;

select schemaname, tablename, indexname, indexdef
from pg_indexes
where schemaname = 'public'
  and tablename in ('ai_conversations', 'ai_chat_messages')
order by tablename, indexname;

select relname, relrowsecurity
from pg_class
where oid in ('public.ai_conversations'::regclass, 'public.ai_chat_messages'::regclass)
order by relname;

select tablename, policyname, cmd, qual, with_check
from pg_policies
where schemaname = 'public'
  and tablename in ('ai_conversations', 'ai_chat_messages')
order by tablename, policyname;

select grantee, table_name, privilege_type
from information_schema.role_table_grants
where table_schema = 'public'
  and table_name in ('ai_conversations', 'ai_chat_messages')
  and grantee in ('anon', 'authenticated')
order by table_name, grantee, privilege_type;

-- Optional RLS probes for SQL Editor / psql.
-- Replace the UUID literals with real auth.users ids in a disposable test project.
--
-- begin;
--   select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
--   select set_config('request.jwt.claim.role', 'authenticated', true);
--   insert into public.ai_conversations (id, conversation_date, title, last_message_preview, created_at, updated_at, last_activity_at)
--   values ('10000000-0000-0000-0000-000000000001', '2026-06-21', 'A', 'A', now(), now(), now());
--   insert into public.ai_chat_messages (id, conversation_id, role, message_type, text, created_at, updated_at)
--   values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'User', 'Text', 'hello', now(), now());
-- rollback;
--
-- Cross-owner insert/update should fail under RLS or the composite FK:
-- begin;
--   select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
--   select set_config('request.jwt.claim.role', 'authenticated', true);
--   insert into public.ai_chat_messages (id, user_id, conversation_id, role, message_type, text, created_at, updated_at)
--   values ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000b', '10000000-0000-0000-0000-000000000001', 'User', 'Text', 'blocked', now(), now());
-- rollback;

