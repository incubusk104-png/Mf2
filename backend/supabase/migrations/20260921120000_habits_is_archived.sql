-- Archived habits: put away, never deleted.
--
-- Phase 1's free-tier cap counts **active** habits — archived ones are excluded,
-- paused ones are counted. Archiving is the user's own way of freeing a slot, and
-- it has to be non-destructive to be offered honestly: an archived habit keeps
-- its check-ins (FK `checkins.habit_id` is untouched), its streak history, its
-- habit_logs and its alarm metadata. Nothing in this migration deletes, moves or
-- rewrites a row.
--
-- Deliberately NOT part of the `habits` RLS/ownership story: this is one more
-- nullable-by-default column on a table that already has them, and the existing
-- policies apply unchanged.
--
-- DEFAULT false with NOT NULL, so every habit that already exists reads as
-- ACTIVE. That is the direction that matters: an update must never make a habit
-- the user already has look archived (which would quietly drop their alarm
-- handling and free a slot they are actually using). A user who wants a slot back
-- archives a habit themselves.
--
-- The Edge Function's `hasUnlimitedHabits`/`countActiveHabits` and the Android
-- client's `Habit.isActive` both read this one column, so the count the client
-- shows and the count the server enforces come from the same fact.

ALTER TABLE public.habits
  ADD COLUMN IF NOT EXISTS is_archived boolean NOT NULL DEFAULT false;

-- The cap is evaluated as "how many ACTIVE habits does this user have", so the
-- partial index is exactly that query.
CREATE INDEX IF NOT EXISTS idx_habits_active_per_user
  ON public.habits (user_id)
  WHERE is_archived = false;

COMMENT ON COLUMN public.habits.is_archived IS
  'Habit put away by the user. Excluded from the free-tier active-habit count '
  '(Phase 1: archived excluded, paused counted); nothing is deleted and the '
  'habit''s check-ins, logs and alarm metadata are retained.';
