/**
 * Activity integration types shared across the web dashboard.
 */

export type Provider = "strava" | "google_fit" | "huawei_health";

export interface ProviderConnection {
  id?: string;
  provider: Provider;
  is_connected: boolean;
  provider_display_name?: string | null;
  provider_avatar_url?: string | null;
  last_sync_at?: string | null;
  sync_error?: string | null;
  connected_at?: string | null;
}

export interface Activity {
  id: string;
  provider: Provider;
  activity_type: string;
  activity_name: string;
  started_at: string;
  ended_at: string | null;
  duration_seconds: number | null;
  distance_meters: number | null;
  calories_burned: number | null;
  heart_rate_avg: number | null;
  steps: number | null;
  activity_date: string;
}

export interface ActivitySummary {
  period_days: number;
  total_activities: number;
  total_duration_seconds: number;
  total_distance_meters: number;
  total_calories: number;
  total_steps: number;
  consistency_rate: number;
  active_days: number;
  most_active_day: string | null;
  most_active_time: string | null;
  top_activities: Array<{ type: string; count: number }>;
  timing_analysis: Array<{
    activity_type: string;
    count: number;
    avg_time_minutes: number;
    variance_minutes: number;
    is_inconsistent: boolean;
  }>;
}

export interface SmartAlarm {
  id: string;
  alarm_type: string;
  suggested_minutes: number;
  suggested_time: string;
  active_minutes: number | null;
  active_time: string | null;
  days_of_week: number;
  days_label: string;
  confidence_score: number;
  based_on_activities: number;
  variance_minutes: number | null;
  is_enabled: boolean;
  is_user_overridden: boolean;
  message: string | null;
}

export interface ConsistencyReport {
  id: string;
  report_type: "weekly" | "monthly" | "custom";
  period_start: string;
  period_end: string;
  total_activities: number;
  total_duration_seconds: number;
  total_duration_formatted: string;
  total_distance_km: number;
  total_calories: number;
  total_steps: number;
  habit_completion_rate: number;
  habit_completion_percent: number;
  longest_streak: number;
  current_streak: number;
  most_active_day: string | null;
  most_active_time: string | null;
  top_activities: Array<{ type: string; count: number; duration: number }>;
  mood_distribution: Record<string, number>;
  daily_breakdown: Array<{
    date: string;
    activities: number;
    checkins: number;
    mood: string | null;
    duration: number;
  }>;
  share_token: string | null;
  share_url: string | null;
  is_public: boolean;
  share_count: number;
  created_at: string;
}

export interface ShareLinks {
  share_url: string;
  share_text: string;
  share_links: Record<string, string>;
  platforms: string[];
}

// Provider display metadata
export const PROVIDER_META: Record<
  Provider,
  { label: string; color: string; icon: string; oauthUrl: string }
> = {
  strava: {
    label: "Strava",
    color: "#FC4C02",
    icon: "strava",
    oauthUrl: "https://www.strava.com/oauth/authorize",
  },
  google_fit: {
    label: "Google Fit",
    color: "#4285F4",
    icon: "google-fit",
    oauthUrl: "https://accounts.google.com/o/oauth2/v2/auth",
  },
  huawei_health: {
    label: "Huawei Health",
    color: "#CF0A2C",
    icon: "huawei-health",
    oauthUrl: "https://oauth-login.cloud.huawei.com/oauth2/v3/authorize",
  },
};

export const ACTIVITY_TYPE_LABELS: Record<string, string> = {
  run: "Running",
  walk: "Walking",
  cycle: "Cycling",
  swim: "Swimming",
  yoga: "Yoga",
  workout: "Workout",
  hike: "Hiking",
  sleep: "Sleep",
  cardio: "Cardio",
  rowing: "Rowing",
  climbing: "Climbing",
  snow_sport: "Snow Sport",
  water_sport: "Water Sport",
  other: "Other",
};

export function formatDuration(seconds: number): string {
  if (!seconds || seconds <= 0) return "0m";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export function formatDistance(meters: number): string {
  if (!meters || meters <= 0) return "0 km";
  return `${(meters / 1000).toFixed(1)} km`;
}

export function formatMinutesToTime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const period = h >= 12 ? "PM" : "AM";
  const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
  return `${h12}:${String(m).padStart(2, "0")} ${period}`;
}
