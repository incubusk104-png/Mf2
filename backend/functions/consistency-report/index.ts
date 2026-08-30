/**
 * consistency-report — Supabase Edge Function
 *
 * Generates comprehensive consistency + activity reports from synced data.
 * Reports can be shared publicly via social media platforms (Twitter/X,
 * Instagram, Facebook, LinkedIn, WhatsApp, WeChat, LINE, Telegram).
 *
 * The report aggregates:
 * - Activity data from all connected providers (Strava, Google Fit, Huawei)
 * - Habit completion rates and streaks
 * - Mood trends
 * - Smart alarm adherence
 * - Timing consistency analysis
 *
 * Endpoints:
 *   POST /consistency-report/generate   — Generate a new report
 *   GET  /consistency-report            — List user's reports
 *   GET  /consistency-report/:id        — Get a specific report
 *   GET  /consistency-report/share/:token — Public share endpoint (no auth)
 *   POST /consistency-report/:id/share  — Generate share links for platforms
 *   DELETE /consistency-report/:id      — Delete a report
 *
 * Auth: requires a valid Supabase JWT (except for public share endpoint).
 */

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
};

const SITE_DOMAIN = "https://mindsetframes.online";

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

function generateShareToken(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes)
    .map((b) => b.toString(36).padStart(2, "0"))
    .join("")
    .slice(0, 32);
}

// ── Report Generation ────────────────────────────────────────────────────────

interface GenerateBody {
  report_type: "weekly" | "monthly" | "custom";
  start_date?: string;
  end_date?: string;
}

async function generateReport(userId: string, body: GenerateBody) {
  const supabase = adminClient();
  const now = new Date();

  let periodStart: Date;
  let periodEnd: Date;

  switch (body.report_type) {
    case "weekly":
      periodEnd = now;
      periodStart = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
      break;
    case "monthly":
      periodEnd = now;
      periodStart = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
      break;
    case "custom":
      if (!body.start_date || !body.end_date) {
        return error("start_date and end_date required for custom reports");
      }
      periodStart = new Date(body.start_date);
      periodEnd = new Date(body.end_date);
      break;
    default:
      return error("report_type must be weekly, monthly, or custom");
  }

  const startStr = periodStart.toISOString().slice(0, 10);
  const endStr = periodEnd.toISOString().slice(0, 10);

  // Fetch activity data
  const { data: activities } = await supabase
    .from("activity_data")
    .select("*")
    .eq("user_id", userId)
    .gte("activity_date", startStr)
    .lte("activity_date", endStr)
    .order("started_at", { ascending: true });

  // Fetch habit check-ins
  const { data: checkins } = await supabase
    .from("checkins")
    .select("*")
    .eq("user_id", userId)
    .gte("day", startStr)
    .lte("day", endStr);

  // Fetch habits for total count
  const { data: habits } = await supabase
    .from("habits")
    .select("id, name")
    .eq("user_id", userId);

  // Fetch mood data
  const { data: moods } = await supabase
    .from("mood_log")
    .select("*")
    .eq("user_id", userId)
    .gte("day", startStr)
    .lte("day", endStr);

  const activityList = (activities ?? []) as Array<Record<string, unknown>>;
  const checkinList = (checkins ?? []) as Array<Record<string, unknown>>;
  const habitList = (habits ?? []) as Array<Record<string, unknown>>;
  const moodList = (moods ?? []) as Array<Record<string, unknown>>;

  // ── Compute metrics ──────────────────────────────────────────────────────

  let totalDuration = 0;
  let totalDistance = 0;
  let totalCalories = 0;
  let totalSteps = 0;
  const typeCounts: Record<string, { count: number; duration: number }> = {};
  const dailyActivities: Record<string, number> = {};
  const hourCounts = new Array(24).fill(0);

  for (const a of activityList) {
    totalDuration += (a.duration_seconds as number) || 0;
    totalDistance += (a.distance_meters as number) || 0;
    totalCalories += (a.calories_burned as number) || 0;
    totalSteps += (a.steps as number) || 0;

    const type = a.activity_type as string;
    if (!typeCounts[type]) typeCounts[type] = { count: 0, duration: 0 };
    typeCounts[type].count++;
    typeCounts[type].duration += (a.duration_seconds as number) || 0;

    const date = a.activity_date as string;
    dailyActivities[date] = (dailyActivities[date] || 0) + 1;

    const hour = new Date(a.started_at as string).getHours();
    hourCounts[hour]++;
  }

  // Habit completion rate
  const totalDays = Math.ceil(
    (periodEnd.getTime() - periodStart.getTime()) / (24 * 60 * 60 * 1000)
  );
  const possibleCheckins = habitList.length * totalDays;
  const completionRate =
    possibleCheckins > 0 ? checkinList.length / possibleCheckins : 0;

  // Streaks
  const checkinDays = new Set(checkinList.map((c) => c.day as string));
  let currentStreak = 0;
  let longestStreak = 0;
  let tempStreak = 0;
  const dayMs = 24 * 60 * 60 * 1000;

  for (let d = periodStart.getTime(); d <= periodEnd.getTime(); d += dayMs) {
    const dayStr = new Date(d).toISOString().slice(0, 10);
    if (checkinDays.has(dayStr)) {
      tempStreak++;
      longestStreak = Math.max(longestStreak, tempStreak);
    } else {
      tempStreak = 0;
    }
  }
  // Check if streak continues to today
  for (let d = now.getTime(); d >= periodStart.getTime(); d -= dayMs) {
    const dayStr = new Date(d).toISOString().slice(0, 10);
    if (checkinDays.has(dayStr)) {
      currentStreak++;
    } else {
      break;
    }
  }

  // Most active day
  const dayCounts: Record<string, number> = {};
  for (const a of activityList) {
    const dayName = new Date(a.started_at as string).toLocaleDateString(
      "en-US",
      { weekday: "long" }
    );
    dayCounts[dayName] = (dayCounts[dayName] || 0) + 1;
  }
  const mostActiveDay =
    Object.entries(dayCounts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;

  // Most active time
  const peakHour = hourCounts.indexOf(Math.max(...hourCounts));
  const mostActiveTime =
    peakHour < 6 ? "night" :
    peakHour < 12 ? "morning" :
    peakHour < 17 ? "afternoon" : "evening";

  // Top activities
  const topActivities = Object.entries(typeCounts)
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, 5)
    .map(([type, data]) => ({ type, count: data.count, duration: data.duration }));

  // Mood distribution
  const moodDist: Record<string, number> = {};
  for (const m of moodList) {
    const mode = m.mode as string;
    moodDist[mode] = (moodDist[mode] || 0) + 1;
  }

  // Daily breakdown
  const dailyBreakdown = [];
  for (let d = periodStart.getTime(); d <= periodEnd.getTime(); d += dayMs) {
    const dayStr = new Date(d).toISOString().slice(0, 10);
    const dayActivities = activityList.filter(
      (a) => a.activity_date === dayStr
    );
    const dayCheckins = checkinList.filter((c) => c.day === dayStr);
    const dayMood = moodList.find((m) => m.day === dayStr);

    dailyBreakdown.push({
      date: dayStr,
      activities: dayActivities.length,
      checkins: dayCheckins.length,
      mood: dayMood ? dayMood.mode : null,
      duration: dayActivities.reduce(
        (s, a) => s + ((a.duration_seconds as number) || 0),
        0
      ),
    });
  }

  // Generate share token
  const shareToken = generateShareToken();

  // Save report
  const { data: report, error: dbErr } = await supabase
    .from("consistency_reports")
    .insert({
      user_id: userId,
      report_type: body.report_type,
      period_start: startStr,
      period_end: endStr,
      total_activities: activityList.length,
      total_duration_seconds: totalDuration,
      total_distance_meters: Math.round(totalDistance),
      total_calories: Math.round(totalCalories),
      total_steps: totalSteps,
      habit_completion_rate: Math.round(completionRate * 100) / 100,
      longest_streak: longestStreak,
      current_streak: currentStreak,
      most_active_day: mostActiveDay,
      most_active_time: mostActiveTime,
      top_activities: topActivities,
      mood_distribution: moodDist,
      daily_breakdown: dailyBreakdown,
      share_token: shareToken,
    })
    .select()
    .single();

  if (dbErr) throw dbErr;

  return json({
    report: mapReportRow(report),
    share_url: `${SITE_DOMAIN}/share/${shareToken}`,
    message: `${body.report_type} report generated with ${activityList.length} activities and ${checkinList.length} check-ins.`,
  });
}

/** GET / — List user's reports */
async function listReports(userId: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("consistency_reports")
    .select("*")
    .eq("user_id", userId)
    .order("created_at", { ascending: false })
    .limit(20);
  if (dbErr) throw dbErr;

  return json({
    reports: (data ?? []).map(mapReportRow),
    count: data?.length ?? 0,
  });
}

/** GET /:id — Get a specific report */
async function getReport(userId: string, reportId: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("consistency_reports")
    .select("*")
    .eq("id", reportId)
    .eq("user_id", userId)
    .single();
  if (dbErr || !data) return error("Report not found", 404);

  return json({ report: mapReportRow(data) });
}

/** GET /share/:token — Public share endpoint (no auth required) */
async function getSharedReport(shareToken: string) {
  const supabase = adminClient();
  const { data, error: dbErr } = await supabase
    .from("consistency_reports")
    .select("*")
    .eq("share_token", shareToken)
    .single();

  if (dbErr || !data) return error("Report not found or link expired", 404);

  // Mark as public on first view
  if (!data.is_public) {
    await supabase
      .from("consistency_reports")
      .update({ is_public: true })
      .eq("id", data.id);
  }

  // Increment share count
  await supabase
    .from("consistency_reports")
    .update({ share_count: (data.share_count || 0) + 1 })
    .eq("id", data.id);

  // Return sanitized report (no user_id)
  const report = mapReportRow(data);
  delete (report as Record<string, unknown>).user_id;

  return json({
    report,
    app_name: "Mindset Frames",
    app_url: SITE_DOMAIN,
  });
}

/** POST /:id/share — Generate share links for social media platforms */
async function generateShareLinks(
  userId: string,
  reportId: string,
  body: { platforms: string[] }
) {
  const supabase = adminClient();
  const { data: report, error: dbErr } = await supabase
    .from("consistency_reports")
    .select("*")
    .eq("id", reportId)
    .eq("user_id", userId)
    .single();

  if (dbErr || !report) return error("Report not found", 404);

  const shareUrl = `${SITE_DOMAIN}/share/${report.share_token}`;

  // Build share text
  const stats = [];
  if (report.total_activities > 0) {
    stats.push(`${report.total_activities} activities`);
  }
  if (report.total_duration_seconds > 0) {
    const hours = Math.round(report.total_duration_seconds / 3600);
    if (hours > 0) stats.push(`${hours}h active`);
  }
  if (report.total_steps > 0) {
    stats.push(`${(report.total_steps / 1000).toFixed(1)}k steps`);
  }
  if (report.current_streak > 0) {
    stats.push(`${report.current_streak}-day streak`);
  }
  if (report.habit_completion_rate > 0) {
    stats.push(`${Math.round(report.habit_completion_rate * 100)}% completion`);
  }

  const shareText = stats.length > 0
    ? `My ${report.report_type} consistency report: ${stats.join(" | ")} — tracked with Mindset Frames`
    : `Check out my ${report.report_type} consistency report from Mindset Frames!`;

  const hashtags = "MindsetFrames,ConsistencyMatters,HabitTracking,Wellness";
  const encodedText = encodeURIComponent(shareText);
  const encodedUrl = encodeURIComponent(shareUrl);
  const encodedHashtags = encodeURIComponent(hashtags);

  // Generate platform-specific share URLs
  const shareLinks: Record<string, string> = {};
  const platforms = body.platforms || [
    "twitter",
    "facebook",
    "linkedin",
    "whatsapp",
    "telegram",
    "wechat",
    "line",
    "copy",
  ];

  for (const platform of platforms) {
    switch (platform) {
      case "twitter":
      case "x":
        shareLinks[platform] = `https://twitter.com/intent/tweet?text=${encodedText}&url=${encodedUrl}&hashtags=${encodedHashtags}`;
        break;
      case "facebook":
        shareLinks[platform] = `https://www.facebook.com/sharer/sharer.php?u=${encodedUrl}&quote=${encodedText}`;
        break;
      case "linkedin":
        shareLinks[platform] = `https://www.linkedin.com/sharing/share-offsite/?url=${encodedUrl}`;
        break;
      case "whatsapp":
        shareLinks[platform] = `https://api.whatsapp.com/send?text=${encodedText}%20${encodedUrl}`;
        break;
      case "telegram":
        shareLinks[platform] = `https://t.me/share/url?url=${encodedUrl}&text=${encodedText}`;
        break;
      case "wechat":
        // WeChat requires QR code scanning — provide the URL
        shareLinks[platform] = shareUrl;
        break;
      case "line":
        shareLinks[platform] = `https://social-plugins.line.me/lineit/share?url=${encodedUrl}&text=${encodedText}`;
        break;
      case "reddit":
        shareLinks[platform] = `https://www.reddit.com/submit?url=${encodedUrl}&title=${encodedText}`;
        break;
      case "pinterest":
        shareLinks[platform] = `https://pinterest.com/pin/create/button/?url=${encodedUrl}&description=${encodedText}`;
        break;
      case "email":
        shareLinks[platform] = `mailto:?subject=${encodeURIComponent("My Mindset Frames Report")}&body=${encodedText}%0A%0A${encodedUrl}`;
        break;
      case "copy":
        shareLinks[platform] = shareUrl;
        break;
    }
  }

  // Record which platforms were shared
  const existingPlatforms = (report.shared_platforms as string[]) || [];
  const allPlatforms = [
    ...new Set([...existingPlatforms, ...platforms]),
  ];

  await supabase
    .from("consistency_reports")
    .update({
      shared_platforms: allPlatforms,
      is_public: true,
    })
    .eq("id", reportId);

  return json({
    share_url: shareUrl,
    share_text: shareText,
    share_links: shareLinks,
    platforms: Object.keys(shareLinks),
  });
}

/** DELETE /:id — Delete a report */
async function deleteReport(userId: string, reportId: string) {
  const supabase = adminClient();
  const { error: dbErr } = await supabase
    .from("consistency_reports")
    .delete()
    .eq("id", reportId)
    .eq("user_id", userId);
  if (dbErr) throw dbErr;

  return json({ deleted: true, report_id: reportId });
}

// ── Utility ──────────────────────────────────────────────────────────────────

function mapReportRow(row: Record<string, unknown>) {
  return {
    id: row.id,
    user_id: row.user_id,
    report_type: row.report_type,
    period_start: row.period_start,
    period_end: row.period_end,
    total_activities: row.total_activities,
    total_duration_seconds: row.total_duration_seconds,
    total_duration_formatted: formatDuration(
      row.total_duration_seconds as number
    ),
    total_distance_meters: row.total_distance_meters,
    total_distance_km: Math.round(((row.total_distance_meters as number) || 0) / 100) / 10,
    total_calories: row.total_calories,
    total_steps: row.total_steps,
    habit_completion_rate: row.habit_completion_rate,
    habit_completion_percent: Math.round(
      ((row.habit_completion_rate as number) || 0) * 100
    ),
    longest_streak: row.longest_streak,
    current_streak: row.current_streak,
    most_active_day: row.most_active_day,
    most_active_time: row.most_active_time,
    top_activities: row.top_activities,
    mood_distribution: row.mood_distribution,
    daily_breakdown: row.daily_breakdown,
    share_token: row.share_token,
    share_url: row.share_token
      ? `${SITE_DOMAIN}/share/${row.share_token}`
      : null,
    is_public: row.is_public,
    shared_platforms: row.shared_platforms,
    share_count: row.share_count,
    created_at: row.created_at,
  };
}

function formatDuration(seconds: number): string {
  if (!seconds || seconds <= 0) return "0m";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

// ── Router ───────────────────────────────────────────────────────────────────

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "");
    const segments = path.split("/").filter(Boolean);
    const lastSegment = segments[segments.length - 1];
    const secondLast = segments.length >= 2 ? segments[segments.length - 2] : "";

    // Public share endpoint — no auth required
    if (req.method === "GET" && secondLast === "share" && lastSegment) {
      return await getSharedReport(lastSegment);
    }

    // All other endpoints require auth
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return error("Missing Authorization header", 401);

    const userId = await getUserId(authHeader);
    if (!userId) return error("Invalid or expired token", 401);

    // POST /generate
    if (req.method === "POST" && lastSegment === "generate") {
      const body = await req.json();
      return await generateReport(userId, body);
    }

    // POST /:id/share
    if (req.method === "POST" && lastSegment === "share" && secondLast) {
      const body = await req.json();
      return await generateShareLinks(userId, secondLast, body);
    }

    // DELETE /:id
    if (req.method === "DELETE" && lastSegment !== "consistency-report") {
      return await deleteReport(userId, lastSegment);
    }

    // GET /:id (specific report)
    if (
      req.method === "GET" &&
      lastSegment !== "consistency-report" &&
      lastSegment.length > 10
    ) {
      return await getReport(userId, lastSegment);
    }

    // GET / — list reports
    if (req.method === "GET") {
      return await listReports(userId);
    }

    return error("Method not allowed", 405);
  } catch (err) {
    console.error("consistency-report error:", err);
    return error("Internal server error", 500);
  }
});
