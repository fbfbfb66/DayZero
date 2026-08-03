-- Durable AI conversation-title queue and source-priority protection.
-- Apply only through the controlled Supabase migration process.

alter table public.ai_conversations
  add column if not exists title_source text not null default 'local_fallback',
  add column if not exists title_generated_at timestamptz null;

alter table public.ai_conversations
  drop constraint if exists ai_conversations_title_source_check;
alter table public.ai_conversations
  add constraint ai_conversations_title_source_check
  check (title_source in ('local_fallback', 'ai_generated', 'user_edited'));

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'ai_chat_messages_id_user_id_unique'
      and conrelid = 'public.ai_chat_messages'::regclass
  ) then
    alter table public.ai_chat_messages
      add constraint ai_chat_messages_id_user_id_unique unique (id, user_id);
  end if;
end;
$$;

create or replace function public.dayzero_protect_conversation_title_source()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  old_priority int;
  new_priority int;
begin
  if tg_op = 'INSERT' then
    if current_user in ('anon', 'authenticated') then
      new.title_source := 'local_fallback';
      new.title_generated_at := null;
    end if;
    return new;
  end if;

  old_priority := case old.title_source
    when 'user_edited' then 3 when 'ai_generated' then 2 else 1 end;
  new_priority := case new.title_source
    when 'user_edited' then 3 when 'ai_generated' then 2 else 1 end;

  if current_user in ('anon', 'authenticated') then
    new_priority := 1;
    new.title_source := 'local_fallback';
    new.title_generated_at := null;
  end if;

  if old_priority > new_priority then
    new.title := old.title;
    new.title_source := old.title_source;
    new.title_generated_at := old.title_generated_at;
  end if;
  return new;
end;
$$;

drop trigger if exists ai_conversations_protect_title_source on public.ai_conversations;
create trigger ai_conversations_protect_title_source
before insert or update on public.ai_conversations
for each row execute function public.dayzero_protect_conversation_title_source();

create table if not exists public.ai_conversation_title_jobs (
  id uuid primary key default gen_random_uuid(),
  request_id text not null,
  user_id uuid not null references auth.users(id) on delete cascade,
  conversation_id uuid not null,
  first_user_message_id uuid not null,
  first_user_text text null,
  input_text_hash bytea not null,
  status text not null default 'pending',
  attempt_count int not null default 0,
  next_attempt_at timestamptz not null default now(),
  lease_owner text null,
  lease_expires_at timestamptz null,
  generated_title text null,
  last_error_code text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz null,
  constraint ai_conversation_title_jobs_owner_fk
    foreign key (conversation_id, user_id)
    references public.ai_conversations(id, user_id)
    on delete cascade,
  constraint ai_conversation_title_jobs_message_owner_fk
    foreign key (first_user_message_id, user_id)
    references public.ai_chat_messages(id, user_id)
    on delete cascade,
  constraint ai_conversation_title_jobs_status_check
    check (status in (
      'pending', 'running', 'retry_wait', 'succeeded',
      'failed_permanent', 'cancelled'
    )),
  constraint ai_conversation_title_jobs_attempt_check check (attempt_count >= 0),
  constraint ai_conversation_title_jobs_user_conversation_unique unique (user_id, conversation_id),
  constraint ai_conversation_title_jobs_request_unique unique (request_id)
);

create index if not exists ai_conversation_title_jobs_claim_idx
  on public.ai_conversation_title_jobs(status, next_attempt_at, lease_expires_at, created_at);

alter table public.ai_conversation_title_jobs enable row level security;
revoke all on public.ai_conversation_title_jobs from anon, authenticated;

drop policy if exists ai_conversation_title_jobs_select_own
  on public.ai_conversation_title_jobs;
drop policy if exists ai_conversation_title_jobs_insert_own_pending
  on public.ai_conversation_title_jobs;

create policy ai_conversation_title_jobs_select_own
on public.ai_conversation_title_jobs
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy ai_conversation_title_jobs_insert_own_pending
on public.ai_conversation_title_jobs
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and status = 'pending'
  and attempt_count = 0
  and lease_owner is null
  and lease_expires_at is null
  and generated_title is null
  and last_error_code is null
  and completed_at is null
  and first_user_text is not null
  and char_length(btrim(first_user_text)) between 1 and 2000
  and input_text_hash = pg_catalog.sha256(
    pg_catalog.convert_to(btrim(first_user_text), 'UTF8')
  )
  and exists (
    select 1 from public.ai_conversations c
    where c.id = public.ai_conversation_title_jobs.conversation_id
      and c.user_id = (select auth.uid())
      and c.deleted_at is null
  )
  and exists (
    select 1 from public.ai_chat_messages selected
    where selected.id = public.ai_conversation_title_jobs.first_user_message_id
      and selected.conversation_id = public.ai_conversation_title_jobs.conversation_id
      and selected.user_id = (select auth.uid())
      and selected.role = 'User'
      and selected.deleted_at is null
      and btrim(selected.text) = btrim(public.ai_conversation_title_jobs.first_user_text)
      and not exists (
        select 1 from public.ai_chat_messages earlier
        where earlier.conversation_id = selected.conversation_id
          and earlier.user_id = selected.user_id
          and earlier.role = 'User'
          and earlier.deleted_at is null
          and (earlier.created_at, earlier.id) < (selected.created_at, selected.id)
      )
  )
);

grant select, insert on public.ai_conversation_title_jobs to authenticated;
grant select, update on public.ai_conversation_title_jobs to service_role;
grant select, update on public.ai_conversations to service_role;

create or replace function public.enqueue_ai_conversation_title_job(
  p_request_id text,
  p_conversation_id uuid,
  p_first_user_message_id uuid,
  p_first_user_text text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_message_text text;
  v_job public.ai_conversation_title_jobs;
begin
  if v_user_id is null then
    return jsonb_build_object('accepted', false, 'errorCode', 'AUTH_REQUIRED');
  end if;
  if p_request_id is null or length(btrim(p_request_id)) < 8 or length(p_request_id) > 128 then
    return jsonb_build_object('accepted', false, 'errorCode', 'REQUEST_ID_INVALID');
  end if;
  if p_first_user_text is null or length(btrim(p_first_user_text)) = 0 then
    return jsonb_build_object('accepted', false, 'errorCode', 'TEXT_EMPTY');
  end if;
  if char_length(p_first_user_text) > 2000 then
    return jsonb_build_object('accepted', false, 'errorCode', 'TEXT_TOO_LONG');
  end if;

  if not exists (
    select 1 from public.ai_conversations c
    where c.id = p_conversation_id
      and c.user_id = v_user_id
      and c.deleted_at is null
  ) then
    return jsonb_build_object('accepted', false, 'errorCode', 'CONVERSATION_NOT_READY');
  end if;

  select m.text into v_message_text
  from public.ai_chat_messages m
  where m.id = p_first_user_message_id
    and m.conversation_id = p_conversation_id
    and m.user_id = v_user_id
    and m.role = 'User'
    and m.deleted_at is null;

  if v_message_text is null then
    return jsonb_build_object('accepted', false, 'errorCode', 'MESSAGE_NOT_READY');
  end if;
  if btrim(v_message_text) <> btrim(p_first_user_text) then
    return jsonb_build_object('accepted', false, 'errorCode', 'MESSAGE_TEXT_MISMATCH');
  end if;
  if exists (
    select 1 from public.ai_chat_messages earlier
    join public.ai_chat_messages selected on selected.id = p_first_user_message_id
    where earlier.conversation_id = p_conversation_id
      and earlier.user_id = v_user_id
      and earlier.role = 'User'
      and earlier.deleted_at is null
      and (earlier.created_at, earlier.id) < (selected.created_at, selected.id)
  ) then
    return jsonb_build_object('accepted', false, 'errorCode', 'NOT_FIRST_USER_MESSAGE');
  end if;

  insert into public.ai_conversation_title_jobs (
    request_id, user_id, conversation_id, first_user_message_id,
    first_user_text, input_text_hash
  ) values (
    btrim(p_request_id), v_user_id, p_conversation_id, p_first_user_message_id,
    btrim(v_message_text),
    pg_catalog.sha256(pg_catalog.convert_to(btrim(v_message_text), 'UTF8'))
  )
  on conflict (user_id, conversation_id) do nothing;

  select * into v_job
  from public.ai_conversation_title_jobs
  where user_id = v_user_id and conversation_id = p_conversation_id;

  return jsonb_build_object(
    'accepted', true,
    'jobId', v_job.id,
    'status', 'accepted'
  );
end;
$$;

revoke all on function public.enqueue_ai_conversation_title_job(text, uuid, uuid, text)
  from public, anon;
grant execute on function public.enqueue_ai_conversation_title_job(text, uuid, uuid, text)
  to authenticated;

create or replace function public.claim_ai_conversation_title_jobs(
  p_worker_id text,
  p_limit int,
  p_lease_seconds int,
  p_max_attempts int
)
returns setof public.ai_conversation_title_jobs
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if p_worker_id is null or length(btrim(p_worker_id)) < 4 then
    raise exception 'worker id invalid';
  end if;

  update public.ai_conversation_title_jobs
  set status = 'failed_permanent',
      first_user_text = null,
      lease_owner = null,
      lease_expires_at = null,
      last_error_code = 'MAX_ATTEMPTS',
      completed_at = now(),
      updated_at = now()
  where attempt_count >= greatest(1, least(p_max_attempts, 20))
    and (
      status in ('pending', 'retry_wait')
      or (status = 'running' and lease_expires_at < now())
    );

  update public.ai_conversation_title_jobs j
  set status = case when c.deleted_at is null then 'succeeded' else 'cancelled' end,
      first_user_text = null,
      completed_at = now(),
      updated_at = now(),
      last_error_code = case when c.deleted_at is null then null else 'CONVERSATION_DELETED' end
  from public.ai_conversations c
  where j.conversation_id = c.id
    and j.user_id = c.user_id
    and j.status in ('pending', 'retry_wait', 'running')
    and (
      c.deleted_at is not null
      or c.title_source in ('ai_generated', 'user_edited')
    );

  return query
  with candidates as (
    select j.id
    from public.ai_conversation_title_jobs j
    join public.ai_conversations c
      on c.id = j.conversation_id and c.user_id = j.user_id
    where c.deleted_at is null
      and c.title_source = 'local_fallback'
      and j.first_user_text is not null
      and j.attempt_count < greatest(1, least(p_max_attempts, 20))
      and (
        (j.status in ('pending', 'retry_wait') and j.next_attempt_at <= now())
        or (j.status = 'running' and j.lease_expires_at < now())
      )
    order by j.next_attempt_at, j.created_at
    for update of j skip locked
    limit greatest(1, least(p_limit, 20))
  )
  update public.ai_conversation_title_jobs j
  set status = 'running',
      attempt_count = j.attempt_count + 1,
      lease_owner = btrim(p_worker_id),
      lease_expires_at = now() + make_interval(secs => greatest(10, least(p_lease_seconds, 600))),
      updated_at = now(),
      last_error_code = null
  from candidates
  where j.id = candidates.id
  returning j.*;
end;
$$;

create or replace function public.complete_ai_conversation_title_job(
  p_job_id uuid,
  p_worker_id text,
  p_generated_title text
)
returns text
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_job public.ai_conversation_title_jobs;
  v_source text;
  v_deleted_at timestamptz;
begin
  if p_generated_title is null
    or char_length(btrim(p_generated_title)) not between 1 and 48
    or strpos(p_generated_title, chr(10)) > 0
    or strpos(p_generated_title, chr(13)) > 0
  then
    raise exception 'generated title invalid' using errcode = '22023';
  end if;

  select * into v_job
  from public.ai_conversation_title_jobs
  where id = p_job_id
  for update;
  if v_job.id is null or v_job.status <> 'running' or v_job.lease_owner <> p_worker_id then
    return 'lease_lost';
  end if;

  select title_source, deleted_at into v_source, v_deleted_at
  from public.ai_conversations
  where id = v_job.conversation_id and user_id = v_job.user_id
  for update;

  if v_deleted_at is not null then
    update public.ai_conversation_title_jobs
    set status = 'cancelled', first_user_text = null,
        last_error_code = 'CONVERSATION_DELETED', completed_at = now(), updated_at = now()
    where id = p_job_id;
    return 'cancelled';
  end if;

  if v_source = 'local_fallback' then
    update public.ai_conversations
    set title = p_generated_title,
        title_source = 'ai_generated',
        title_generated_at = now(),
        updated_at = now()
    where id = v_job.conversation_id
      and user_id = v_job.user_id
      and deleted_at is null
      and title_source = 'local_fallback';
  end if;

  update public.ai_conversation_title_jobs
  set status = 'succeeded', generated_title = p_generated_title,
      first_user_text = null, lease_owner = null, lease_expires_at = null,
      completed_at = now(), updated_at = now(), last_error_code = null
  where id = p_job_id;
  return 'succeeded';
end;
$$;

create or replace function public.finish_ai_conversation_title_job_failure(
  p_job_id uuid,
  p_worker_id text,
  p_error_code text,
  p_retry boolean,
  p_delay_seconds int
)
returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
begin
  update public.ai_conversation_title_jobs
  set status = case when p_retry then 'retry_wait' else 'failed_permanent' end,
      next_attempt_at = case when p_retry
        then now() + make_interval(secs => greatest(1, least(p_delay_seconds, 86400)))
        else next_attempt_at end,
      first_user_text = case when p_retry then first_user_text else null end,
      lease_owner = null,
      lease_expires_at = null,
      last_error_code = left(coalesce(p_error_code, 'UNKNOWN'), 64),
      completed_at = case when p_retry then null else now() end,
      updated_at = now()
  where id = p_job_id
    and status = 'running'
    and lease_owner = p_worker_id;
  return found;
end;
$$;

revoke all on function public.claim_ai_conversation_title_jobs(text, int, int, int) from public, anon, authenticated;
revoke all on function public.complete_ai_conversation_title_job(uuid, text, text) from public, anon, authenticated;
revoke all on function public.finish_ai_conversation_title_job_failure(uuid, text, text, boolean, int) from public, anon, authenticated;
grant execute on function public.claim_ai_conversation_title_jobs(text, int, int, int) to service_role;
grant execute on function public.complete_ai_conversation_title_job(uuid, text, text) to service_role;
grant execute on function public.finish_ai_conversation_title_job_failure(uuid, text, text, boolean, int) to service_role;

comment on table public.ai_conversation_title_jobs is
  'Durable, leased outbox for asynchronous titles. Raw input is cleared on terminal states.';
