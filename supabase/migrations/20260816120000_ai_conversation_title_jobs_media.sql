-- Allow image-only / image+text first messages to receive AI-generated conversation titles.
-- Adds first_user_media_count to the durable title queue and relaxes the text-only
-- validation so a blank first_user_text is accepted when media is attached.
-- Apply only through the controlled Supabase migration process.

alter table public.ai_conversation_title_jobs
  add column if not exists first_user_media_count int not null default 0;

alter table public.ai_conversation_title_jobs
  drop constraint if exists ai_conversation_title_jobs_media_count_check;
alter table public.ai_conversation_title_jobs
  add constraint ai_conversation_title_jobs_media_count_check
  check (first_user_media_count between 0 and 6);

-- The insert policy previously required non-blank first_user_text; allow blank text
-- when the first user message carries media.
drop policy if exists ai_conversation_title_jobs_insert_own_pending
  on public.ai_conversation_title_jobs;

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
  and char_length(btrim(first_user_text)) <= 2000
  and (char_length(btrim(first_user_text)) > 0 or first_user_media_count > 0)
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

-- The enqueue function gains a media-count parameter. Dropping the old 4-arg
-- signature keeps exactly one overload; PostgREST callers may omit the defaulted
-- 5th argument, so older gateways keep working.
drop function if exists public.enqueue_ai_conversation_title_job(text, uuid, uuid, text);

create or replace function public.enqueue_ai_conversation_title_job(
  p_request_id text,
  p_conversation_id uuid,
  p_first_user_message_id uuid,
  p_first_user_text text,
  p_first_user_media_count int default 0
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
  if p_first_user_media_count is null
    or p_first_user_media_count < 0
    or p_first_user_media_count > 6
  then
    return jsonb_build_object('accepted', false, 'errorCode', 'MEDIA_COUNT_INVALID');
  end if;
  if p_first_user_text is null then
    return jsonb_build_object('accepted', false, 'errorCode', 'TEXT_EMPTY');
  end if;
  if length(btrim(p_first_user_text)) = 0 and p_first_user_media_count = 0 then
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
    first_user_text, input_text_hash, first_user_media_count
  ) values (
    btrim(p_request_id), v_user_id, p_conversation_id, p_first_user_message_id,
    btrim(v_message_text),
    pg_catalog.sha256(pg_catalog.convert_to(btrim(v_message_text), 'UTF8')),
    p_first_user_media_count
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

revoke all on function public.enqueue_ai_conversation_title_job(text, uuid, uuid, text, int)
  from public, anon;
grant execute on function public.enqueue_ai_conversation_title_job(text, uuid, uuid, text, int)
  to authenticated;

comment on column public.ai_conversation_title_jobs.first_user_media_count is
  'Number of images attached to the first user message. When > 0 the title worker includes the images (downloaded from Storage via service role) in the Kimi request, and blank text is allowed.';
