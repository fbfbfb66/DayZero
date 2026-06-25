-- Add fiber_g column to food_entries
alter table public.food_entries
add column if not exists fiber_g numeric null;
