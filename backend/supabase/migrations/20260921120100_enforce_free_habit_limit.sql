-- Free-tier habit cap: enforce it where it cannot be bypassed.
--
-- ## Why the Edge Function alone is not enough
--
-- `functions/habits/index.ts` now applies the cap to `POST /habits` and
-- `POST /habits/sync`, and that is the enforcement point the security review
-- asked for. But it is not the only door into this table: the Android client
-- writes habits with a direct PostgREST upsert (`SupabaseSync.upsert("habits",
-- …)` → `/rest/v1/habits`), which never reaches the function at all. A cap that
-- lives only in a function the client does not call is a cap on paper.
--
-- This trigger closes that: it is evaluated by Postgres on the write itself, so
-- every path into `habits` — the function, PostgREST, the dashboard, a future
-- client — is bounded by the same rule.
--
-- ## The rule, in the direction that matters
--
-- Phase 1 is explicit that the cap must never take a habit away. So this is a
-- BEFORE INSERT trigger only: it can refuse a NEW active habit, and it can do
-- nothing at all to a row that already exists. An update is never blocked, which
-- is what makes the promise "your existing habits are safe" structural rather
-- than a matter of the code behaving well.
--
-- Archived habits do not count (`is_archived = true`), matching the client's
-- `Habit.isActive` and the function's `countActiveHabits`. Paused habits do
-- count — pausing is a scheduling choice, not a way to free a slot.
--
-- ## Entitlement, derived here too
--
-- Premium is read from the same evidence the function uses rather than trusted
-- from the caller: a verified `founding_member_claims` row, or a verified
-- `tip_purchases` row (the client maps an active subscription product to the
-- unlimited-habits tier, so a paying user must never be capped).
--
-- Both lookups are written `IF EXISTS (SELECT 1 …)`: on a deployment that has
-- not created one of those tables yet the condition is simply false rather than
-- an error, which keeps this migration applicable in any order and keeps the
-- trigger failing CLOSED — an unknown entitlement is treated as free.

CREATE OR REPLACE FUNCTION public.enforce_free_habit_limit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  active_count integer;
  limit_value  integer;
  entitled     boolean;
BEGIN
  -- Rows that do not occupy a slot are always fine: an archived habit is put
  -- away, and a caller inserting one is not asking for a new slot.
  IF NEW.is_archived IS TRUE THEN
    RETURN NEW;
  END IF;

  limit_value := COALESCE(
    NULLIF(current_setting('app.free_habit_limit', true), '')::integer,
    5
  );

  SELECT count(*) INTO active_count
  FROM public.habits
  WHERE user_id = NEW.user_id
    AND is_archived = false;

  IF active_count < limit_value THEN
    RETURN NEW;
  END IF;

  SELECT (
    EXISTS (
      SELECT 1 FROM public.founding_member_claims
      WHERE user_identifier = NEW.user_id AND verified = true
    )
    OR EXISTS (
      SELECT 1 FROM public.tip_purchases
      WHERE user_identifier = NEW.user_id AND verified = true
    )
  ) INTO entitled;

  IF entitled THEN
    RETURN NEW;
  END IF;

  -- The SQLSTATE is chosen so PostgREST reports it as a client error
  -- (42501 → 403) and the function's `habit_limit` wire code can be raised
  -- alongside it. The message is written for a human reading it in a log: it
  -- states the limit and, importantly, that nothing was removed.
  RAISE EXCEPTION
    'free_habit_limit: the free plan allows % active habits; % are already in use. '
    'Nothing existing was removed — archive a habit or upgrade to add more.',
    limit_value, active_count
    USING ERRCODE = '42501';
END;
$$;

DROP TRIGGER IF EXISTS habits_enforce_free_limit ON public.habits;

CREATE TRIGGER habits_enforce_free_limit
  BEFORE INSERT ON public.habits
  FOR EACH ROW
  EXECUTE FUNCTION public.enforce_free_habit_limit();

COMMENT ON FUNCTION public.enforce_free_habit_limit() IS
  'BEFORE INSERT guard bounding active (non-archived) habits per user to the '
  'free-tier cap, unless the user has a verified entitlement. Never blocks '
  'UPDATE, so no existing habit can be lost.';
