/**
 * habits — Supabase Edge Function
 *
 * Full-stack habit CRUD with alarm/reminder metadata. The Android client
 * stores habits + alarm times locally and schedules AlarmManager alarms.
 * This function keeps the cloud copy in sync and adds server-side
 * validation so the data is consistent across devices.
 *
 * Endpoints:
 *   GET    /habits           — list all habits for the authenticated user
 *   POST   /habits           — create a new habit (with optional alarm)
 *   PATCH  /habits/:id       — update a habit (name, reminder time, icon)
 *   DELETE /habits/:id       — delete a habit
 *   POST   /habits/sync      — bulk upsert (full device → cloud sync)
 *   GET    /habits/alarms     — get all active alarm times for the user
 *
 * Auth: requires a valid Supabase JWT in the Authorization header.
 */

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { HABIT_LIMIT_CODE, MAX_FREE_HABITS } from "../_shared/freeTier.ts";

// ── Constants ────────────────────────────────────────────────────────────────

const MAX_HABIT_NAME_LENGTH = 60;

// MAX_FREE_HABITS is deliberately NOT declared here. It is imported from
// `_shared/freeTier.ts` — the one place in the repository the number is written
// (fed from `freeHabitsMax` in android/gradle/libs.versions.toml, which the
// Android client builds its own copy from too). It used to be redeclared here as
// a private `const MAX_FREE_HABITS = 5` that was then never used at all, which is
// how the client came to show "5 of 5 active habits" while POST /habits and
// POST /habits/sync accepted unlimited creates. There is no local copy left to
// fall out of step.

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
};

// ── Types ────────────────────────────────────────────────────────────────────

interface HabitPayload {
  id?: string;
  name: string;
  icon_id?: string | null;
  reminder_minutes?: number | null;
  is_pinned?: boolean;
  duration_seconds?: number | null;
  /** Archived habits are excluded from the free-tier active count. */
  is_archived?: boolean;
  created_at_ms?: number;
}

interface SyncPayload {
  habits: HabitPayload[];
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function adminClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("Missing Supabase credentials");
  return createClient(url, key);
}

function userClient(authHeader: string) {
  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!url || !anonKey) throw new Error("Missing Supabase credentials");
  return createClient(url, anonKey, {
    global: { headers: { Authorization: authHeader } },
  });
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function error(message: string, status = 400) {
  return json({ error: message }, status);
}

async function getUserId(authHeader: string): Promise<string | null> {
  const client = userClient(authHeader);
  const {
    data: { user },
  } = await client.auth.getUser();
  return user?.id ?? null;
}

/** Validates a habit name and returns the sanitized value or null. */
function sanitizeName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim().slice(0, MAX_HABIT_NAME_LENGTH);
  return trimmed.length > 0 ? trimmed : null;
}

/** Validates reminder_minutes (0..1439 = minutes from midnight, or null). */
function sanitizeReminderMinutes(raw: unknown): number | null {
  if (raw === null || raw === undefined) return null;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0 || n > 1439) return null;
  return n;
}

// ── Free-tier habit cap ──────────────────────────────────────────────────────

/**
 * The `403` a blocked habit write answers with.
 *
 * `code: "habit_limit"` is the part the client acts on: SupabaseSync matches it
 * and raises the existing LimitReachedPaywallState, so the refusal reaches the
 * user as the upgrade prompt they already have rather than as a generic sync
 * error they can do nothing about.
 */
function habitLimitError(activeCount: number, blocked: string[] = []) {
  return json(
    {
      error:
        `The free plan includes ${MAX_FREE_HABITS} active habits ` +
        `(${activeCount} in use). Nothing already saved was removed.`,
      code: HABIT_LIMIT_CODE,
      limit: MAX_FREE_HABITS,
      active_count: activeCount,
      blocked,
    },
    403,
  );
}

/**
 * How many **active** habits this user has in the database.
 *
 * Archived rows (`is_archived = true`) are excluded and paused ones are counted —
 * Phase 1's rule, and the same set the client's "x of 5" indicator counts. A
 * project whose `habits` table has no `is_archived` column reports `undefined`,
 * which reads as active: an un-migrated deployment therefore counts every row,
 * which is the conservative direction — it can never let a client past the cap.
 */
async function countActiveHabits(
  supabase: ReturnType<typeof adminClient>,
  userId: string,
): Promise<number> {
  const { data, error: dbErr } = await supabase
    .from("habits")
    .select("id,is_archived")
    .eq("user_id", userId);
  if (dbErr) throw dbErr;
  return (data ?? []).filter(
    (row: { is_archived?: boolean | null }) => row.is_archived !== true,
  ).length;
}

/**
 * Whether this user is entitled to unlimited habits — derived **server-side**.
 *
 * ## Why it is derived here and not taken from the client
 *
 * The client is not a trustworthy source for its own entitlement: an edited APK
 * can answer "premium" and then create unlimited habits. The server has to reach
 * its own conclusion, so this reads evidence the server already holds rather than
 * a flag the caller supplies. There is deliberately **no request parameter** that
 * can set it.
 *
 * ## What the verdict is based on
 *
 *  * `founding_member_claims` — a claim exists only for a purchase Huawei's Order
 *    Service verified (`verified = true`), the strongest evidence in the DB.
 *  * `tip_purchases` — checked as well because the client's own tier mapping
 *    treats an active subscription product as UNLIMITED_HABITS, so a paying user
 *    must not be capped here.
 *
 * ## Which way it fails
 *
 * It fails **closed**: if the lookup errors, the user is treated as free. Failing
 * open would hand unlimited habits to anyone who could make one query error, and
 * this is a product limit rather than a safety one — a wrongly-capped free user
 * keeps every habit they already have (the cap gates creation only) and reaches
 * support, whereas a wrongly-unlimited account is a permanent revenue hole.
 *
 * ## The known gap, stated plainly
 *
 * A lapse in the store's subscription state is not mirrored here, so an existing
 * entitlement row keeps the cap lifted. That is the intended direction for Phase
 * 1: the spec is explicit that existing habits are never taken away, and
 * re-verifying subscriptions on every habit write is a billing-layer change of
 * its own. It fails toward the user, never toward data loss.
 */
async function hasUnlimitedHabits(userId: string): Promise<boolean> {
  try {
    const supabase = adminClient();
    const [{ data: claims }, { data: tips }] = await Promise.all([
      supabase
        .from("founding_member_claims")
        .select("id")
        .eq("user_identifier", userId)
        .eq("verified", true)
        .limit(1),
      supabase
        .from("tip_purchases")
        .select("id")
        .eq("user_identifier", userId)
        .eq("verified", true)
        .limit(1),
    ]);
    return (claims?.length ?? 0) > 0 || (tips?.length ?? 0) > 0;
  } catch (err) {
    console.error("Entitlement lookup failed; treating as free tier:", err);
    return false;
  }
}

// ── Route handlers ───────────────────────────────────────────────────────────

/** GET /habits — list all habits for the authenticated user. */
async function listHabits(userId: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("habits")
    .select("*")
    .eq("user_id", userId)
    .order("created_at_ms", { ascending: true });
  if (dbErr) throw dbErr;

  return json({
    habits: (data ?? []).map(mapHabitRow),
    count: data?.length ?? 0,
  });
}

/** POST /habits — create a single habit. */
async function createHabit(userId: string, body: HabitPayload) {
  const name = sanitizeName(body.name);
  if (!name) return error("Habit name is required (1–60 characters)");

  const supabase = adminClient();

  // ── Free-tier cap, enforced HERE ───────────────────────────────────────────
  // This is the enforcement point, and the reason the security review's blocking
  // item existed: the cap was declared and then never applied, so POST /habits
  // accepted unlimited creates while the client showed "5 of 5 active habits".
  // A client-side check cannot be the enforcement point precisely because the
  // client belongs to the user — this one cannot be edited away.
  const entitled = await hasUnlimitedHabits(userId);
  const currentCount = await countActiveHabits(supabase, userId);
  if (!entitled && currentCount >= MAX_FREE_HABITS) {
    return habitLimitError(currentCount);
  }

  const habitId = body.id ?? crypto.randomUUID();
  const now = body.created_at_ms ?? Date.now();

  const { data, error: dbErr } = await supabase
    .from("habits")
    .insert({
      id: habitId,
      user_id: userId,
      name,
      created_at_ms: now,
      icon_id: body.icon_id ?? null,
      reminder_minutes: sanitizeReminderMinutes(body.reminder_minutes),
      is_pinned: body.is_pinned ?? false,
      is_archived: body.is_archived ?? false,
      duration_seconds: body.duration_seconds ?? null,
    })
    .select()
    .single();
  if (dbErr) throw dbErr;

  return json(
    {
      habit: mapHabitRow(data),
      alarm: body.reminder_minutes != null
        ? {
            habit_id: habitId,
            habit_name: name,
            reminder_minutes: sanitizeReminderMinutes(body.reminder_minutes),
            description: formatAlarmTime(body.reminder_minutes as number),
          }
        : null,
    },
    201,
  );
}

/** PATCH /habits/:id — update a habit. */
async function updateHabit(userId: string, habitId: string, body: Partial<HabitPayload>) {
  const supabase = adminClient();

  const updates: Record<string, unknown> = {};
  if (body.name !== undefined) {
    const name = sanitizeName(body.name);
    if (!name) return error("Habit name must be 1–60 characters");
    updates.name = name;
  }
  if (body.reminder_minutes !== undefined) {
    updates.reminder_minutes = sanitizeReminderMinutes(body.reminder_minutes);
  }
  if (body.icon_id !== undefined) {
    updates.icon_id = body.icon_id;
  }
  if (body.is_pinned !== undefined) {
    updates.is_pinned = !!body.is_pinned;
  }
  if (body.duration_seconds !== undefined) {
    updates.duration_seconds = body.duration_seconds;
  }

  if (Object.keys(updates).length === 0) {
    return error("No valid fields to update");
  }

  const { data, error: dbErr } = await supabase
    .from("habits")
    .update(updates)
    .eq("id", habitId)
    .eq("user_id", userId)
    .select()
    .single();
  if (dbErr) throw dbErr;
  if (!data) return error("Habit not found", 404);

  return json({ habit: mapHabitRow(data) });
}

/** DELETE /habits/:id — delete a habit and its check-ins. */
async function deleteHabit(userId: string, habitId: string) {
  const supabase = adminClient();

  // Delete check-ins first (foreign key)
  await supabase
    .from("checkins")
    .delete()
    .eq("habit_id", habitId)
    .eq("user_id", userId);

  const { error: dbErr } = await supabase
    .from("habits")
    .delete()
    .eq("id", habitId)
    .eq("user_id", userId);
  if (dbErr) throw dbErr;

  return json({
    deleted: true,
    habit_id: habitId,
    alarm_cancelled: true,
  });
}

/** POST /habits/sync — bulk upsert from device. */
async function syncHabits(userId: string, body: SyncPayload) {
  if (!Array.isArray(body.habits)) {
    return error("Expected { habits: [...] }");
  }

  const supabase = adminClient();

  // ── What this user already has ─────────────────────────────────────────────
  // id + is_archived, so the cap can be applied to the ACTIVE set exactly as
  // createHabit does. The previous version fetched only `id` and then never used
  // it at all — which is why sync was a second, completely unbounded way into the
  // same table.
  const { data: existingRows } = await supabase
    .from("habits")
    .select("id,is_archived")
    .eq("user_id", userId);
  const existingActive = new Map<string, boolean>();
  (existingRows ?? []).forEach((h: { id: string; is_archived?: boolean | null }) => {
    existingActive.set(h.id, h.is_archived !== true);
  });

  const incoming = body.habits
    .filter((h) => sanitizeName(h.name))
    .map((h) => ({
      id: h.id ?? crypto.randomUUID(),
      user_id: userId,
      name: sanitizeName(h.name)!,
      created_at_ms: h.created_at_ms ?? Date.now(),
      icon_id: h.icon_id ?? null,
      reminder_minutes: sanitizeReminderMinutes(h.reminder_minutes),
      is_pinned: h.is_pinned ?? false,
      is_archived: h.is_archived ?? false,
      duration_seconds: h.duration_seconds ?? null,
    }));

  // ── Bound the sync to the same cap ────────────────────────────────────────
  // The rule, applied to a whole batch: a row that ALREADY EXISTS is always
  // accepted (it is an update, and an existing habit is never dropped or
  // refused), and new ACTIVE rows are capped at the remaining free slots.
  // Archiving frees a slot, which is what makes "archive one, add one" a real
  // answer to the cap instead of a dead end.
  //
  // A refusal is raised only when something was actually DROPPED. A client whose
  // habits all already exist — the ordinary "push my device state" call — syncs
  // untouched even while it is over the cap, so an existing user cannot lose a
  // habit to this path. That is the same no-data-loss rule the client's
  // LimitReachedPaywallState promises, enforced on the server side of it.
  const entitled = await hasUnlimitedHabits(userId);
  const activeExisting = [...existingActive.values()].filter(Boolean).length;
  const room = Math.max(0, MAX_FREE_HABITS - activeExisting);
  const dropped: string[] = [];
  let kept = 0;
  const toUpsert = entitled
    ? incoming
    : incoming.filter((row) => {
        if (row.is_archived || existingActive.has(row.id)) return true;
        if (kept >= room) {
          dropped.push(row.name);
          return false;
        }
        kept += 1;
        return true;
      });

  if (toUpsert.length > 0) {
    const { error: dbErr } = await supabase
      .from("habits")
      .upsert(toUpsert, { onConflict: "id" });
    if (dbErr) throw dbErr;
  }

  // Build the alarm schedule from what was ACTUALLY synced, so the alarms the
  // client is told about match the habits that landed.
  const alarms = toUpsert
    .filter((h) => h.reminder_minutes != null)
    .map((h) => ({
      habit_id: h.id,
      habit_name: h.name,
      reminder_minutes: h.reminder_minutes,
      description: formatAlarmTime(h.reminder_minutes as number),
    }));

  if (dropped.length > 0) {
    return habitLimitError(activeExisting + kept, dropped);
  }

  return json({
    synced: toUpsert.length,
    alarms,
    message: `Synced ${toUpsert.length} habit(s) with ${alarms.length} alarm(s)`,
  });
}

/** GET /habits/alarms — returns all active alarm configurations. */
async function listAlarms(userId: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("habits")
    .select("*")
    .eq("user_id", userId)
    .order("created_at_ms", { ascending: true });
  if (dbErr) throw dbErr;

  // The habits table doesn't store reminder_minutes in the cloud schema yet;
  // we return the full habit list so the client can reconcile its local alarms.
  return json({
    habits: (data ?? []).map(mapHabitRow),
    message: "Client should reconcile local AlarmManager state with this list",
  });
}

// ── Utility ──────────────────────────────────────────────────────────────────

function mapHabitRow(row: Record<string, unknown>) {
  return {
    id: row.id,
    name: row.name,
    user_id: row.user_id,
    created_at_ms: row.created_at_ms,
    created_at: row.created_at,
    icon_id: row.icon_id ?? null,
    reminder_minutes: row.reminder_minutes ?? null,
    is_pinned: row.is_pinned ?? false,
    is_archived: row.is_archived ?? false,
    duration_seconds: row.duration_seconds ?? null,
  };
}

/** Human-readable alarm time string from minutes-from-midnight. */
function formatAlarmTime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const period = h >= 12 ? "PM" : "AM";
  const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
  return `${h12}:${String(m).padStart(2, "0")} ${period}`;
}

// ── Router ───────────────────────────────────────────────────────────────────

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return error("Missing Authorization header", 401);

    const userId = await getUserId(authHeader);
    if (!userId) return error("Invalid or expired token", 401);

    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, ""); // trim trailing slashes
    const segments = path.split("/").filter(Boolean);

    // Route: /habits/sync
    if (req.method === "POST" && segments[segments.length - 1] === "sync") {
      const body = await req.json();
      return await syncHabits(userId, body);
    }

    // Route: /habits/alarms
    if (req.method === "GET" && segments[segments.length - 1] === "alarms") {
      return await listAlarms(userId);
    }

    // Route: /habits/:id (PATCH, DELETE)
    const habitId = segments.length >= 2 ? segments[segments.length - 1] : null;
    if (habitId && habitId !== "habits" && habitId !== "sync" && habitId !== "alarms") {
      if (req.method === "PATCH") {
        const body = await req.json();
        return await updateHabit(userId, habitId, body);
      }
      if (req.method === "DELETE") {
        return await deleteHabit(userId, habitId);
      }
    }

    // Route: /habits (GET, POST)
    if (req.method === "GET") {
      return await listHabits(userId);
    }
    if (req.method === "POST") {
      const body = await req.json();
      return await createHabit(userId, body);
    }

    return error("Method not allowed", 405);
  } catch (err) {
    console.error("habits function error:", err);
    return error("Internal server error", 500);
  }
});
