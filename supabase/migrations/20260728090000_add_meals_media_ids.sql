-- Stores canonical, ordered media ownership on meals.  This migration is intentionally not deployed by the app.
alter table public.meals
  add column if not exists media_ids text[] not null default '{}'::text[];
