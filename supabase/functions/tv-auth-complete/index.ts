import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, x-client-info, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
}

const emailRegex = /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,63}$/i
const blockedEmailDomains = new Set([
  "example.com",
  "example.net",
  "example.org",
  "invalid",
  "localhost",
  "mailinator.com",
  "guerrillamail.com",
  "guerrillamail.net",
  "10minutemail.com",
  "tempmail.com",
  "temp-mail.org",
  "yopmail.com",
])
const emailSendCooldownMs = 60 * 1000
const emailSendAttempts = new Map<string, number>()

function validateEmail(email: string, rejectDisposable = true): string | null {
  if (!email) return "Email is required"
  if (email.length > 254 || !emailRegex.test(email)) return "Enter a valid email address"
  const [localPart, domain = ""] = email.split("@")
  if (!localPart || !domain) return "Use a real email address"
  if (rejectDisposable && blockedEmailDomains.has(domain)) return "Use a real email address"
  if (rejectDisposable && (domain.endsWith(".invalid") || domain.endsWith(".test") || domain.endsWith(".local"))) {
    return "Use a real email address"
  }
  if (domain.split(".").some((part) => !part)) return "Enter a valid email address"
  return null
}

function enforceEmailSendCooldown(action: string, email: string): string | null {
  const key = `${action}:${email}`
  const last = emailSendAttempts.get(key) ?? 0
  const remaining = emailSendCooldownMs - (Date.now() - last)
  if (remaining > 0) {
    return `Please wait ${Math.ceil(remaining / 1000)}s before requesting another email.`
  }
  emailSendAttempts.set(key, Date.now())
  return null
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    })
  }

  try {
    const anonHeader = req.headers.get("apikey")
    const authHeader = req.headers.get("authorization")
    const expectedAnon = Deno.env.get("APP_ANON_KEY") ?? Deno.env.get("SUPABASE_ANON_KEY")

    const hasValidApiKey = !!anonHeader && !!expectedAnon && anonHeader === expectedAnon
    const hasValidBearer = !!authHeader && authHeader.startsWith("Bearer ") && !!expectedAnon && authHeader.replace("Bearer ", "") === expectedAnon

    if (!hasValidApiKey && !hasValidBearer) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL")
    const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
    const anonKey = expectedAnon
    if (!supabaseUrl || !serviceRole || !anonKey) {
      throw new Error("Missing Supabase credentials")
    }

    const body = await req.json().catch(() => ({})) as {
      code?: string
      email?: string
      password?: string
      intent?: string
    }

    const code = body.code?.trim() || ""
    const email = body.email?.trim().toLowerCase() || ""
    const password = body.password || ""
    const intent = (body.intent || "signin").trim().toLowerCase()

    if (!code || !email || !password) {
      return new Response(JSON.stringify({ error: "Missing required fields" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    const emailError = validateEmail(email, intent === "signup")
    if (emailError) {
      return new Response(JSON.stringify({ error: emailError }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    if (intent === "signup") {
      const cooldownError = enforceEmailSendCooldown("signup", email)
      if (cooldownError) {
        return new Response(JSON.stringify({ error: cooldownError }), {
          status: 429,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        })
      }
    }

    const sessionQuery = await fetch(
      `${supabaseUrl}/rest/v1/tv_device_auth_sessions?select=id,status,expires_at,user_code&user_code=eq.${encodeURIComponent(code)}&limit=1`,
      {
        headers: {
          apikey: serviceRole,
          Authorization: `Bearer ${serviceRole}`,
        },
      },
    )

    if (!sessionQuery.ok) {
      const txt = await sessionQuery.text()
      throw new Error(`Unable to validate code (${txt})`)
    }

    const rows = await sessionQuery.json() as Array<Record<string, string>>
    const row = rows[0]
    if (!row) {
      return new Response(JSON.stringify({ error: "Invalid or expired code" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    const isExpired = Date.now() > Date.parse(row.expires_at)
    if (isExpired || row.status !== "pending") {
      return new Response(JSON.stringify({ error: "Code has expired" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    if (intent === "signup") {
      const signupResp = await fetch(`${supabaseUrl}/auth/v1/signup`, {
        method: "POST",
        headers: {
          apikey: anonKey,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ email, password }),
      })
      if (!signupResp.ok) {
        return new Response(JSON.stringify({ error: "Unable to create account" }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        })
      }
    }

    const tokenResp = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=password`, {
      method: "POST",
      headers: {
        apikey: anonKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email, password }),
    })

    if (!tokenResp.ok) {
      const message = intent === "signup"
        ? "Account created. Verify your email first, then sign in."
        : "Invalid email or password"
      return new Response(JSON.stringify({ error: message }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      })
    }

    const auth = await tokenResp.json() as {
      access_token?: string
      refresh_token?: string
      user?: { id?: string; email?: string }
    }

    if (!auth.access_token || !auth.refresh_token || !auth.user?.id) {
      throw new Error("Auth response incomplete")
    }

    const updateResp = await fetch(
      `${supabaseUrl}/rest/v1/tv_device_auth_sessions?user_code=eq.${encodeURIComponent(code)}`,
      {
        method: "PATCH",
        headers: {
          apikey: serviceRole,
          Authorization: `Bearer ${serviceRole}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          status: "approved",
          approved_at: new Date().toISOString(),
          user_id: auth.user.id,
          user_email: auth.user.email ?? email,
          access_token: auth.access_token,
          refresh_token: auth.refresh_token,
        }),
      },
    )

    if (!updateResp.ok) {
      const txt = await updateResp.text()
      throw new Error(`Failed to link device (${txt})`)
    }

    return new Response(JSON.stringify({ ok: true }), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    })
  } catch (error) {
    return new Response(
      JSON.stringify({ error: error instanceof Error ? error.message : "Unexpected error" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } },
    )
  }
})
