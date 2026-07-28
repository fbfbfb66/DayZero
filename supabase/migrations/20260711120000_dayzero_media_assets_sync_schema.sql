-- DayZero media_assets cross-device sync schema.
-- Creates the remote media_assets metadata table plus a private Storage bucket
-- (media-assets) so photo bytes and metadata survive a device switch. Mirrors the
-- ai_chat_sync_schema conventions: client-UUID PK, user_id owner column, server_updated_at
-- pull cursor, soft-delete via deleted_at (no delete policy), owner-scoped parent FK.
-- Image bytes live in Storage; this table stores only object keys + metadata.

-- Reuse the shared server_updated_at trigger function (idempotent redefinition).
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

create table if not exists public.media_assets (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  conversation_id uuid not null,
  source_message_id uuid null,
  conversation_order bigint not null,
  master_object_path text null,
  thumbnail_object_path text null,
  mime_type text null,
  width int null,
  height int null,
  byte_size bigint null,
  sha256 text null,
  source text not null default 'PHOTO_PICKER',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz null,
  server_updated_at timestamptz not null default now(),
  schema_version int not null default 1
);

alter table public.media_assets add column if not exists user_id uuid default auth.uid() references auth.users(id) on delete cascade;
alter table public.media_assets add column if not exists conversation_id uuid;
alter table public.media_assets add column if not exists source_message_id uuid null;
alter table public.media_assets add column if not exists conversation_order bigint;
alter table public.media_assets add column if not exists master_object_path text null;
alter table public.media_assets add column if not exists thumbnail_object_path text null;
alter table public.media_assets add column if not exists mime_type text null;
alter table public.media_assets add column if not exists width int null;
alter table public.media_assets add column if not exists height int null;
alter table public.media_assets add column if not exists byte_size bigint null;
alter table public.media_assets add column if not exists sha256 text null;
alter table public.media_assets add column if not exists source text not null default 'PHOTO_PICKER';
alter table public.media_assets add column if not exists created_at timestamptz not null default now();
alter table public.media_assets add column if not exists updated_at timestamptz not null default now();
alter table public.media_assets add column if not exists deleted_at timestamptz null;
alter table public.media_assets add column if not exists server_updated_at timestamptz not null default now();
alter table public.media_assets add column if not exists schema_version int not null default 1;

alter table public.media_assets alter column user_id set default auth.uid();
alter table public.media_assets alter column user_id set not null;
alter table public.media_assets alter column conversation_id set not null;
alter table public.media_assets alter column conversation_order set not null;
alter table public.media_assets alter column source set default 'PHOTO_PICKER';
alter table public.media_assets alter column created_at set default now();
alter table public.media_assets alter column updated_at set default now();
alter table public.media_assets alter column server_updated_at set default now();
alter table public.media_assets alter column schema_version set default 1;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'media_assets_id_user_id_unique'
      and conrelid = 'public.media_assets'::regclass
  ) then
    alter table public.media_assets
      add constraint media_assets_id_user_id_unique unique (id, user_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'media_assets_conversation_owner_fk'
      and conrelid = 'public.media_assets'::regclass
  ) then
    alter table public.media_assets
      add constraint media_assets_conversation_owner_fk
      foreign key (conversation_id, user_id)
      references public.ai_conversations (id, user_id)
      on delete cascade;
  end if;
end;
$$;

create index if not exists media_assets_user_server_cursor_idx
  on public.media_assets (user_id, server_updated_at, id);
create index if not exists media_assets_conversation_owner_fk_idx
  on public.media_assets (conversation_id, user_id);
create index if not exists media_assets_user_deleted_at_idx
  on public.media_assets (user_id, deleted_at);

alter table public.media_assets enable row level security;

drop policy if exists media_assets_select_own on public.media_assets;
drop policy if exists media_assets_insert_own_conversation on public.media_assets;
drop policy if exists media_assets_update_own_conversation on public.media_assets;

create policy media_assets_select_own
on public.media_assets
for select
using ((select auth.uid()) = user_id);

create policy media_assets_insert_own_conversation
on public.media_assets
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

create policy media_assets_update_own_conversation
on public.media_assets
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

drop trigger if exists media_assets_set_server_updated_at on public.media_assets;
create trigger media_assets_set_server_updated_at
before update on public.media_assets
for each row execute function public.dayzero_set_server_updated_at();

grant usage on schema public to anon, authenticated;
revoke all on public.media_assets from anon, authenticated;
-- No delete grant: media rows are soft-deleted via deleted_at, mirroring ai_chat tables.
grant select, insert, update on public.media_assets to authenticated;

comment on table public.media_assets is
  'DayZero media asset sync contract. Local UUIDs are remote primary keys. Rows are owner-scoped by user_id and soft-deleted with deleted_at. Image bytes live in the private media-assets Storage bucket; this table stores only object keys + metadata.';
comment on column public.media_assets.master_object_path is
  'Storage object key for the master JPEG under the media-assets bucket, e.g. {user_id}/{id}/master.jpg.';
comment on column public.media_assets.server_updated_at is
  'Database-controlled cursor for incremental media pull. Use with id as a stable secondary cursor.';

-- Private Storage bucket for photo bytes.
insert into storage.buckets (id, name, public)
values ('media-assets', 'media-assets', false)
on conflict (id) do nothing;

-- Owner-scoped Storage RLS: the first path segment must equal the caller's uid.
-- Object keys are {user_id}/{media_id}/master.jpg | thumb.jpg.
drop policy if exists media_assets_objects_select_own on storage.objects;
drop policy if exists media_assets_objects_insert_own on storage.objects;
drop policy if exists media_assets_objects_update_own on storage.objects;

create policy media_assets_objects_select_own
on storage.objects
for select
to authenticated
using (
  bucket_id = 'media-assets'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy media_assets_objects_insert_own
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'media-assets'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy media_assets_objects_update_own
on storage.objects
for update
to authenticated
using (
  bucket_id = 'media-assets'
  and (storage.foldername(name))[1] = (select auth.uid())::text
)
with check (
  bucket_id = 'media-assets'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);
