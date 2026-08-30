/**
 * activity-sync — Supabase Edge Function
 *
 * Manages activity provider connections and syncs activity data from
 * Strava, Google Fit, and Huawei Health Kit into the Mindset Frames
 * activity_data table.
 *
 * SECURITY: The user MUST authenticate with each provider's OAuth flow
 * BEFORE any data can be synced. This function verifies the connection
 * exists and tokens are valid before pulling any data.
 *
 * Endpoints:
 *   POST /activity-sync/connect     — Save OAuth tokens after user authenticates
 *   POST /activity-sync/disconnect  — Revoke connection and clear tokens
 *   GET  /activity-sync/connections — List all provider connections for user
 *   POST /activity-sync/sync        — Pull latest activities from a provider
 *   GET  /activity-sync/activities  — Query synced activity data
 *   GET  /activity-sync/summary     — Aggregated activity summary for a period
 *
 * Auth: requires a valid Supabase JWT in the Authorization header.
 */

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ── Constants ────────────────────────────────────────────────────────────────

const STRAVA_API = "https://www.strava.com/api/v3";
const GOOGLE_FIT_API = "https://www.googleapis.com/fitness/v1/users/me";
const HUAWEI_HEALTH_API = "https://health-api.cloud.huawei.com/healthkit/v1";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
};

// ── Types ────────────────────────────────────────────────────────────────────

interface ConnectBody {
  provider: "strava" | "google_fit" | "huawei_health";
  accessToken: string;
  refreshToken: string;
  expiresAt?: number;
  providerUserId?: string;
  providerDisplayName?: string;
  providerAvatarUrl?: string;
}

interface SyncBody {
  provider: "strava" | "google_fit" | "huawei_health";
  /** How many days back to sync (default: 7, max: 90) */
  daysBack?: number;
}

interface NormalizedActivity {
  provider: string;
  provider_activity_id: string;
  activity_type: string;
  activity_name: string;
  started_at: string;
  ended_at: string | null;
  duration_seconds: number | null;
  distance_meters: number | null;
  calories_burned: number | null;
  heart_rate_avg: number | null;
  heart_rate_max: number | null;
  steps: number | null;
  elevation_gain_meters: number | null;
  raw_data: Record<string, unknown>;
  activity_date: string;
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

function dateStr(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// ── Provider: Strava ─────────────────────────────────────────────────────────

function normalizeStravaType(type: string): string {
  const map: Record<string, string> = {
    Run: "run", Ride: "cycle", Swim: "swim", Walk: "walk",
    Hike: "hike", Yoga: "yoga", WeightTraining: "workout",
    Workout: "workout", CrossFit: "workout", Elliptical: "cardio",
    StairStepper: "cardio", Rowing: "rowing", Kayaking: "water_sport",
    Surfing: "water_sport", Skateboard: "other", InlineSkate: "other",
    IceSkate: "other", RockClimbing: "climbing", Snowboard: "snow_sport",
    AlpineSki: "snow_sport", NordicSki: "snow_sport", Canoeing: "water_sport",
  };
  return map[type] || "other";
}

async function fetchStravaActivities(
  accessToken: string,
  after: number
): Promise<NormalizedActivity[]> {
  const url = `${STRAVA_API}/athlete/activities?after=${after}&per_page=100`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) {
    throw new Error(`Strava API error: ${res.status}`);
  }
  const activities = await res.json();
  return (activities as Array<Record<string, unknown>>).map((a) => {
    const startDate = new Date(a.start_date as string);
    const elapsed = (a.elapsed_time as number) || 0;
    return {
      provider: "strava",
      provider_activity_id: String(a.id),
      activity_type: normalizeStravaType(a.type as string),
      activity_name: (a.name as string) || `${a.type}`,
      started_at: startDate.toISOString(),
      ended_at: elapsed > 0
        ? new Date(startDate.getTime() + elapsed * 1000).toISOString()
        : null,
      duration_seconds: (a.moving_time as number) || null,
      distance_meters: (a.distance as number) || null,
      calories_burned: (a.calories as number) || null,
      heart_rate_avg: (a.average_heartrate as number) || null,
      heart_rate_max: (a.max_heartrate as number) || null,
      steps: null,
      elevation_gain_meters: (a.total_elevation_gain as number) || null,
      raw_data: a,
      activity_date: dateStr(startDate),
    };
  });
}

// ── Provider: Google Fit ─────────────────────────────────────────────────────

function normalizeGoogleFitType(typeId: number): string {
  const map: Record<number, string> = {
    7: "walk", 8: "run", 1: "cycle", 82: "swim", 100: "yoga",
    80: "workout", 13: "hike", 74: "rowing", 12: "cardio",
    72: "sleep", 79: "other",
  };
  return map[typeId] || "other";
}

async function fetchGoogleFitActivities(
  accessToken: string,
  startTimeMs: number,
  endTimeMs: number
): Promise<NormalizedActivity[]> {
  const url = `${GOOGLE_FIT_API}/sessions?startTime=${new Date(
    startTimeMs
  ).toISOString()}&endTime=${new Date(endTimeMs).toISOString()}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) {
    throw new Error(`Google Fit API error: ${res.status}`);
  }
  const data = await res.json();
  const sessions = (data.session || []) as Array<Record<string, unknown>>;
  return sessions.map((s) => {
    const startMs = Number(s.startTimeMillis);
    const endMs = Number(s.endTimeMillis);
    const startDate = new Date(startMs);
    return {
      provider: "google_fit",
      provider_activity_id: s.id as string,
      activity_type: normalizeGoogleFitType(s.activityType as number),
      activity_name: (s.name as string) || (s.description as string) || "Activity",
      started_at: startDate.toISOString(),
      ended_at: new Date(endMs).toISOString(),
      duration_seconds: Math.round((endMs - startMs) / 1000),
      distance_meters: null,
      calories_burned: null,
      heart_rate_avg: null,
      heart_rate_max: null,
      steps: null,
      elevation_gain_meters: null,
      raw_data: s,
      activity_date: dateStr(startDate),
    };
  });
}

// ── Provider: Huawei Health ──────────────────────────────────────────────────

function normalizeHuaweiType(type: number): string {
  const map: Record<number, string> = {
    1: "run", 2: "walk", 3: "cycle", 4: "workout",
    5: "swim", 6: "yoga", 7: "hike", 16: "sleep",
    17: "cardio", 25: "rowing",
  };
  return map[type] || "other";
}

async function fetchHuaweiHealthActivities(
  accessToken: string,
  startTimeMs: number,
  endTimeMs: number
): Promise<NormalizedActivity[]> {
  // Huawei Health Kit REST API — activity records
  const res = await fetch(`${HUAWEI_HEALTH_API}/activityRecord/read`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      startTime: startTimeMs,
      endTime: endTimeMs,
      timeZone: "+00:00",
    }),
  });
  if (!res.ok) {
    throw new Error(`Huawei Health API error: ${res.status}`);
  }
  const data = await res.json();
  const records = (data.activityRecord || []) as Array<Record<string, unknown>>;
  return records.map((r) => {
    const startMs = Number(r.startTime);
    const endMs = Number(r.endTime);
    const startDate = new Date(startMs);
    return {
      provider: "huawei_health",
      provider_activity_id: (r.activityRecordId as string) || String(startMs),
      activity_type: normalizeHuaweiType(r.activityType as number),
      activity_name: (r.name as string) || (r.description as string) || "Activity",
      started_at: startDate.toISOString(),
      ended_at: new Date(endMs).toISOString(),
      duration_seconds: Math.round((endMs - startMs) / 1000),
      distance_meters: (r.distance as number) || null,
      calories_burned: (r.calories as number) || null,
      heart_rate_avg: null,
      heart_rate_max: null,
      steps: (r.steps as number) || null,
      elevation_gain_meters: null,
      raw_data: r,
      activity_date: dateStr(startDate),
    };
  });
}

// ── Route handlers ───────────────────────────────────────────────────────────

/** POST /connect — Save OAuth tokens after user authenticates with provider */
async function connectProvider(userId: string, body: ConnectBody) {
  const validProviders = ["strava", "google_fit", "huawei_health"];
  if (!validProviders.includes(body.provider)) {
    return error("Invalid provider. Must be: strava, google_fit, or huawei_health");
  }
  if (!body.accessToken?.trim()) {
    return error("accessToken is required — user must authenticate first");
  }

  const supabase = adminClient();

  // Upsert the connection
  const { data, error: dbErr } = await supabase
    .from("activity_connections")
    .upsert(
      {
        user_id: userId,
        provider: body.provider,
        access_token: body.accessToken.trim(),
        refresh_token: body.refreshToken?.trim() || null,
        token_expires_at: body.expiresAt || null,
        provider_user_id: body.providerUserId || null,
        provider_display_name: body.providerDisplayName || null,
        provider_avatar_url: body.providerAvatarUrl || null,
        is_connected: true,
        connected_at: new Date().toISOString(),
        disconnected_at: null,
        sync_error: null,
      },
      { onConflict: "user_id,provider" }
    )
    .select()
    .single();

  if (dbErr) throw dbErr;

  return json({
    connection: {
      id: data.id,
      provider: data.provider,
      is_connected: data.is_connected,
      provider_display_name: data.provider_display_name,
      connected_at: data.connected_at,
    },
    message: `Successfully connected ${body.provider}. You can now sync activities.`,
  });
}

/** POST /disconnect — Revoke connection and clear tokens */
async function disconnectProvider(userId: string, provider: string) {
  const supabase = adminClient();

  const { error: dbErr } = await supabase
    .from("activity_connections")
    .update({
      is_connected: false,
      access_token: null,
      refresh_token: null,
      token_expires_at: null,
      disconnected_at: new Date().toISOString(),
    })
    .eq("user_id", userId)
    .eq("provider", provider);

  if (dbErr) throw dbErr;

  return json({
    disconnected: true,
    provider,
    message: `Disconnected ${provider}. Existing synced data is preserved.`,
  });
}

/** GET /connections — List all provider connections for user */
async function listConnections(userId: string) {
  const supabase = adminClient();

  const { data, error: dbErr } = await supabase
    .from("activity_connections")
    .select(
      "id, provider, is_connected, provider_display_name, provider_avatar_url, last_sync_at, sync_error, connected_at"
    )
    .eq("user_id", userId);

  if (dbErr) throw dbErr;

  // Return all three providers with connection status
  const providers = ["strava", "google_fit", "huawei_health"];
  const connections = providers.map((p) => {
    const existing = (data ?? []).find(
      (c: Record<string, unknown>) => c.provider === p
    );
    return existing || { provider: p, is_connected: false };
  });

  return json({ connections });
}

/** POST /sync — Pull latest activities from a connected provider */
async function syncActivities(userId: string, body: SyncBody) {
  const supabase = adminClient();

  // Verify connection exists and is active
  const { data: conn, error: connErr } = await supabase
    .from("activity_connections")
    .select("*")
    .eq("user_id", userId)
    .eq("provider", body.provider)
    .single();

  if (connErr || !conn) {
    return error(
      `No ${body.provider} connection found. Please connect your account first.`,
      404
    );
  }
  if (!conn.is_connected) {
    return error(
      `Your ${body.provider} account is disconnected. Please re-authenticate.`,
      403
    );
  }
  if (!conn.access_token) {
    return error(
      `No valid access token for ${body.provider}. Please re-authenticate your account.`,
      401
    );
  }

  // Check token expiry
  if (conn.token_expires_at && conn.token_expires_at * 1000 < Date.now()) {
    return error(
      `Your ${body.provider} token has expired. Please refresh your authentication.`,
      401
    );
  }

  const daysBack = Math.min(Math.max(body.daysBack ?? 7, 1), 90);
  const now = Date.now();
  const startTime = now - daysBack * 24 * 60 * 60 * 1000;

  let activities: NormalizedActivity[];
  try {
    switch (body.provider) {
      case "strava":
        activities = await fetchStravaActivities(
          conn.access_token,
          Math.floor(startTime / 1000)
        );
        break;
      case "google_fit":
        activities = await fetchGoogleFitActivities(
          conn.access_token,
          startTime,
          now
        );
        break;
      case "huawei_health":
        activities = await fetchHuaweiHealthActivities(
          conn.access_token,
          startTime,
          now
        );
        break;
      default:
        return error("Unsupported provider");
    }
  } catch (e) {
    const errMsg = e instanceof Error ? e.message : "Unknown sync error";
    // Record the error
    await supabase
      .from("activity_connections")
      .update({ sync_error: errMsg })
      .eq("id", conn.id);
    return error(`Sync failed: ${errMsg}`, 502);
  }

  // Upsert activities (dedup by provider + provider_activity_id)
  let synced = 0;
  for (const activity of activities) {
    const { error: insertErr } = await supabase
      .from("activity_data")
      .upsert(
        {
          user_id: userId,
          connection_id: conn.id,
          ...activity,
        },
        { onConflict: "user_id,provider,provider_activity_id" }
      );
    if (!insertErr) synced++;
  }

  // Update last sync time
  await supabase
    .from("activity_connections")
    .update({
      last_sync_at: new Date().toISOString(),
      sync_error: null,
    })
    .eq("id", conn.id);

  return json({
    synced,
    total_fetched: activities.length,
    provider: body.provider,
    period_days: daysBack,
    message: `Synced ${synced} activities from ${body.provider}`,
  });
}

/** GET /activities — Query synced activity data */
async function listActivities(userId: string, url: URL) {
  const supabase = adminClient();
  const provider = url.searchParams.get("provider");
  const activityType = url.searchParams.get("type");
  const startDate = url.searchParams.get("start");
  const endDate = url.searchParams.get("end");
  const limit = Math.min(Number(url.searchParams.get("limit") ?? 50), 200);

  let query = supabase
    .from("activity_data")
    .select("*")
    .eq("user_id", userId)
    .order("started_at", { ascending: false })
    .limit(limit);

  if (provider) query = query.eq("provider", provider);
  if (activityType) query = query.eq("activity_type", activityType);
  if (startDate) query = query.gte("activity_date", startDate);
  if (endDate) query = query.lte("activity_date", endDate);

  const { data, error: dbErr } = await query;
  if (dbErr) throw dbErr;

  return json({
    activities: (data ?? []).map((a: Record<string, unknown>) => ({
      ...a,
      raw_data: undefined, // Don't send raw data to client
    })),
    count: data?.length ?? 0,
  });
}

/** GET /summary — Aggregated activity summary for a period */
async function getActivitySummary(userId: string, url: URL) {
  const supabase = adminClient();
  const days = Number(url.searchParams.get("days") ?? 7);
  const startDate = dateStr(
    new Date(Date.now() - days * 24 * 60 * 60 * 1000)
  );

  const { data, error: dbErr } = await supabase
    .from("activity_data")
    .select("*")
    .eq("user_id", userId)
    .gte("activity_date", startDate)
    .order("started_at", { ascending: false });

  if (dbErr) throw dbErr;
  const activities = data ?? [];

  // Compute aggregates
  let totalDuration = 0;
  let totalDistance = 0;
  let totalCalories = 0;
  let totalSteps = 0;
  const typeCounts: Record<string, number> = {};
  const dayTimes: Record<string, number[]> = {};
  const hourCounts: number[] = new Array(24).fill(0);

  for (const a of activities as Array<Record<string, unknown>>) {
    totalDuration += (a.duration_seconds as number) || 0;
    totalDistance += (a.distance_meters as number) || 0;
    totalCalories += (a.calories_burned as number) || 0;
    totalSteps += (a.steps as number) || 0;

    const type = a.activity_type as string;
    typeCounts[type] = (typeCounts[type] || 0) + 1;

    const startedAt = new Date(a.started_at as string);
    const dayName = startedAt.toLocaleDateString("en-US", { weekday: "long" });
    if (!dayTimes[dayName]) dayTimes[dayName] = [];
    dayTimes[dayName].push(startedAt.getHours() * 60 + startedAt.getMinutes());

    hourCounts[startedAt.getHours()]++;
  }

  // Find most active day and time
  const mostActiveDay = Object.entries(dayTimes).sort(
    (a, b) => b[1].length - a[1].length
  )[0]?.[0] ?? null;

  const peakHour = hourCounts.indexOf(Math.max(...hourCounts));
  const mostActiveTime =
    peakHour < 6 ? "night" :
    peakHour < 12 ? "morning" :
    peakHour < 17 ? "afternoon" : "evening";

  // Top activities by count
  const topActivities = Object.entries(typeCounts)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([type, count]) => ({ type, count }));

  // Consistency: unique active days / total days
  const activeDays = new Set(
    (activities as Array<Record<string, unknown>>).map(
      (a) => a.activity_date as string
    )
  );
  const consistencyRate = days > 0 ? activeDays.size / days : 0;

  // Per-activity-type time distribution (for detecting inconsistency)
  const typeTimings: Record<string, { times: number[]; variance: number }> = {};
  for (const a of activities as Array<Record<string, unknown>>) {
    const type = a.activity_type as string;
    const time = new Date(a.started_at as string);
    const mins = time.getHours() * 60 + time.getMinutes();
    if (!typeTimings[type]) typeTimings[type] = { times: [], variance: 0 };
    typeTimings[type].times.push(mins);
  }

  // Compute variance for each type
  for (const key of Object.keys(typeTimings)) {
    const times = typeTimings[key].times;
    if (times.length > 1) {
      const mean = times.reduce((s, t) => s + t, 0) / times.length;
      const variance = times.reduce((s, t) => s + (t - mean) ** 2, 0) / times.length;
      typeTimings[key].variance = Math.round(Math.sqrt(variance));
    }
  }

  return json({
    period_days: days,
    total_activities: activities.length,
    total_duration_seconds: totalDuration,
    total_distance_meters: Math.round(totalDistance),
    total_calories: Math.round(totalCalories),
    total_steps: totalSteps,
    consistency_rate: Math.round(consistencyRate * 100) / 100,
    active_days: activeDays.size,
    most_active_day: mostActiveDay,
    most_active_time: mostActiveTime,
    top_activities: topActivities,
    timing_analysis: Object.entries(typeTimings).map(([type, data]) => ({
      activity_type: type,
      count: data.times.length,
      avg_time_minutes: Math.round(
        data.times.reduce((s, t) => s + t, 0) / data.times.length
      ),
      variance_minutes: data.variance,
      is_inconsistent: data.variance > 60,
    })),
  });
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

    // POST /connect
    if (req.method === "POST" && lastSegment === "connect") {
      const body = await req.json();
      return await connectProvider(userId, body);
    }

    // POST /disconnect
    if (req.method === "POST" && lastSegment === "disconnect") {
      const body = await req.json();
      return await disconnectProvider(userId, body.provider);
    }

    // GET /connections
    if (req.method === "GET" && lastSegment === "connections") {
      return await listConnections(userId);
    }

    // POST /sync
    if (req.method === "POST" && lastSegment === "sync") {
      const body = await req.json();
      return await syncActivities(userId, body);
    }

    // GET /activities
    if (req.method === "GET" && lastSegment === "activities") {
      return await listActivities(userId, url);
    }

    // GET /summary
    if (req.method === "GET" && lastSegment === "summary") {
      return await getActivitySummary(userId, url);
    }

    return error("Not found", 404);
  } catch (err) {
    console.error("activity-sync error:", err);
    return error("Internal server error", 500);
  }
});
