-- Per-habit tracker links.
--
-- ## Why the link is a column on `habits` rather than a table of its own
--
-- It is a property of the habit, in the same sense `alarm_message` and
-- `repeat_days_mask` are: it is deleted with the habit, shared with the habit,
-- and restored with it. A join table would need its own lifecycle and its own
-- orphan cleanup, and — worst — a row could outlive its habit and re-attach the
-- link to a different habit that later reused the id.
--
-- ## What is stored, and what deliberately is not
--
-- Stored: which providers the user has pointed at this habit, as provider names
-- (`{HEALTH_CONNECT,POLAR,STRAVA}`).
--
-- NOT stored: the OAuth credentials. A Strava/Polar token is bound to the
-- authorising device and is never uploaded — only the *fact* that a provider is
-- connected is kept locally, never the secret. This column therefore carries the
-- user's configuration, not their access.
--
-- The consequence on a restored row is benign and intended: a link arrives on a
-- device that has no matching connection, the row reads "Link" (the connection
-- is absent), and every import is refused until the user authorises that
-- provider on this device. No record is written, so no activity is attributed to
-- a source the user did not enable here.
--
-- ## Why an array with a default rather than NOT NULL
--
-- `'{}'` is the correct value for every habit that existed before this shipped,
-- and it is also the correct *behaviour*: an empty link set means "no tracker
-- feeds this habit", and an unbound provider resolves to no habit and imports
-- nothing. The pre-existing behaviour — every sport habit swept by every
-- connected provider, one account-wide report filed under each habit's own id —
-- is the defect this column exists to make impossible.
--
-- Every writer maintains one invariant: at most one habit names a given
-- provider. It is enforced in `HabitTrackerLinks.bindFor`, which moves the
-- binding rather than duplicating it. It is deliberately not a DB constraint,
-- because a violation there is not data corruption to reject — the read path
-- resolves it (first binder wins, the loser is reported) and rejecting the write
-- would lose the user's whole habit edit over a field that can be resolved.

alter table public.habits
    add column if not exists tracker_provider_ids text[] not null default '{}';

comment on column public.habits.tracker_provider_ids is
    'Providers linked to this habit, as provider names (HEALTH_CONNECT/POLAR/STRAVA). Empty = unbound, which means no tracker writes activity for this habit. Credentials are never stored here.';

-- A GIN index is not added: the column is read only as part of a whole-habit
-- select, and no query filters on it. Adding one would cost write throughput on
-- every habit edit to serve a lookup nothing performs.
