/**
 * habit-recommend — Supabase Edge Function
 *
 * AI-powered habit & to-do suggestions via the Gemini API (free tier).
 * The Gemini API key is stored as a Supabase Edge Function secret
 * (GEMINI_API_KEY) — never shipped in the APK.
 *
 * Accepts:
 *   POST { existing_habits: string[], mood?: string, activity_summary?: string }
 *
 * Returns:
 *   { suggestions: [{ name: string, reason: string }] }
 *
 * Falls back to a curated on-device list when:
 *  - The user is not signed in (auth required)
 *  - The Gemini API key isn't configured
 *  - Gemini returns an error or is rate-limited
 *
 * The client (AppViewModel.getSuggestions) handles the fallback silently.
 */

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

interface RequestBody {
  existing_habits: string[];
  mood?: string;
  activity_summary?: string;
  context_type?: "habits" | "todos" | "daily_focus";
}

interface Suggestion {
  name: string;
  reason: string;
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

/**
 * Builds a Gemini prompt tuned to produce actionable habit / to-do
 * suggestions based on the user's current state.
 */
function buildPrompt(body: RequestBody): string {
  const contextType = body.context_type ?? "habits";
  const existingList =
    body.existing_habits.length > 0
      ? body.existing_habits.map((h) => `- ${h}`).join("\n")
      : "(none yet)";

  const moodLine = body.mood
    ? `The user's current mood is: ${body.mood}.`
    : "";
  const activityLine = body.activity_summary
    ? `Recent fitness activity: ${body.activity_summary}.`
    : "";

  if (contextType === "todos") {
    return `You are a personal productivity coach inside a habit-tracking app called Mindset Frames.

The user already tracks these habits:
${existingList}

${moodLine}
${activityLine}

Suggest 5 specific, actionable to-do items for TODAY that complement their existing habits and current mood. Each should be:
- Concrete and completeable in one day
- 1-2 sentences max
- Varied across wellness, productivity, social, and self-care
- Sensitive to their mood (if overwhelmed, suggest lighter items; if motivated, suggest stretch goals)

Return ONLY valid JSON: { "suggestions": [{ "name": "<short task>", "reason": "<why this helps today>" }] }`;
  }

  if (contextType === "daily_focus") {
    return `You are a mindfulness coach inside a habit-tracking app called Mindset Frames.

The user's habits:
${existingList}

${moodLine}
${activityLine}

Suggest 3 focus areas for today — one physical, one mental, one social. Each should be a short actionable sentence with a brief "why" that feels warm and encouraging, not preachy.

Return ONLY valid JSON: { "suggestions": [{ "name": "<focus area>", "reason": "<warm encouragement>" }] }`;
  }

  // Default: habit suggestions
  return `You are a habit coach inside a habit-tracking app called Mindset Frames.

The user already tracks these habits:
${existingList}

${moodLine}
${activityLine}

Suggest 5 new habits that would complement their existing routine. Each should be:
- Specific and measurable (not vague like "be healthier")
- Small enough to do daily (5-15 minutes max)
- Varied across health, mindfulness, productivity, social, and finance
- NOT duplicating what they already track

Return ONLY valid JSON: { "suggestions": [{ "name": "<habit name, max 60 chars>", "reason": "<why this pairs well, 1 sentence>" }] }`;
}

async function callGemini(prompt: string): Promise<Suggestion[] | null> {
  const apiKey = Deno.env.get("GEMINI_API_KEY");
  if (!apiKey) {
    console.log("GEMINI_API_KEY not set — AI suggestions unavailable");
    return null;
  }

  const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${apiKey}`;

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.8,
        maxOutputTokens: 1024,
        responseMimeType: "application/json",
      },
    }),
  });

  if (!response.ok) {
    console.error(`Gemini API error: ${response.status} ${response.statusText}`);
    return null;
  }

  const data = await response.json();
  const text =
    data?.candidates?.[0]?.content?.parts?.[0]?.text ?? "";

  // Parse the JSON from Gemini's response
  try {
    // Gemini sometimes wraps in markdown code blocks
    const cleaned = text.replace(/```json\n?/g, "").replace(/```\n?/g, "").trim();
    const parsed = JSON.parse(cleaned);
    if (Array.isArray(parsed?.suggestions)) {
      return parsed.suggestions
        .filter(
          (s: unknown) =>
            typeof (s as Suggestion)?.name === "string" &&
            typeof (s as Suggestion)?.reason === "string"
        )
        .slice(0, 7) as Suggestion[];
    }
  } catch (e) {
    console.error("Failed to parse Gemini response:", e, text);
  }
  return null;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return error("Method not allowed", 405);
  }

  try {
    // Auth check: require a valid token
    const authHeader = req.headers.get("Authorization");
    if (!authHeader?.startsWith("Bearer ")) {
      return error("Authorization required", 401);
    }

    const body: RequestBody = await req.json();
    const existingHabits = body.existing_habits ?? [];

    const prompt = buildPrompt({ ...body, existing_habits: existingHabits });
    const suggestions = await callGemini(prompt);

    if (!suggestions || suggestions.length === 0) {
      // Return empty — client falls back to on-device suggestions
      return json({ suggestions: null });
    }

    return json({ suggestions });
  } catch (err) {
    console.error("habit-recommend error:", err);
    return error("Internal server error", 500);
  }
});
