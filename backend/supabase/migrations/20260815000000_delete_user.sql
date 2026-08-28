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

  -- Delete children tables before parents to respect constraints
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

