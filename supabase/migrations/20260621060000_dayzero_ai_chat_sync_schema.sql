-- DayZero Phase 6A AI chat sync schema.
-- This migration creates only remote tables/contracts for future chat sync.
-- It does not migrate existing local chat data and does not touch record sync tables.

create or replace function public.dayzero_set_server_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.server_updated_at = now();
  return new;
end;
$$;

create table if not exists public.ai_conversations (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  conversation_date date not null,
  title text not null default '',
  last_message_preview text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_activity_at timestamptz not null default now(),
  deleted_at timestamptz null,
  server_updated_at timestamptz not null default now(),
  schema_version int not null default 1
);

create table if not exists public.ai_chat_messages (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  conversation_id uuid not null,
  role text not null,
  message_type text not null default 'Text',
  text text not null default '',
  content_json jsonb null,
  assistant_cards jsonb null,
  suggested_replies_json jsonb null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  server_updated_at timestamptz not null default now(),
  schema_version int not null default 1,
  constraint ai_chat_messages_role_check check (role in ('User', 'Assistant', 'System'))
);

alter table public.ai_conversations add column if not exists user_id uuid default auth.uid() references auth.users(id) on delete cascade;
alter table public.ai_conversations add column if not exists conversation_date date;
alter table public.ai_conversations add column if not exists title text not null default '';
alter table public.ai_conversations add column if not exists last_message_preview text not null default '';
alter table public.ai_conversations add column if not exists created_at timestamptz not null default now();
alter table public.ai_conversations add column if not exists updated_at timestamptz not null default now();
alter table public.ai_conversations add column if not exists last_activity_at timestamptz not null default now();
alter table public.ai_conversations add column if not exists deleted_at timestamptz null;
alter table public.ai_conversations add column if not exists server_updated_at timestamptz not null default now();
alter table public.ai_conversations add column if not exists schema_version int not null default 1;

alter table public.ai_chat_messages add column if not exists user_id uuid default auth.uid() references auth.users(id) on delete cascade;
alter table public.ai_chat_messages add column if not exists conversation_id uuid;
alter table public.ai_chat_messages add column if not exists role text;
alter table public.ai_chat_messages add column if not exists message_type text not null default 'Text';
alter table public.ai_chat_messages add column if not exists text text not null default '';
alter table public.ai_chat_messages add column if not exists content_json jsonb null;
alter table public.ai_chat_messages add column if not exists assistant_cards jsonb null;
alter table public.ai_chat_messages add column if not exists suggested_replies_json jsonb null;
alter table public.ai_chat_messages add column if not exists created_at timestamptz not null default now();
alter table public.ai_chat_messages add column if not exists updated_at timestamptz not null default now();
alter table public.ai_chat_messages add column if not exists deleted_at timestamptz null;
alter table public.ai_chat_messages add column if not exists server_updated_at timestamptz not null default now();
alter table public.ai_chat_messages add column if not exists schema_version int not null default 1;

alter table public.ai_conversations alter column user_id set default auth.uid();
alter table public.ai_conversations alter column user_id set not null;
alter table public.ai_conversations alter column conversation_date set not null;
alter table public.ai_conversations alter column title set default '';
alter table public.ai_conversations alter column last_message_preview set default '';
alter table public.ai_conversations alter column created_at set default now();
alter table public.ai_conversations alter column updated_at set default now();
alter table public.ai_conversations alter column last_activity_at set default now();
alter table public.ai_conversations alter column server_updated_at set default now();
alter table public.ai_conversations alter column schema_version set default 1;

alter table public.ai_chat_messages alter column user_id set default auth.uid();
alter table public.ai_chat_messages alter column user_id set not null;
alter table public.ai_chat_messages alter column conversation_id set not null;
alter table public.ai_chat_messages alter column role set not null;
alter table public.ai_chat_messages alter column message_type set default 'Text';
alter table public.ai_chat_messages alter column text set default '';
alter table public.ai_chat_messages alter column created_at set default now();
alter table public.ai_chat_messages alter column updated_at set default now();
alter table public.ai_chat_messages alter column server_updated_at set default now();
alter table public.ai_chat_messages alter column schema_version set default 1;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'ai_conversations_id_user_id_unique'
      and conrelid = 'public.ai_conversations'::regclass
  ) then
    alter table public.ai_conversations
      add constraint ai_conversations_id_user_id_unique unique (id, user_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'ai_chat_messages_role_check'
      and conrelid = 'public.ai_chat_messages'::regclass
  ) then
    alter table public.ai_chat_messages
      add constraint ai_chat_messages_role_check
      check (role in ('User', 'Assistant', 'System'));
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'ai_chat_messages_conversation_owner_fk'
      and conrelid = 'public.ai_chat_messages'::regclass
  ) then
    alter table public.ai_chat_messages
      add constraint ai_chat_messages_conversation_owner_fk
      foreign key (conversation_id, user_id)
      references public.ai_conversations (id, user_id)
      on delete cascade;
  end if;
end;
$$;

create index if not exists ai_conversations_user_server_cursor_idx
  on public.ai_conversations (user_id, server_updated_at, id);
create index if not exists ai_conversations_user_active_activity_idx
  on public.ai_conversations (user_id, last_activity_at desc, id)
  where deleted_at is null;
create index if not exists ai_conversations_user_deleted_at_idx
  on public.ai_conversations (user_id, deleted_at);
create index if not exists ai_conversations_user_conversation_date_idx
  on public.ai_conversations (user_id, conversation_date);

create index if not exists ai_chat_messages_user_server_cursor_idx
  on public.ai_chat_messages (user_id, server_updated_at, id);
create index if not exists ai_chat_messages_conversation_owner_fk_idx
  on public.ai_chat_messages (conversation_id, user_id);
create index if not exists ai_chat_messages_conversation_order_idx
  on public.ai_chat_messages (user_id, conversation_id, created_at, id);
create index if not exists ai_chat_messages_user_deleted_at_idx
  on public.ai_chat_messages (user_id, deleted_at);

alter table public.ai_conversations enable row level security;
alter table public.ai_chat_messages enable row level security;

drop policy if exists ai_conversations_select_own on public.ai_conversations;
drop policy if exists ai_conversations_insert_own on public.ai_conversations;
drop policy if exists ai_conversations_update_own on public.ai_conversations;
drop policy if exists ai_chat_messages_select_own on public.ai_chat_messages;
drop policy if exists ai_chat_messages_insert_own_conversation on public.ai_chat_messages;
drop policy if exists ai_chat_messages_update_own_conversation on public.ai_chat_messages;

create policy ai_conversations_select_own
on public.ai_conversations
for select
using ((select auth.uid()) = user_id);

create policy ai_conversations_insert_own
on public.ai_conversations
for insert
with check ((select auth.uid()) = user_id);

create policy ai_conversations_update_own
on public.ai_conversations
for update
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy ai_chat_messages_select_own
on public.ai_chat_messages
for select
using ((select auth.uid()) = user_id);

create policy ai_chat_messages_insert_own_conversation
on public.ai_chat_messages
for insert
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.ai_conversations c
    where c.id = conversation_id
      and c.user_id = (select auth.uid())
  )
);

create policy ai_chat_messages_update_own_conversation
on public.ai_chat_messages
for update
using ((select auth.uid()) = user_id)
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.ai_conversations c
    where c.id = conversation_id
      and c.user_id = (select auth.uid())
  )
);

drop trigger if exists ai_conversations_set_server_updated_at on public.ai_conversations;
create trigger ai_conversations_set_server_updated_at
before update on public.ai_conversations
for each row execute function public.dayzero_set_server_updated_at();

drop trigger if exists ai_chat_messages_set_server_updated_at on public.ai_chat_messages;
create trigger ai_chat_messages_set_server_updated_at
before update on public.ai_chat_messages
for each row execute function public.dayzero_set_server_updated_at();

grant usage on schema public to anon, authenticated;
revoke all on public.ai_conversations from anon, authenticated;
revoke all on public.ai_chat_messages from anon, authenticated;
grant select, insert, update on public.ai_conversations to authenticated;
grant select, insert, update on public.ai_chat_messages to authenticated;

comment on table public.ai_conversations is
  'DayZero AI conversation sync contract. Local UUIDs are remote primary keys. Rows are owner-scoped by user_id and soft-deleted with deleted_at.';
comment on table public.ai_chat_messages is
  'DayZero AI chat message sync contract. Messages belong to an owner-matched ai_conversations row and store assistant card JSON losslessly as jsonb.';
comment on column public.ai_conversations.conversation_date is
  'Fixed natural date for the conversation; not a timestamp and not timezone shifted.';
comment on column public.ai_conversations.server_updated_at is
  'Database-controlled cursor for incremental chat pull. Clients must not use business updated_at as the only pull cursor.';
comment on column public.ai_chat_messages.assistant_cards is
  'Full persisted assistantCardsJson array/object. Unknown future card fields should be preserved by clients.';
comment on column public.ai_chat_messages.server_updated_at is
  'Database-controlled cursor for incremental chat pull. Use with id as a stable secondary cursor.';

-- Verification SQL (copy into SQL Editor after applying if integration tests are unavailable):
-- select table_name from information_schema.tables where table_schema = 'public' and table_name in ('ai_conversations', 'ai_chat_messages');
-- select conname, contype from pg_constraint where conrelid in ('public.ai_conversations'::regclass, 'public.ai_chat_messages'::regclass) order by conname;
-- select tablename, policyname, cmd from pg_policies where schemaname = 'public' and tablename in ('ai_conversations', 'ai_chat_messages') order by tablename, policyname;
-- select relname, relrowsecurity from pg_class where oid in ('public.ai_conversations'::regclass, 'public.ai_chat_messages'::regclass);
-- select indexname from pg_indexes where schemaname = 'public' and tablename in ('ai_conversations', 'ai_chat_messages') order by tablename, indexname;
