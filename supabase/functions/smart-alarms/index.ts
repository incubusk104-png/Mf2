/**
 * smart-alarms — Supabase Edge Function
 *
 * Analyzes the user's activity data to detect timing patterns and
 * inconsistencies, then generates/updates adaptive alarm schedules.
 *
 * The core problem: users have inconsistent habit times — they run at 7 AM
 * on weekdays but 10 AM on weekends, or their yoga time drifts throughout
 * the week. Smart alarms learn from their ACTUAL activity data (Strava,
 * Google Fit, Huawei Health) and suggest optimal alarm times.
 *
 * Endpoints:
 *   POST /smart-alarms/analyze       — Analyze activity patterns & generate alarms
 *   GET  /smart-alarms               — List all smart alarms for user
 *   PATCH /smart-alarms/:id          — Update alarm (enable/disable, override time)
 *   DELETE /smart-alarms/:id         — Delete a smart alarm
 *   GET  /smart-alarms/patterns      — View detected activity patterns
 *
 * Auth: requires a valid Supabase JWT.
 */

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
};

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
  const { data: { user } } = await client.auth.getUser();
  return user?.id ?? null;
}

function formatTime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const period = h >= 12 ? "PM" : "AM";
  const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
  return `${h12}:${String(m).padStart(2, "0")} ${period}`;
}

// ── Pattern Analysis Engine ──────────────────────────────────────────────────

interface ActivityRecord {
  activity_type: string;
  started_at: string;
  duration_seconds: number | null;
  activity_date: string;
}

interface DetectedPattern {
  activity_type: string;
  weekday_avg_minutes: number;
  weekend_avg_minutes: number;
  overall_avg_minutes: number;
  variance_minutes: number;
  count: number;
  is_inconsistent: boolean;
  weekday_count: number;
  weekend_count: number;
  suggested_weekday_alarm: number;
  suggested_weekend_alarm: number;
  confidence: number;
  message: string;
}

function analyzePatterns(activities: ActivityRecord[]): DetectedPattern[] {
  // Group by activity type
  const byType: Record<string, { weekday: number[]; weekend: number[] }> = {};

  for (const a of activities) {
    const type = a.activity_type;
    if (!byType[type]) byType[type] = { weekday: [], weekend: [] };

    const start = new Date(a.started_at);
    const dayOfWeek = start.getDay(); // 0=Sun, 6=Sat
    const minutes = start.getHours() * 60 + start.getMinutes();

    if (dayOfWeek === 0 || dayOfWeek === 6) {
      byType[type].weekend.push(minutes);
    } else {
      byType[type].weekday.push(minutes);
    }
  }

  const patterns: DetectedPattern[] = [];

  for (const [type, data] of Object.entries(byType)) {
    const allTimes = [...data.weekday, ...data.weekend];
    if (allTimes.length < 2) continue;

    const mean = (arr: number[]) =>
      arr.length > 0 ? Math.round(arr.reduce((s, v) => s + v, 0) / arr.length) : 0;
    const stddev = (arr: number[]) => {
      if (arr.length < 2) return 0;
      const m = arr.reduce((s, v) => s + v, 0) / arr.length;
      return Math.round(Math.sqrt(arr.reduce((s, v) => s + (v - m) ** 2, 0) / arr.length));
    };

    const weekdayAvg = mean(data.weekday);
    const weekendAvg = mean(data.weekend);
    const overallAvg = mean(allTimes);
    const variance = stddev(allTimes);
    const isInconsistent = variance > 60; // More than 1 hour of variation

    // Suggest alarm = average time minus 15 min buffer for preparation
    const buffer = 15;
    const suggestedWeekday = data.weekday.length > 0
      ? Math.max(0, weekdayAvg - buffer)
      : Math.max(0, overallAvg - buffer);
    const suggestedWeekend = data.weekend.length > 0
      ? Math.max(0, weekendAvg - buffer)
      : Math.max(0, overallAvg - buffer);

    // Confidence based on data count and consistency
    const countFactor = Math.min(allTimes.length / 14, 1); // Max confidence at 14+ records
    const consistencyFactor = Math.max(0, 1 - variance / 240); // Drops as variance increases
    const confidence = Math.round((countFactor * 0.6 + consistencyFactor * 0.4) * 100) / 100;

    let message: string;
    if (isInconsistent) {
      const diffMinutes = Math.abs(weekdayAvg - weekendAvg);
      if (data.weekday.length > 0 && data.weekend.length > 0 && diffMinutes > 60) {
        message = `You ${type} at ~${formatTime(weekdayAvg)} on weekdays but ~${formatTime(weekendAvg)} on weekends. We'll set separate alarms.`;
      } else {
        message = `Your ${type} timing varies by ~${variance} minutes. We suggest ${formatTime(suggestedWeekday)} to build consistency.`;
      }
    } else {
      message = `Great consistency! You usually ${type} around ${formatTime(overallAvg)}. Alarm set for ${formatTime(suggestedWeekday)}.`;
    }

    patterns.push({
      activity_type: type,
      weekday_avg_minutes: weekdayAvg,
      weekend_avg_minutes: weekendAvg,
      overall_avg_minutes: overallAvg,
      variance_minutes: variance,
      count: allTimes.length,
      is_inconsistent: isInconsistent,
      weekday_count: data.weekday.length,
      weekend_count: data.weekend.length,
      suggested_weekday_alarm: suggestedWeekday,
      suggested_weekend_alarm: suggestedWeekend,
      confidence,
      message,
    });
  }

  return patterns.sort((a, b) => b.count - a.count);
}

// Generate alarm messages based on activity type and timing
function generateAlarmMessage(type: string, timeMinutes: number, isInconsistent: boolean): string {
  const timeStr = formatTime(timeMinutes);
  const messages: Record<string, string[]> = {
    run: [
      `Time for your run! Lace up at ${timeStr}`,
      `Your body's ready for a run. Let's go at ${timeStr}!`,
    ],
    walk: [
      `Walking time! Step out at ${timeStr}`,
      `A walk does wonders. Head out at ${timeStr}`,
    ],
    cycle: [
      `Cycling time! Hop on at ${timeStr}`,
      `Time to ride. Your bike awaits at ${timeStr}`,
    ],
    yoga: [
      `Yoga time. Unroll your mat at ${timeStr}`,
      `Breathe and stretch. Yoga starts at ${timeStr}`,
    ],
    workout: [
      `Workout time! Get moving at ${timeStr}`,
      `Your workout window opens at ${timeStr}. Let's go!`,
    ],
    swim: [
      `Pool time! Dive in at ${timeStr}`,
      `Swimming session at ${timeStr}. Grab your towel!`,
    ],
    sleep: [
      `Time to wind down. Start your bedtime routine at ${timeStr}`,
      `Sleep well! Begin winding down at ${timeStr}`,
    ],
  };

  const typeMessages = messages[type] || [`Time for your ${type} at ${timeStr}!`];
  const base = typeMessages[Math.floor(Math.random() * typeMessages.length)];

  if (isInconsistent) {
    return `${base} (Your timing varies — this alarm adapts to your pattern.)`;
  }
  return base;
}

// ── Route handlers ───────────────────────────────────────────────────────────

/** POST /analyze — Analyze activity patterns & generate/update smart alarms */
async function analyzeAndGenerateAlarms(userId: string) {
  const supabase = adminClient();

  // Fetch last 30 days of activity data
  const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 10);

  const { data: activities, error: actErr } = await supabase
    .from("activity_data")
    .select("activity_type, started_at, duration_seconds, activity_date")
    .eq("user_id", userId)
    .gte("activity_date", thirtyDaysAgo)
    .order("started_at", { ascending: true });

  if (actErr) throw actErr;

  if (!activities || activities.length === 0) {
    return json({
      patterns: [],
      alarms_created: 0,
      message: "No activity data found. Connect and sync a provider first.",
    });
  }

  const patterns = analyzePatterns(activities as ActivityRecord[]);

  // Get existing smart alarms to avoid duplicates
  const { data: existingAlarms } = await supabase
    .from("smart_alarms")
    .select("id, alarm_type, is_user_overridden")
    .eq("user_id", userId);

  const existingIds = new Set(
    (existingAlarms ?? []).map((a: Record<string, unknown>) => a.id)
  );

  let alarmsCreated = 0;
  const generatedAlarms = [];

  for (const pattern of patterns) {
    if (pattern.count < 3) continue; // Need at least 3 records for a reliable alarm

    // Weekday/weekend split for inconsistent patterns
    const hasSplit =
      pattern.is_inconsistent &&
      pattern.weekday_count >= 2 &&
      pattern.weekend_count >= 2 &&
      Math.abs(pattern.weekday_avg_minutes - pattern.weekend_avg_minutes) > 60;

    if (hasSplit) {
      // Create two alarms: weekday + weekend
      for (const [label, minutes, daysMask] of [
        ["weekday", pattern.suggested_weekday_alarm, 0b0111110] as const, // Mon-Fri
        ["weekend", pattern.suggested_weekend_alarm, 0b1000001] as const, // Sat-Sun
      ]) {
        const message = generateAlarmMessage(
          pattern.activity_type,
          minutes,
          true
        );

        const { data: alarm, error: alarmErr } = await supabase
          .from("smart_alarms")
          .upsert(
            {
              user_id: userId,
              alarm_type: "activity_nudge",
              suggested_minutes: minutes,
              active_minutes: minutes,
              days_of_week: daysMask,
              confidence_score: pattern.confidence,
              based_on_activities: pattern.count,
              avg_activity_time_minutes: label === "weekday"
                ? pattern.weekday_avg_minutes
                : pattern.weekend_avg_minutes,
              variance_minutes: pattern.variance_minutes,
              message,
              is_enabled: true,
            },
            { onConflict: "id" }
          )
          .select()
          .single();

        if (!alarmErr && alarm) {
          alarmsCreated++;
          generatedAlarms.push(alarm);
        }
      }
    } else {
      // Single alarm for all days
      const minutes = pattern.suggested_weekday_alarm;
      const message = generateAlarmMessage(
        pattern.activity_type,
        minutes,
        pattern.is_inconsistent
      );

      const { data: alarm, error: alarmErr } = await supabase
        .from("smart_alarms")
        .upsert(
          {
            user_id: userId,
            alarm_type: "activity_nudge",
            suggested_minutes: minutes,
            active_minutes: minutes,
            days_of_week: 127, // All days
            confidence_score: pattern.confidence,
            based_on_activities: pattern.count,
            avg_activity_time_minutes: pattern.overall_avg_minutes,
            variance_minutes: pattern.variance_minutes,
            message,
            is_enabled: true,
          },
          { onConflict: "id" }
        )
        .select()
        .single();

      if (!alarmErr && alarm) {
        alarmsCreated++;
        generatedAlarms.push(alarm);
      }
    }

    // If pattern shows a potential streak break, add consistency check alarm
    if (pattern.is_inconsistent && pattern.confidence > 0.3) {
      const nudgeTime = Math.max(
        0,
        pattern.overall_avg_minutes - 30 // 30 min before usual time
      );
      const { data: alarm } = await supabase
        .from("smart_alarms")
        .upsert(
          {
            user_id: userId,
            alarm_type: "consistency_check",
            suggested_minutes: nudgeTime,
            active_minutes: nudgeTime,
            days_of_week: 127,
            confidence_score: pattern.confidence * 0.8,
            based_on_activities: pattern.count,
            avg_activity_time_minutes: pattern.overall_avg_minutes,
            variance_minutes: pattern.variance_minutes,
            message: `Heads up! You usually ${pattern.activity_type} around ${formatTime(pattern.overall_avg_minutes)} but your timing varies. Don't let the streak slip!`,
            is_enabled: true,
          },
          { onConflict: "id" }
        )
        .select()
        .single();
      if (alarm) {
        alarmsCreated++;
        generatedAlarms.push(alarm);
      }
    }
  }

  return json({
    patterns,
    alarms_created: alarmsCreated,
    alarms: generatedAlarms.map(mapAlarmRow),
    message: `Analyzed ${activities.length} activities. Generated ${alarmsCreated} smart alarm(s).`,
  });
}

/** GET / — List all smart alarms for user */
async function listAlarms(userId: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("smart_alarms")
    .select("*")
    .eq("user_id", userId)
    .order("active_minutes", { ascending: true });
  if (dbErr) throw dbErr;

  return json({
    alarms: (data ?? []).map(mapAlarmRow),
    count: data?.length ?? 0,
  });
}

/** PATCH /:id — Update alarm */
async function updateAlarm(
  userId: string,
  alarmId: string,
  body: Record<string, unknown>
) {
  const supabase = adminClient();
  const updates: Record<string, unknown> = {};

  if (body.is_enabled !== undefined) updates.is_enabled = !!body.is_enabled;
  if (body.active_minutes !== undefined) {
    const m = Number(body.active_minutes);
    if (!Number.isInteger(m) || m < 0 || m > 1439) {
      return error("active_minutes must be 0–1439");
    }
    updates.active_minutes = m;
    updates.is_user_overridden = true;
  }
  if (body.days_of_week !== undefined) {
    const d = Number(body.days_of_week);
    if (!Number.isInteger(d) || d < 0 || d > 127) {
      return error("days_of_week must be 0–127");
    }
    updates.days_of_week = d;
  }
  if (body.message !== undefined) {
    updates.message = String(body.message).slice(0, 200);
  }

  if (Object.keys(updates).length === 0) {
    return error("No valid fields to update");
  }

  const { data, error: dbErr } = await supabase
    .from("smart_alarms")
    .update(updates)
    .eq("id", alarmId)
    .eq("user_id", userId)
    .select()
    .single();
  if (dbErr) throw dbErr;
  if (!data) return error("Alarm not found", 404);

  return json({ alarm: mapAlarmRow(data) });
}

/** DELETE /:id — Delete a smart alarm */
async function deleteAlarm(userId: string, alarmId: string) {
  const supabase = adminClient();
  const { error: dbErr } = await supabase
    .from("smart_alarms")
    .delete()
    .eq("id", alarmId)
    .eq("user_id", userId);
  if (dbErr) throw dbErr;

  return json({ deleted: true, alarm_id: alarmId });
}

/** GET /patterns — View detected activity patterns */
async function viewPatterns(userId: string) {
  const supabase = adminClient();
  const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 10);

  const { data: activities, error: actErr } = await supabase
    .from("activity_data")
    .select("activity_type, started_at, duration_seconds, activity_date")
    .eq("user_id", userId)
    .gte("activity_date", thirtyDaysAgo)
    .order("started_at", { ascending: true });

  if (actErr) throw actErr;

  const patterns = analyzePatterns((activities ?? []) as ActivityRecord[]);
  return json({ patterns, activity_count: activities?.length ?? 0 });
}

// ── Utilities ────────────────────────────────────────────────────────────────

function mapAlarmRow(row: Record<string, unknown>) {
  return {
    id: row.id,
    user_id: row.user_id,
    habit_id: row.habit_id ?? null,
    alarm_type: row.alarm_type,
    suggested_minutes: row.suggested_minutes,
    suggested_time: formatTime(row.suggested_minutes as number),
    active_minutes: row.active_minutes,
    active_time: row.active_minutes != null
      ? formatTime(row.active_minutes as number)
      : null,
    days_of_week: row.days_of_week,
    days_label: formatDaysOfWeek(row.days_of_week as number),
    confidence_score: row.confidence_score,
    based_on_activities: row.based_on_activities,
    avg_activity_time_minutes: row.avg_activity_time_minutes,
    variance_minutes: row.variance_minutes,
    is_enabled: row.is_enabled,
    is_user_overridden: row.is_user_overridden,
    message: row.message,
    last_triggered_at: row.last_triggered_at,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

function formatDaysOfWeek(mask: number): string {
  if (mask === 127) return "Every day";
  if (mask === 0b0111110) return "Weekdays";
  if (mask === 0b1000001) return "Weekends";
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const active = [];
  for (let i = 0; i < 7; i++) {
    if (mask & (1 << i)) active.push(days[i]);
  }
  return active.join(", ");
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
    const path = url.pathname.replace(/\/+$/, "");
    const segments = path.split("/").filter(Boolean);
    const lastSegment = segments[segments.length - 1];

    // POST /analyze
    if (req.method === "POST" && lastSegment === "analyze") {
      return await analyzeAndGenerateAlarms(userId);
    }

    // GET /patterns
    if (req.method === "GET" && lastSegment === "patterns") {
      return await viewPatterns(userId);
    }

    // PATCH /:id
    if (req.method === "PATCH" && lastSegment !== "smart-alarms") {
      const body = await req.json();
      return await updateAlarm(userId, lastSegment, body);
    }

    // DELETE /:id
    if (req.method === "DELETE" && lastSegment !== "smart-alarms") {
      return await deleteAlarm(userId, lastSegment);
    }

    // GET / — list all alarms
    if (req.method === "GET") {
      return await listAlarms(userId);
    }

    return error("Method not allowed", 405);
  } catch (err) {
    console.error("smart-alarms error:", err);
    return error("Internal server error", 500);
  }
});
