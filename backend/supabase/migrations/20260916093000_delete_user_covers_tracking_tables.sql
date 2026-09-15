-- ============================================================================
-- delete_user — cover the tables added after it was written
--
-- WHY THIS IS NEEDED
--
-- `delete_user()` erases the caller's rows and then the auth user. But every
-- table it names was written before habit tracking grew a payload: `habit_logs`
-- and `activity_data` were not in its list.
--
-- The gap is partly masked by ON DELETE CASCADE (both tables reference
-- auth.users(id)), so a delete of the auth row does eventually take them. But
-- relying on cascade here is the wrong guarantee for a deletion feature:
--   - the explicit DELETE statements are the auditable, reviewable statement of
--     what "delete my account" means, and they currently under-describe it;
--   - `activity_data` also references profiles(id), and any row whose user row
--     is removed without cascade taking effect survives, orphaned;
--   - an account-deletion request that leaves the user's journal notes and
--     recorded activities behind is a compliance problem, not a tidy-up.
--
-- This migration makes the function explicit about every table that holds user
-- data, in child-before-parent order.
-- ============================================================================

create or replace function public.delete_user()
returns void
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated';
  end if;

  -- ── Activity integrations ─────────────────────────────────────────────────
  -- activity_data references activity_connections, so it goes first.
  delete from public.activity_data where user_id = uid;
  delete from public.activity_connections where user_id = uid;

  -- ── Tracking payloads ─────────────────────────────────────────────────────
  -- The journal notes, measured durations and counts. Guarded by a catalog
  -- check so this function still runs on a project where the habit_logs
  -- migration has not been applied yet.
  if exists (select 1 from pg_tables where schemaname = 'public' and tablename = 'habit_logs') then
    execute 'delete from public.habit_logs where user_id = uid';
  end if;

  -- ── Core habit data (children before parents) ─────────────────────────────
  delete from public.checkins where user_id = uid;
  delete from public.habits where user_id = uid;
  delete from public.mood_log where user_id = uid;
  delete from public.settings where user_id = uid;

  -- HARD delete the auth user row so the email uniqueness constraint releases immediately
  delete from auth.users where id = uid;
end;
$$;

-- Secure the function so only the authenticated caller can invoke their own deletion
revoke all on function public.delete_user() from public;
grant execute on function public.delete_user() to authenticated;

comment on function public.delete_user() is
  'Erases the calling user''s data (activities, habit logs, check-ins, habits, mood, settings) then the auth user, in one transaction. Covers every user-data table added since the original version.';
