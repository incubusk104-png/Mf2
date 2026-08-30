import { useState } from "react";
import {
  Activity as ActivityIcon,
  Bell,
  BarChart3,
  Link2,
  Link2Off,
  RefreshCcw,
  Share2,
  Clock,
  Flame,
  Footprints,
  Heart,
  TrendingUp,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Download,
  ExternalLink,
  Copy,
  Twitter,
  Facebook,
  Linkedin,
  MessageCircle,
  Send,
  ArrowLeft,
} from "lucide-react";

import { Navbar } from "@/components/marketing/Navbar";
import { SiteFooter } from "@/components/marketing/SiteFooter";
import { Reveal } from "@/components/marketing/Reveal";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Switch } from "@/components/ui/switch";
import { Separator } from "@/components/ui/separator";
import { toast } from "sonner";

import {
  type Provider,
  type ProviderConnection,
  type Activity,
  type ActivitySummary,
  type SmartAlarm,
  type ConsistencyReport,
  PROVIDER_META,
  ACTIVITY_TYPE_LABELS,
  formatDuration,
  formatDistance,
  formatMinutesToTime,
} from "@/lib/activityTypes";

// ── Demo data for the marketing preview ──────────────────────────────────────

const DEMO_CONNECTIONS: ProviderConnection[] = [
  {
    provider: "strava",
    is_connected: true,
    provider_display_name: "Alex Runner",
    last_sync_at: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    connected_at: new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString(),
  },
  { provider: "google_fit", is_connected: false },
  { provider: "huawei_health", is_connected: false },
];

const DEMO_SUMMARY: ActivitySummary = {
  period_days: 7,
  total_activities: 12,
  total_duration_seconds: 18720,
  total_distance_meters: 42500,
  total_calories: 2840,
  total_steps: 68200,
  consistency_rate: 0.86,
  active_days: 6,
  most_active_day: "Wednesday",
  most_active_time: "morning",
  top_activities: [
    { type: "run", count: 5 },
    { type: "yoga", count: 3 },
    { type: "cycle", count: 2 },
    { type: "workout", count: 2 },
  ],
  timing_analysis: [
    { activity_type: "run", count: 5, avg_time_minutes: 420, variance_minutes: 45, is_inconsistent: false },
    { activity_type: "yoga", count: 3, avg_time_minutes: 1080, variance_minutes: 120, is_inconsistent: true },
    { activity_type: "cycle", count: 2, avg_time_minutes: 600, variance_minutes: 30, is_inconsistent: false },
  ],
};

const DEMO_ACTIVITIES: Activity[] = [
  { id: "1", provider: "strava", activity_type: "run", activity_name: "Morning 5K", started_at: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(), ended_at: null, duration_seconds: 1680, distance_meters: 5120, calories_burned: 420, heart_rate_avg: 155, steps: 6200, activity_date: new Date().toISOString().slice(0, 10) },
  { id: "2", provider: "strava", activity_type: "yoga", activity_name: "Evening Flow", started_at: new Date(Date.now() - 26 * 60 * 60 * 1000).toISOString(), ended_at: null, duration_seconds: 2400, distance_meters: null, calories_burned: 180, heart_rate_avg: 95, steps: null, activity_date: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10) },
  { id: "3", provider: "strava", activity_type: "cycle", activity_name: "Commute", started_at: new Date(Date.now() - 50 * 60 * 60 * 1000).toISOString(), ended_at: null, duration_seconds: 1200, distance_meters: 8300, calories_burned: 310, heart_rate_avg: 140, steps: null, activity_date: new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString().slice(0, 10) },
];

const DEMO_ALARMS: SmartAlarm[] = [
  { id: "a1", alarm_type: "activity_nudge", suggested_minutes: 405, suggested_time: "6:45 AM", active_minutes: 405, active_time: "6:45 AM", days_of_week: 0b0111110, days_label: "Weekdays", confidence_score: 0.85, based_on_activities: 12, variance_minutes: 45, is_enabled: true, is_user_overridden: false, message: "Time for your run! Lace up at 6:45 AM" },
  { id: "a2", alarm_type: "activity_nudge", suggested_minutes: 1065, suggested_time: "5:45 PM", active_minutes: 1065, active_time: "5:45 PM", days_of_week: 127, days_label: "Every day", confidence_score: 0.62, based_on_activities: 8, variance_minutes: 120, is_enabled: true, is_user_overridden: false, message: "Yoga time. Unroll your mat at 5:45 PM (Your timing varies — this alarm adapts to your pattern.)" },
  { id: "a3", alarm_type: "consistency_check", suggested_minutes: 390, suggested_time: "6:30 AM", active_minutes: 390, active_time: "6:30 AM", days_of_week: 127, days_label: "Every day", confidence_score: 0.48, based_on_activities: 8, variance_minutes: 120, is_enabled: false, is_user_overridden: false, message: "Heads up! Your yoga timing varies. Don't let the streak slip!" },
];

const DEMO_REPORT: ConsistencyReport = {
  id: "r1",
  report_type: "weekly",
  period_start: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
  period_end: new Date().toISOString().slice(0, 10),
  total_activities: 12,
  total_duration_seconds: 18720,
  total_duration_formatted: "5h 12m",
  total_distance_km: 42.5,
  total_calories: 2840,
  total_steps: 68200,
  habit_completion_rate: 0.86,
  habit_completion_percent: 86,
  longest_streak: 6,
  current_streak: 4,
  most_active_day: "Wednesday",
  most_active_time: "morning",
  top_activities: [
    { type: "run", count: 5, duration: 8400 },
    { type: "yoga", count: 3, duration: 7200 },
  ],
  mood_distribution: { calm: 3, focused: 2, motivated: 1, overwhelmed: 1 },
  daily_breakdown: [],
  share_token: "demo-token",
  share_url: "https://mindsetframes.online/share/demo-token",
  is_public: false,
  share_count: 0,
  created_at: new Date().toISOString(),
};

// ── Shared sub-components ────────────────────────────────────────────────────

function ProviderCard({ conn, onConnect, onDisconnect, onSync }: {
  conn: ProviderConnection;
  onConnect: (p: Provider) => void;
  onDisconnect: (p: Provider) => void;
  onSync: (p: Provider) => void;
}) {
  const meta = PROVIDER_META[conn.provider];
  return (
    <Card className="border-border/60">
      <CardContent className="flex items-center gap-4 p-5">
        <div
          className="flex h-12 w-12 items-center justify-center rounded-xl text-white font-bold text-sm"
          style={{ backgroundColor: meta.color }}
        >
          {meta.label.slice(0, 2).toUpperCase()}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-display font-semibold">{meta.label}</span>
            {conn.is_connected ? (
              <Badge variant="outline" className="border-emerald-500/40 text-emerald-400 text-[10px]">
                Connected
              </Badge>
            ) : (
              <Badge variant="outline" className="border-muted-foreground/30 text-muted-foreground text-[10px]">
                Not connected
              </Badge>
            )}
          </div>
          {conn.is_connected && conn.provider_display_name && (
            <p className="text-xs text-muted-foreground mt-0.5 truncate">
              {conn.provider_display_name}
              {conn.last_sync_at && (
                <> &middot; Synced {new Date(conn.last_sync_at).toLocaleDateString()}</>
              )}
            </p>
          )}
          {!conn.is_connected && (
            <p className="text-xs text-muted-foreground mt-0.5">
              Sign in to {meta.label} to sync activities
            </p>
          )}
        </div>
        <div className="flex gap-2">
          {conn.is_connected ? (
            <>
              <Button size="sm" variant="ghost" onClick={() => onSync(conn.provider)} className="h-8 gap-1.5 text-xs">
                <RefreshCcw className="h-3.5 w-3.5" /> Sync
              </Button>
              <Button size="sm" variant="ghost" onClick={() => onDisconnect(conn.provider)} className="h-8 text-xs text-destructive hover:text-destructive">
                <Link2Off className="h-3.5 w-3.5" />
              </Button>
            </>
          ) : (
            <Button size="sm" onClick={() => onConnect(conn.provider)} className="h-8 gap-1.5 text-xs">
              <Link2 className="h-3.5 w-3.5" /> Connect
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function StatCard({ icon: Icon, label, value, sub }: {
  icon: typeof ActivityIcon;
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <Card className="border-border/60">
      <CardContent className="p-4">
        <div className="flex items-center gap-3">
          <span className="inline-flex h-9 w-9 items-center justify-center rounded-lg bg-primary/12 text-primary">
            <Icon className="h-4 w-4" />
          </span>
          <div>
            <p className="text-xs text-muted-foreground">{label}</p>
            <p className="font-display text-lg font-semibold">{value}</p>
            {sub && <p className="text-[10px] text-muted-foreground">{sub}</p>}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function AlarmCard({ alarm, onToggle }: { alarm: SmartAlarm; onToggle: (id: string) => void }) {
  const isNudge = alarm.alarm_type === "activity_nudge";
  return (
    <div className="flex items-start gap-4 rounded-xl border border-border/60 bg-card/60 p-4">
      <span className={`mt-0.5 inline-flex h-9 w-9 items-center justify-center rounded-lg ${
        isNudge ? "bg-primary/12 text-primary" : "bg-accent/12 text-accent"
      }`}>
        {isNudge ? <Bell className="h-4 w-4" /> : <AlertTriangle className="h-4 w-4" />}
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="font-display font-semibold text-sm">
            {alarm.active_time ?? alarm.suggested_time}
          </span>
          <Badge variant="outline" className="text-[10px]">{alarm.days_label}</Badge>
          {alarm.is_user_overridden && (
            <Badge variant="secondary" className="text-[10px]">Custom</Badge>
          )}
        </div>
        <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
          {alarm.message}
        </p>
        <div className="flex items-center gap-3 mt-2 text-[10px] text-muted-foreground">
          <span>Confidence: {Math.round(alarm.confidence_score * 100)}%</span>
          <span>&middot;</span>
          <span>Based on {alarm.based_on_activities} activities</span>
          {alarm.variance_minutes != null && alarm.variance_minutes > 60 && (
            <>
              <span>&middot;</span>
              <span className="text-accent">~{alarm.variance_minutes}min variance</span>
            </>
          )}
        </div>
      </div>
      <Switch
        checked={alarm.is_enabled}
        onCheckedChange={() => onToggle(alarm.id)}
      />
    </div>
  );
}

function ActivityRow({ activity }: { activity: Activity }) {
  const meta = PROVIDER_META[activity.provider];
  return (
    <div className="flex items-center gap-4 rounded-lg border border-border/40 bg-card/40 p-3">
      <div
        className="flex h-8 w-8 items-center justify-center rounded-lg text-white text-[10px] font-bold"
        style={{ backgroundColor: meta.color }}
      >
        {ACTIVITY_TYPE_LABELS[activity.activity_type]?.slice(0, 2).toUpperCase() ?? "??"}
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium truncate">{activity.activity_name}</p>
        <p className="text-[10px] text-muted-foreground">
          {new Date(activity.started_at).toLocaleDateString("en-US", {
            weekday: "short",
            month: "short",
            day: "numeric",
            hour: "numeric",
            minute: "2-digit",
          })}
          {" via "}{meta.label}
        </p>
      </div>
      <div className="flex gap-4 text-right text-xs text-muted-foreground">
        {activity.duration_seconds && (
          <span className="flex items-center gap-1">
            <Clock className="h-3 w-3" />
            {formatDuration(activity.duration_seconds)}
          </span>
        )}
        {activity.distance_meters && (
          <span className="flex items-center gap-1">
            <TrendingUp className="h-3 w-3" />
            {formatDistance(activity.distance_meters)}
          </span>
        )}
        {activity.calories_burned && (
          <span className="flex items-center gap-1">
            <Flame className="h-3 w-3" />
            {Math.round(activity.calories_burned)} cal
          </span>
        )}
      </div>
    </div>
  );
}

function ShareModal({ report, onClose }: { report: ConsistencyReport; onClose: () => void }) {
  const shareUrl = report.share_url || "https://mindsetframes.online";
  const stats = [];
  if (report.total_activities > 0) stats.push(`${report.total_activities} activities`);
  if (report.total_duration_formatted) stats.push(report.total_duration_formatted + " active");
  if (report.current_streak > 0) stats.push(`${report.current_streak}-day streak`);
  if (report.habit_completion_percent > 0) stats.push(`${report.habit_completion_percent}% completion`);
  const shareText = `My ${report.report_type} report: ${stats.join(" | ")} — tracked with Mindset Frames`;

  const platforms = [
    { key: "twitter", label: "X / Twitter", icon: Twitter, url: `https://twitter.com/intent/tweet?text=${encodeURIComponent(shareText)}&url=${encodeURIComponent(shareUrl)}` },
    { key: "facebook", label: "Facebook", icon: Facebook, url: `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(shareUrl)}` },
    { key: "linkedin", label: "LinkedIn", icon: Linkedin, url: `https://www.linkedin.com/sharing/share-offsite/?url=${encodeURIComponent(shareUrl)}` },
    { key: "whatsapp", label: "WhatsApp", icon: MessageCircle, url: `https://api.whatsapp.com/send?text=${encodeURIComponent(shareText + " " + shareUrl)}` },
    { key: "telegram", label: "Telegram", icon: Send, url: `https://t.me/share/url?url=${encodeURIComponent(shareUrl)}&text=${encodeURIComponent(shareText)}` },
  ];

  function copyLink() {
    navigator.clipboard?.writeText(shareUrl);
    toast.success("Link copied to clipboard!");
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="mx-4 w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <h3 className="font-display text-xl font-semibold">Share Your Report</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          Show the world your consistency and progress
        </p>

        {/* Report preview card */}
        <div className="mt-4 rounded-xl border border-primary/20 bg-primary/5 p-4">
          <div className="flex items-center justify-between">
            <span className="font-display font-semibold text-sm capitalize">{report.report_type} Report</span>
            <span className="text-[10px] text-muted-foreground">{report.period_start} — {report.period_end}</span>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div><p className="text-2xl font-display font-bold">{report.total_activities}</p><p className="text-[10px] text-muted-foreground">Activities</p></div>
            <div><p className="text-2xl font-display font-bold">{report.total_duration_formatted}</p><p className="text-[10px] text-muted-foreground">Active</p></div>
            <div><p className="text-2xl font-display font-bold">{report.current_streak}</p><p className="text-[10px] text-muted-foreground">Day streak</p></div>
            <div><p className="text-2xl font-display font-bold">{report.habit_completion_percent}%</p><p className="text-[10px] text-muted-foreground">Completion</p></div>
          </div>
          <p className="mt-2 text-center text-[9px] text-muted-foreground">Mindset Frames — mindsetframes.online</p>
        </div>

        {/* Platform buttons */}
        <div className="mt-5 grid grid-cols-3 gap-2">
          {platforms.map(({ key, label, icon: Icon, url }) => (
            <a
              key={key}
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="flex flex-col items-center gap-1.5 rounded-xl border border-border/60 bg-secondary/50 p-3 text-xs transition-colors hover:bg-secondary hover:border-primary/30"
            >
              <Icon className="h-5 w-5" />
              {label}
            </a>
          ))}
          <button
            onClick={copyLink}
            className="flex flex-col items-center gap-1.5 rounded-xl border border-border/60 bg-secondary/50 p-3 text-xs transition-colors hover:bg-secondary hover:border-primary/30"
          >
            <Copy className="h-5 w-5" />
            Copy Link
          </button>
        </div>

        <Button onClick={onClose} variant="ghost" className="mt-4 w-full">
          Close
        </Button>
      </div>
    </div>
  );
}

// ── Main Dashboard Page ──────────────────────────────────────────────────────

export default function ActivityDashboard() {
  const [connections, setConnections] = useState<ProviderConnection[]>(DEMO_CONNECTIONS);
  const [alarms, setAlarms] = useState<SmartAlarm[]>(DEMO_ALARMS);
  const [showShareModal, setShowShareModal] = useState(false);

  // Demo handlers
  function handleConnect(provider: Provider) {
    toast.info(
      `To connect ${PROVIDER_META[provider].label}, download Mindset Frames and sign in to your ${PROVIDER_META[provider].label} account first.`
    );
  }

  function handleDisconnect(provider: Provider) {
    setConnections((prev) =>
      prev.map((c) =>
        c.provider === provider ? { ...c, is_connected: false, provider_display_name: null } : c
      )
    );
    toast.success(`Disconnected ${PROVIDER_META[provider].label}`);
  }

  function handleSync(provider: Provider) {
    toast.success(`Synced activities from ${PROVIDER_META[provider].label}`);
  }

  function handleToggleAlarm(id: string) {
    setAlarms((prev) =>
      prev.map((a) => (a.id === id ? { ...a, is_enabled: !a.is_enabled } : a))
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <Navbar />
      <main className="mx-auto max-w-5xl px-5 pt-28 pb-20">
        {/* Hero */}
        <Reveal>
          <div className="flex items-center gap-3">
            <a href="/" className="text-muted-foreground hover:text-foreground transition">
              <ArrowLeft className="h-5 w-5" />
            </a>
            <div>
              <h1 className="font-display text-3xl font-semibold tracking-tight sm:text-4xl">
                Activity Integrations
              </h1>
              <p className="mt-1 text-muted-foreground">
                Connect your fitness accounts, sync activities into Mindset Frames, and let smart alarms adapt to your rhythm.
              </p>
            </div>
          </div>
        </Reveal>

        <Tabs defaultValue="connect" className="mt-8">
          <TabsList className="grid w-full grid-cols-4 bg-muted/50">
            <TabsTrigger value="connect" className="gap-1.5 text-xs">
              <Link2 className="h-3.5 w-3.5" /> Connect
            </TabsTrigger>
            <TabsTrigger value="activities" className="gap-1.5 text-xs">
              <ActivityIcon className="h-3.5 w-3.5" /> Activities
            </TabsTrigger>
            <TabsTrigger value="alarms" className="gap-1.5 text-xs">
              <Bell className="h-3.5 w-3.5" /> Smart Alarms
            </TabsTrigger>
            <TabsTrigger value="reports" className="gap-1.5 text-xs">
              <BarChart3 className="h-3.5 w-3.5" /> Reports
            </TabsTrigger>
          </TabsList>

          {/* ── Connect Tab ────────────────────────────────────────────── */}
          <TabsContent value="connect" className="mt-6 space-y-4">
            <Reveal>
              <Card className="border-primary/20 bg-primary/5">
                <CardContent className="p-5">
                  <div className="flex items-start gap-3">
                    <CheckCircle2 className="h-5 w-5 text-primary mt-0.5" />
                    <div>
                      <p className="font-display font-semibold text-sm">Login Required Before Sync</p>
                      <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                        For your security, you must sign in to each provider's account before any data is synced.
                        Mindset Frames uses OAuth — your password is never stored. Each provider handles its own authentication.
                      </p>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </Reveal>

            <div className="space-y-3">
              {connections.map((conn, i) => (
                <Reveal key={conn.provider} delay={i * 80}>
                  <ProviderCard
                    conn={conn}
                    onConnect={handleConnect}
                    onDisconnect={handleDisconnect}
                    onSync={handleSync}
                  />
                </Reveal>
              ))}
            </div>

            <Reveal delay={300}>
              <Card className="border-border/40">
                <CardContent className="p-5">
                  <h3 className="font-display font-semibold text-sm">How it works</h3>
                  <ol className="mt-3 space-y-2 text-xs text-muted-foreground">
                    <li className="flex gap-2">
                      <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary text-[10px] font-bold">1</span>
                      <span><strong className="text-foreground">Sign in</strong> to your Strava, Google, or Huawei account via their secure OAuth page.</span>
                    </li>
                    <li className="flex gap-2">
                      <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary text-[10px] font-bold">2</span>
                      <span><strong className="text-foreground">Authorize</strong> Mindset Frames to read your activity data (read-only access).</span>
                    </li>
                    <li className="flex gap-2">
                      <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary text-[10px] font-bold">3</span>
                      <span><strong className="text-foreground">Sync</strong> your activities. Smart alarms analyze your patterns and suggest optimal times.</span>
                    </li>
                    <li className="flex gap-2">
                      <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary text-[10px] font-bold">4</span>
                      <span><strong className="text-foreground">Generate</strong> consistency reports and share your progress on social media.</span>
                    </li>
                  </ol>
                </CardContent>
              </Card>
            </Reveal>
          </TabsContent>

          {/* ── Activities Tab ─────────────────────────────────────────── */}
          <TabsContent value="activities" className="mt-6 space-y-6">
            {/* Summary cards */}
            <Reveal>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <StatCard icon={ActivityIcon} label="Activities" value={String(DEMO_SUMMARY.total_activities)} sub="This week" />
                <StatCard icon={Clock} label="Active Time" value={formatDuration(DEMO_SUMMARY.total_duration_seconds)} sub={`${DEMO_SUMMARY.active_days} active days`} />
                <StatCard icon={Footprints} label="Steps" value={`${(DEMO_SUMMARY.total_steps / 1000).toFixed(1)}k`} sub={formatDistance(DEMO_SUMMARY.total_distance_meters)} />
                <StatCard icon={Flame} label="Calories" value={String(DEMO_SUMMARY.total_calories)} sub={`Peak: ${DEMO_SUMMARY.most_active_time}`} />
              </div>
            </Reveal>

            {/* Consistency gauge */}
            <Reveal delay={100}>
              <Card className="border-border/60">
                <CardContent className="p-5">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="font-display font-semibold text-sm">Weekly Consistency</p>
                      <p className="text-xs text-muted-foreground mt-0.5">
                        {DEMO_SUMMARY.active_days} of {DEMO_SUMMARY.period_days} days active &middot; Most active on {DEMO_SUMMARY.most_active_day}s
                      </p>
                    </div>
                    <span className="font-display text-2xl font-bold text-primary">
                      {Math.round(DEMO_SUMMARY.consistency_rate * 100)}%
                    </span>
                  </div>
                  <Progress value={DEMO_SUMMARY.consistency_rate * 100} className="mt-3 h-2" />
                </CardContent>
              </Card>
            </Reveal>

            {/* Timing analysis */}
            {DEMO_SUMMARY.timing_analysis.some((t) => t.is_inconsistent) && (
              <Reveal delay={150}>
                <Card className="border-accent/20 bg-accent/5">
                  <CardContent className="p-5">
                    <div className="flex items-start gap-3">
                      <AlertTriangle className="h-5 w-5 text-accent mt-0.5" />
                      <div>
                        <p className="font-display font-semibold text-sm">Inconsistent Timing Detected</p>
                        <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                          Some activities have high timing variance. Smart Alarms can help you build consistency.
                        </p>
                        <div className="mt-3 space-y-2">
                          {DEMO_SUMMARY.timing_analysis.filter((t) => t.is_inconsistent).map((t) => (
                            <div key={t.activity_type} className="flex items-center gap-2 text-xs">
                              <span className="text-accent font-medium">{ACTIVITY_TYPE_LABELS[t.activity_type]}</span>
                              <span className="text-muted-foreground">
                                avg {formatMinutesToTime(t.avg_time_minutes)} &middot; ~{t.variance_minutes}min variance across {t.count} sessions
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </Reveal>
            )}

            {/* Activity list */}
            <Reveal delay={200}>
              <div className="space-y-2">
                <h3 className="font-display font-semibold text-sm">Recent Activities</h3>
                {DEMO_ACTIVITIES.map((a) => (
                  <ActivityRow key={a.id} activity={a} />
                ))}
              </div>
            </Reveal>
          </TabsContent>

          {/* ── Smart Alarms Tab ───────────────────────────────────────── */}
          <TabsContent value="alarms" className="mt-6 space-y-6">
            <Reveal>
              <Card className="border-primary/20 bg-primary/5">
                <CardContent className="p-5">
                  <div className="flex items-start gap-3">
                    <Bell className="h-5 w-5 text-primary mt-0.5" />
                    <div>
                      <p className="font-display font-semibold text-sm">Smart Alarms Adapt to Your Rhythm</p>
                      <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                        Alarms are generated from your actual activity data. When your timing is inconsistent,
                        we create separate weekday/weekend alarms and add consistency checks to prevent streak breaks.
                        Override any alarm time manually — your preferences always come first.
                      </p>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </Reveal>

            <div className="space-y-3">
              {alarms.map((alarm, i) => (
                <Reveal key={alarm.id} delay={i * 80}>
                  <AlarmCard alarm={alarm} onToggle={handleToggleAlarm} />
                </Reveal>
              ))}
            </div>

            <Reveal delay={300}>
              <Button
                variant="outline"
                className="w-full gap-2"
                onClick={() => toast.info("Analysis runs automatically when you sync activities.")}
              >
                <RefreshCcw className="h-4 w-4" />
                Re-analyze Activity Patterns
              </Button>
            </Reveal>
          </TabsContent>

          {/* ── Reports & Sharing Tab ──────────────────────────────────── */}
          <TabsContent value="reports" className="mt-6 space-y-6">
            <Reveal>
              <div className="grid gap-3 sm:grid-cols-3">
                <Button variant="outline" className="h-auto flex-col gap-1.5 p-4" onClick={() => toast.success("Weekly report generated!")}>
                  <BarChart3 className="h-5 w-5 text-primary" />
                  <span className="font-display font-semibold text-sm">Weekly Report</span>
                  <span className="text-[10px] text-muted-foreground">Last 7 days</span>
                </Button>
                <Button variant="outline" className="h-auto flex-col gap-1.5 p-4" onClick={() => toast.success("Monthly report generated!")}>
                  <BarChart3 className="h-5 w-5 text-primary" />
                  <span className="font-display font-semibold text-sm">Monthly Report</span>
                  <span className="text-[10px] text-muted-foreground">Last 30 days</span>
                </Button>
                <Button variant="outline" className="h-auto flex-col gap-1.5 p-4" onClick={() => toast.info("Custom date range coming soon!")}>
                  <BarChart3 className="h-5 w-5 text-muted-foreground" />
                  <span className="font-display font-semibold text-sm">Custom Range</span>
                  <span className="text-[10px] text-muted-foreground">Pick dates</span>
                </Button>
              </div>
            </Reveal>

            {/* Report preview */}
            <Reveal delay={100}>
              <Card className="border-border/60">
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <CardTitle className="font-display text-lg capitalize">{DEMO_REPORT.report_type} Report</CardTitle>
                      <CardDescription className="text-xs">
                        {DEMO_REPORT.period_start} — {DEMO_REPORT.period_end}
                      </CardDescription>
                    </div>
                    <div className="flex gap-2">
                      <Button size="sm" variant="outline" className="h-8 gap-1.5 text-xs" onClick={() => setShowShareModal(true)}>
                        <Share2 className="h-3.5 w-3.5" /> Share
                      </Button>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="space-y-4 pt-0">
                  <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                    <div>
                      <p className="text-2xl font-display font-bold">{DEMO_REPORT.total_activities}</p>
                      <p className="text-[10px] text-muted-foreground">Activities</p>
                    </div>
                    <div>
                      <p className="text-2xl font-display font-bold">{DEMO_REPORT.total_duration_formatted}</p>
                      <p className="text-[10px] text-muted-foreground">Active</p>
                    </div>
                    <div>
                      <p className="text-2xl font-display font-bold">{DEMO_REPORT.current_streak}</p>
                      <p className="text-[10px] text-muted-foreground">Current streak</p>
                    </div>
                    <div>
                      <p className="text-2xl font-display font-bold">{DEMO_REPORT.habit_completion_percent}%</p>
                      <p className="text-[10px] text-muted-foreground">Habit completion</p>
                    </div>
                  </div>

                  <Separator />

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <p className="text-xs font-medium">Top Activities</p>
                      <div className="mt-2 space-y-1.5">
                        {DEMO_REPORT.top_activities.map((a) => (
                          <div key={a.type} className="flex items-center justify-between text-xs">
                            <span>{ACTIVITY_TYPE_LABELS[a.type]}</span>
                            <span className="text-muted-foreground">{a.count}x &middot; {formatDuration(a.duration)}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                    <div>
                      <p className="text-xs font-medium">Mood Distribution</p>
                      <div className="mt-2 space-y-1.5">
                        {Object.entries(DEMO_REPORT.mood_distribution).map(([mood, count]) => (
                          <div key={mood} className="flex items-center justify-between text-xs">
                            <span className="capitalize">{mood}</span>
                            <span className="text-muted-foreground">{count} days</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>

                  <Separator />

                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>
                      {DEMO_REPORT.total_distance_km} km &middot; {DEMO_REPORT.total_calories} cal &middot; {(DEMO_REPORT.total_steps / 1000).toFixed(1)}k steps
                    </span>
                    <span>Most active: {DEMO_REPORT.most_active_day} {DEMO_REPORT.most_active_time}s</span>
                  </div>
                </CardContent>
              </Card>
            </Reveal>

            {/* Social share CTA */}
            <Reveal delay={200}>
              <Card className="border-primary/20 bg-primary/5">
                <CardContent className="flex items-center gap-4 p-5">
                  <Share2 className="h-8 w-8 text-primary shrink-0" />
                  <div className="flex-1">
                    <p className="font-display font-semibold text-sm">Share Your Progress</p>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      Share on Twitter/X, Facebook, LinkedIn, WhatsApp, Telegram, WeChat, LINE, and more.
                      Each report gets a unique public link anyone can view.
                    </p>
                  </div>
                  <Button size="sm" className="gap-1.5" onClick={() => setShowShareModal(true)}>
                    <ExternalLink className="h-3.5 w-3.5" /> Share
                  </Button>
                </CardContent>
              </Card>
            </Reveal>
          </TabsContent>
        </Tabs>
      </main>
      <SiteFooter />

      {showShareModal && (
        <ShareModal report={DEMO_REPORT} onClose={() => setShowShareModal(false)} />
      )}
    </div>
  );
}
