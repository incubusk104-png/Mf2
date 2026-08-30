import { useState } from "react";
import {
  BarChart3,
  Clock,
  Flame,
  Footprints,
  TrendingUp,
  Heart,
  Share2,
  Copy,
  Twitter,
  Facebook,
  Linkedin,
  MessageCircle,
  Send,
  ExternalLink,
  ArrowLeft,
} from "lucide-react";
import { useParams } from "react-router-dom";

import { Navbar } from "@/components/marketing/Navbar";
import { SiteFooter } from "@/components/marketing/SiteFooter";
import { Reveal } from "@/components/marketing/Reveal";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { toast } from "sonner";

import {
  ACTIVITY_TYPE_LABELS,
  formatDuration,
} from "@/lib/activityTypes";

/**
 * Public share page — renders a consistency report without requiring auth.
 * The share token in the URL is used to fetch the report from the backend.
 *
 * For the marketing preview, this renders demo data.
 */
export default function ShareReport() {
  const { token } = useParams<{ token: string }>();
  const [copied, setCopied] = useState(false);

  // Demo report data (in production, fetch from /consistency-report/share/:token)
  const report = {
    report_type: "weekly" as const,
    period_start: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
    period_end: new Date().toISOString().slice(0, 10),
    total_activities: 12,
    total_duration_formatted: "5h 12m",
    total_distance_km: 42.5,
    total_calories: 2840,
    total_steps: 68200,
    habit_completion_percent: 86,
    longest_streak: 6,
    current_streak: 4,
    most_active_day: "Wednesday",
    most_active_time: "morning",
    top_activities: [
      { type: "run", count: 5, duration: 8400 },
      { type: "yoga", count: 3, duration: 7200 },
      { type: "cycle", count: 2, duration: 1800 },
    ],
    mood_distribution: { calm: 3, focused: 2, motivated: 1, overwhelmed: 1 } as Record<string, number>,
  };

  const shareUrl = window.location.href;
  const stats = [];
  if (report.total_activities > 0) stats.push(`${report.total_activities} activities`);
  if (report.total_duration_formatted) stats.push(report.total_duration_formatted + " active");
  if (report.current_streak > 0) stats.push(`${report.current_streak}-day streak`);
  if (report.habit_completion_percent > 0) stats.push(`${report.habit_completion_percent}% completion`);
  const shareText = `Check out this ${report.report_type} consistency report: ${stats.join(" | ")} — tracked with Mindset Frames`;

  function copyLink() {
    navigator.clipboard?.writeText(shareUrl);
    setCopied(true);
    toast.success("Link copied!");
    setTimeout(() => setCopied(false), 2000);
  }

  const platforms = [
    { key: "twitter", label: "X", icon: Twitter, url: `https://twitter.com/intent/tweet?text=${encodeURIComponent(shareText)}&url=${encodeURIComponent(shareUrl)}` },
    { key: "facebook", label: "Facebook", icon: Facebook, url: `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(shareUrl)}` },
    { key: "linkedin", label: "LinkedIn", icon: Linkedin, url: `https://www.linkedin.com/sharing/share-offsite/?url=${encodeURIComponent(shareUrl)}` },
    { key: "whatsapp", label: "WhatsApp", icon: MessageCircle, url: `https://api.whatsapp.com/send?text=${encodeURIComponent(shareText + " " + shareUrl)}` },
    { key: "telegram", label: "Telegram", icon: Send, url: `https://t.me/share/url?url=${encodeURIComponent(shareUrl)}&text=${encodeURIComponent(shareText)}` },
  ];

  return (
    <div className="min-h-screen bg-background">
      <Navbar />
      <main className="mx-auto max-w-2xl px-5 pt-28 pb-20">
        <Reveal>
          <div className="text-center">
            <Badge variant="outline" className="border-primary/30 text-primary mb-4">
              Shared Report
            </Badge>
            <h1 className="font-display text-3xl font-semibold tracking-tight capitalize">
              {report.report_type} Consistency Report
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {report.period_start} — {report.period_end}
            </p>
          </div>
        </Reveal>

        {/* Big stats */}
        <Reveal delay={100}>
          <div className="mt-8 grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Card className="border-border/60 text-center">
              <CardContent className="p-4">
                <p className="text-3xl font-display font-bold text-primary">{report.total_activities}</p>
                <p className="text-xs text-muted-foreground mt-1">Activities</p>
              </CardContent>
            </Card>
            <Card className="border-border/60 text-center">
              <CardContent className="p-4">
                <p className="text-3xl font-display font-bold text-primary">{report.total_duration_formatted}</p>
                <p className="text-xs text-muted-foreground mt-1">Active Time</p>
              </CardContent>
            </Card>
            <Card className="border-border/60 text-center">
              <CardContent className="p-4">
                <p className="text-3xl font-display font-bold text-primary">{report.current_streak}</p>
                <p className="text-xs text-muted-foreground mt-1">Day Streak</p>
              </CardContent>
            </Card>
            <Card className="border-border/60 text-center">
              <CardContent className="p-4">
                <p className="text-3xl font-display font-bold text-primary">{report.habit_completion_percent}%</p>
                <p className="text-xs text-muted-foreground mt-1">Completion</p>
              </CardContent>
            </Card>
          </div>
        </Reveal>

        {/* Details */}
        <Reveal delay={200}>
          <Card className="mt-6 border-border/60">
            <CardContent className="p-6 space-y-5">
              {/* Progress bar */}
              <div>
                <div className="flex items-center justify-between text-sm">
                  <span className="font-display font-semibold">Habit Completion</span>
                  <span className="text-primary font-bold">{report.habit_completion_percent}%</span>
                </div>
                <Progress value={report.habit_completion_percent} className="mt-2 h-3" />
              </div>

              <Separator />

              {/* Two columns */}
              <div className="grid grid-cols-2 gap-6">
                <div>
                  <p className="text-sm font-display font-semibold">Top Activities</p>
                  <div className="mt-3 space-y-2">
                    {report.top_activities.map((a) => (
                      <div key={a.type} className="flex items-center justify-between text-xs">
                        <span>{ACTIVITY_TYPE_LABELS[a.type] ?? a.type}</span>
                        <span className="text-muted-foreground">{a.count}x &middot; {formatDuration(a.duration)}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-sm font-display font-semibold">Mood This Week</p>
                  <div className="mt-3 space-y-2">
                    {Object.entries(report.mood_distribution).map(([mood, count]) => (
                      <div key={mood} className="flex items-center justify-between text-xs">
                        <span className="capitalize">{mood}</span>
                        <span className="text-muted-foreground">{count} days</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              <Separator />

              {/* Extra stats */}
              <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
                <span className="flex items-center gap-1"><TrendingUp className="h-3 w-3" /> {report.total_distance_km} km</span>
                <span className="flex items-center gap-1"><Flame className="h-3 w-3" /> {report.total_calories} cal</span>
                <span className="flex items-center gap-1"><Footprints className="h-3 w-3" /> {(report.total_steps / 1000).toFixed(1)}k steps</span>
                <span className="flex items-center gap-1"><BarChart3 className="h-3 w-3" /> Longest streak: {report.longest_streak} days</span>
                <span className="flex items-center gap-1"><Clock className="h-3 w-3" /> Most active: {report.most_active_day} {report.most_active_time}s</span>
              </div>
            </CardContent>
          </Card>
        </Reveal>

        {/* Share CTA */}
        <Reveal delay={300}>
          <Card className="mt-6 border-primary/20 bg-primary/5">
            <CardContent className="p-5">
              <p className="font-display font-semibold text-sm text-center">Share this report</p>
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {platforms.map(({ key, label, icon: Icon, url }) => (
                  <a
                    key={key}
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 rounded-lg border border-border/60 bg-secondary/50 px-3 py-2 text-xs transition-colors hover:bg-secondary hover:border-primary/30"
                  >
                    <Icon className="h-4 w-4" />
                    {label}
                  </a>
                ))}
                <button
                  onClick={copyLink}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-border/60 bg-secondary/50 px-3 py-2 text-xs transition-colors hover:bg-secondary hover:border-primary/30"
                >
                  <Copy className="h-4 w-4" />
                  {copied ? "Copied!" : "Copy Link"}
                </button>
              </div>
            </CardContent>
          </Card>
        </Reveal>

        {/* App promo */}
        <Reveal delay={400}>
          <div className="mt-8 text-center">
            <p className="text-sm text-muted-foreground">
              Tracked with{" "}
              <a href="/" className="text-primary font-display font-semibold hover:underline">
                Mindset Frames
              </a>
            </p>
            <p className="text-xs text-muted-foreground mt-1">
              Mood-first habit tracking with activity integrations and smart alarms.
            </p>
            <Button asChild variant="outline" className="mt-4 gap-2">
              <a href="/">
                <ArrowLeft className="h-4 w-4" /> Learn more about Mindset Frames
              </a>
            </Button>
          </div>
        </Reveal>
      </main>
      <SiteFooter />
    </div>
  );
}
