-- Free-tier habit cap: enforce it where it cannot be bypassed.
--
-- ## Why the Edge Function alone is not enough
--
-- `functions/habits/index.ts` applies the cap to `POST /habits` and
-- `POST /habits/sync`, and that is the enforcement point the security review
-- asked for. But it is not the only door into this table: the Android client
-- writes habits with a direct PostgREST upsert (`SupabaseSync.upsert("habits",
-- …)` → `/rest/v1/habits?on_conflict=id` with `Prefer: resolution=merge-duplicates`),
-- which never reaches the function at all. A cap that lives only in a function
-- the client does not call is a cap on paper.
--
-- This trigger closes that: it is evaluated by Postgres on the write itself, so
-- every path into `habits` — the function, PostgREST, the dashboard, a future
-- client — is bounded by the same rule.
--
-- ## Three defects in the first version, found by running it
--
-- The version of this migration that was merged would not have under-enforced
-- the cap — it would have broken habit writing outright. None of the three was
-- visible by reading it; they were found by applying it to the project and
-- exercising it:
--
--   1. `user_identifier = NEW.user_id` is `text = uuid`.
--      `founding_member_claims.user_identifier` (and `tip_purchases.user_identifier`)
--      are `text`, while `habits.user_id` is `uuid`. Postgres raises
--      `operator does not exist: text = uuid`, so the function aborts — on
--      EVERY insert, at any habit count.
--
--   2. `founding_member_claims` had no `verified` column. On this project it is
--      added by `20260916100000_founding_claim_requires_verified_purchase.sql`,
--      which had not been applied; referencing it is a hard error.
--
--   3. `tip_purchases` did not exist on this project at all. It is created by
--      `20260830120000_tip_purchases.sql`, also not applied.
--
-- Defects 2 and 3 are why the entitlement lookup is written **defensively**
-- below: it checks the catalog before touching anything and degrades to "not
-- entitled" on any failure. That keeps the guard failing CLOSED — an unknown
-- entitlement is treated as free, so the cap is never silently raised — while
-- never *erroring*, which is what a missing table/column would otherwise do.
-- Once the earlier migrations are applied the same function automatically uses
-- `verified = true`; no code change is needed.
--
-- ## The rule, in the direction that matters
--
-- Phase 1 is explicit that the cap must never take a habit away. That is why
-- this is a BEFORE INSERT guard only: it can refuse a NEW active habit, and it
-- can do nothing at all to a row that already exists. An UPDATE is never
-- blocked, which makes the promise "your existing habits are safe" structural
-- rather than a matter of the code behaving well.
--
-- The one exception is load-bearing and is not optional: a row whose primary key
-- already exists must be let through. A `BEFORE INSERT` trigger fires for the
-- INSERT half of `INSERT … ON CONFLICT DO UPDATE` *before* Postgres discovers
-- the conflict, and the Android client syncs with exactly that shape — the whole
-- habit list, existing rows included, on every sync. Without the exemption a
-- user at the cap would have their own existing habits refused with a 403, i.e.
-- the trigger reporting "5 are already in use" about the very rows it was asked
-- to re-write. That is the cap losing data. (Verified both ways: REFUSED with
-- the exemption removed, ALLOWED with it in place.)
--
-- Archived habits do not count (`is_archived = true`), matching the client's
-- `Habit.isActive` and the function's `countActiveHabits`. Paused habits do
-- count — pausing is a scheduling choice, not a way to free a slot.

-- ── Entitlement, resolved defensively ────────────────────────────────────────
-- Reads the same evidence the Edge Function does — a founding-member claim, or a
-- tip purchase — without trusting the caller, and without assuming this
-- deployment has either table or their columns.
CREATE OR REPLACE FUNCTION public.has_unlimited_habits(uid uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public
AS $fn$
DECLARE
  entitled     boolean := false;
  has_verified boolean;
BEGIN
  IF uid IS NULL THEN
    RETURN false;
  END IF;

  -- Founding-member claims.
  IF to_regclass('public.founding_member_claims') IS NOT NULL THEN
    SELECT EXISTS (
      SELECT 1 FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname = 'founding_member_claims'
        AND a.attname = 'verified' AND a.attnum > 0 AND NOT a.attisdropped
    ) INTO has_verified;
    BEGIN
      IF has_verified THEN
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM public.founding_member_claims '
                'WHERE user_identifier = $1 AND verified = true)'
          INTO entitled USING uid::text;
      ELSE
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM public.founding_member_claims '
                'WHERE user_identifier = $1)'
          INTO entitled USING uid::text;
      END IF;
    EXCEPTION WHEN others THEN
      entitled := false;
    END;
  END IF;

  -- Tip purchases / subscriptions.
  IF NOT entitled AND to_regclass('public.tip_purchases') IS NOT NULL THEN
    SELECT EXISTS (
      SELECT 1 FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname = 'tip_purchases'
        AND a.attname = 'verified' AND a.attnum > 0 AND NOT a.attisdropped
    ) INTO has_verified;
    BEGIN
      IF has_verified THEN
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM public.tip_purchases '
                'WHERE user_identifier = $1 AND verified = true)'
          INTO entitled USING uid::text;
      ELSE
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM public.tip_purchases '
                'WHERE user_identifier = $1)'
          INTO entitled USING uid::text;
      END IF;
    EXCEPTION WHEN others THEN
      entitled := false;
    END;
  END IF;

  RETURN entitled;
END;
$fn$;

COMMENT ON FUNCTION public.has_unlimited_habits(uuid) IS
  'True when the user holds unlimited-habit entitlement (founding-member claim or '
  'tip purchase). Schema-agnostic: absent tables/columns resolve to false rather '
  'than raising, so it can never break a write.';

-- ── The cap, for genuinely new habits ────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.enforce_free_habit_limit()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  active_count integer;
  limit_value  integer;
BEGIN
  -- An archived habit is put away: the caller is not asking for a slot.
  IF NEW.is_archived IS TRUE THEN
    RETURN NEW;
  END IF;

  -- Re-writing a row that already exists is never a request for a new slot.
  -- See the header: without this, an upsert of the user's own existing habits
  -- would be refused once they are at the cap, which is how a sync would lose
  -- data. `id` is the primary key (public.habits.id uuid, PK).
  IF EXISTS (SELECT 1 FROM public.habits h WHERE h.id = NEW.id) THEN
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

  IF public.has_unlimited_habits(NEW.user_id) THEN
    RETURN NEW;
  END IF;

  -- SQLSTATE 42501 is chosen so PostgREST reports it as a client error
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

COMMENT ON FUNCTION public.enforce_free_habit_limit() IS
  'BEFORE INSERT guard bounding active (non-archived) habits per user to the '
  'free-tier cap, unless the user has a verified entitlement. Exempts rows that '
  'already exist (so upserting existing habits is never refused) and never '
  'blocks UPDATE, so no existing habit can be lost.';

DROP TRIGGER IF EXISTS habits_enforce_free_limit ON public.habits;

CREATE TRIGGER habits_enforce_free_limit
  BEFORE INSERT ON public.habits
  FOR EACH ROW
  EXECUTE FUNCTION public.enforce_free_habit_limit();

-- The cap is evaluated as "how many ACTIVE habits does this user have", so the
-- partial index is exactly that query.
CREATE INDEX IF NOT EXISTS idx_habits_active_per_user
  ON public.habits (user_id)
  WHERE is_archived = false;
