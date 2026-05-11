// TMDB API Proxy - Secured with rate limiting and path allowlist
// Deploy with: npx supabase functions deploy tmdb-proxy
// Set secrets:
//   npx supabase secrets set TMDB_API_KEY=your_key
//   npx supabase secrets set APP_ANON_KEY=your_anon_key

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const TMDB_BASE_URL = "https://api.themoviedb.org/3"

// Rate limiting: 100 requests per minute per IP
const RATE_LIMIT = 100
const RATE_WINDOW_MS = 60 * 1000
const rateLimitMap = new Map<string, { count: number; resetTime: number }>()

// Allowed TMDB paths (prefix matching)
const ALLOWED_PATHS = [
  '/trending/',
  '/movie/',
  '/tv/',
  '/search/',
  '/discover/',
  '/find/',
  '/genre/',
  '/person/',
  '/collection/',
  '/watch/providers',
  '/configuration',
]

function isPathAllowed(path: string): boolean {
  return ALLOWED_PATHS.some(allowed => path.startsWith(allowed))
}

function getClientIP(req: Request): string {
  // Try various headers for real IP (behind proxies/CDN)
  return req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ||
         req.headers.get('x-real-ip') ||
         req.headers.get('cf-connecting-ip') ||
         'unknown'
}

function checkRateLimit(ip: string): { allowed: boolean; remaining: number; resetIn: number } {
  const now = Date.now()
  const record = rateLimitMap.get(ip)

  if (!record || now > record.resetTime) {
    // New window
    rateLimitMap.set(ip, { count: 1, resetTime: now + RATE_WINDOW_MS })
    return { allowed: true, remaining: RATE_LIMIT - 1, resetIn: RATE_WINDOW_MS }
  }

  if (record.count >= RATE_LIMIT) {
    return { allowed: false, remaining: 0, resetIn: record.resetTime - now }
  }

  record.count++
  return { allowed: true, remaining: RATE_LIMIT - record.count, resetIn: record.resetTime - now }
}

// Clean up old rate limit entries periodically
setInterval(() => {
  const now = Date.now()
  for (const [ip, record] of rateLimitMap.entries()) {
    if (now > record.resetTime) {
      rateLimitMap.delete(ip)
    }
  }
}, 60000)

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const clientIP = getClientIP(req)

    // Check rate limit
    const rateCheck = checkRateLimit(clientIP)
    if (!rateCheck.allowed) {
      return new Response(JSON.stringify({
        error: 'Rate limit exceeded',
        retryAfter: Math.ceil(rateCheck.resetIn / 1000)
      }), {
        headers: {
          ...corsHeaders,
          'Content-Type': 'application/json',
          'Retry-After': String(Math.ceil(rateCheck.resetIn / 1000)),
          'X-RateLimit-Limit': String(RATE_LIMIT),
          'X-RateLimit-Remaining': '0',
          'X-RateLimit-Reset': String(Math.ceil(rateCheck.resetIn / 1000))
        },
        status: 429,
      })
    }

    // Verify authentication
    const apiKey = req.headers.get('apikey')
    const authHeader = req.headers.get('authorization')
    const EXPECTED_ANON_KEY = Deno.env.get('APP_ANON_KEY')

    // Accept either: valid apikey OR valid Authorization Bearer token
    const hasValidApiKey = apiKey && EXPECTED_ANON_KEY && apiKey === EXPECTED_ANON_KEY
    const hasValidAuth = authHeader?.startsWith('Bearer ') &&
                         EXPECTED_ANON_KEY &&
                         authHeader.replace('Bearer ', '') === EXPECTED_ANON_KEY

    if (!hasValidApiKey && !hasValidAuth) {
      return new Response(JSON.stringify({ error: 'Unauthorized' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 401,
      })
    }

    const TMDB_API_KEY = Deno.env.get('TMDB_API_KEY')
    if (!TMDB_API_KEY) {
      throw new Error('TMDB_API_KEY not configured')
    }

    // Get the path from the request URL
    const url = new URL(req.url)
    const path = url.searchParams.get('path')

    if (!path) {
      throw new Error('Missing path parameter')
    }

    // Validate path against allowlist
    if (!isPathAllowed(path)) {
      return new Response(JSON.stringify({ error: 'Path not allowed' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 403,
      })
    }

    // Build TMDB URL with all query parameters
    const tmdbUrl = new URL(`${TMDB_BASE_URL}${path}`)
    tmdbUrl.searchParams.set('api_key', TMDB_API_KEY)

    // Forward all other query parameters except 'path'
    url.searchParams.forEach((value, key) => {
      if (key !== 'path') {
        tmdbUrl.searchParams.set(key, value)
      }
    })

    // Make request to TMDB
    const response = await fetch(tmdbUrl.toString(), {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    const data = await response.json()

    return new Response(JSON.stringify(data), {
      headers: {
        ...corsHeaders,
        'Content-Type': 'application/json',
        'X-RateLimit-Limit': String(RATE_LIMIT),
        'X-RateLimit-Remaining': String(rateCheck.remaining),
      },
      status: response.status,
    })
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 500,
    })
  }
})
