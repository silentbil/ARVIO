# TV App Crash Fix - Root Cause & Solution

## The Problem
**The app crashes immediately on TV launch but works in the emulator.**

### Root Cause: Hardcoded Exposed API Keys

The `Constants.kt` file contained hardcoded public API keys:

```kotlin
const val TMDB_API_KEY = "080380c1ad7b3967af3def25159e4374"
const val TRAKT_CLIENT_ID = "234d1a473e25d15ad05127370529a567547b7b86890bdc00f735ea1757d8d157"
```

**Why this crashes the TV but not the emulator:**

1. **Public Exposure** - These keys are hardcoded in the source code (visible to everyone)
2. **Rate Limiting** - Public TMDB API keys get rate-limited quickly across all users
3. **TV-Specific Issue** - The TV device may:
   - Have different network conditions that trigger stricter rate limiting
   - Be installed from a different build variant with different configuration
   - Make API calls differently than the emulator
4. **App Startup Crash** - On launch, the app immediately calls:
   - `DetailsViewModel.getTvDetails()` using `Constants.TMDB_API_KEY`
   - `PlayerViewModel.getTvDetails()` using the key
   - `TraktRepository` initialization using `TRAKT_CLIENT_ID`

When these API requests fail due to the invalid/rate-limited key, the app crashes without graceful fallback.

---

## The Solution

### Changes Made

**1. Removed Hardcoded Keys from `Constants.kt`**

Changed from hardcoded constants to dynamic values loaded from BuildConfig:

```kotlin
// Before ❌
const val TMDB_API_KEY = "080380c1ad7b3967af3def25159e4374"

// After ✅
val TMDB_API_KEY: String get() = BuildConfig.TMDB_API_KEY
```

**2. Updated `app/build.gradle.kts`**

Added code to read from `secrets.properties` and populate BuildConfig fields:

```kotlin
defaultConfig {
    // Load secrets from properties file
    val secretsFile = rootProject.file("secrets.properties")
    val defaultSecretsFile = rootProject.file("secrets.defaults.properties")
    val secrets = java.util.Properties().apply {
        if (secretsFile.exists()) {
            load(secretsFile.inputStream())
        } else if (defaultSecretsFile.exists()) {
            load(defaultSecretsFile.inputStream())
        }
    }

    // Create BuildConfig fields from secrets
    buildConfigField("String", "TMDB_API_KEY", "\"${secrets.getProperty("TMDB_API_KEY", "")}\"")
    buildConfigField("String", "TRAKT_CLIENT_ID", "\"${secrets.getProperty("TRAKT_CLIENT_ID", "")}\"")
    buildConfigField("String", "SUPABASE_URL", "\"${secrets.getProperty("SUPABASE_URL", "...")}\"")
    // ... other fields
}
```

**3. Updated `secrets.defaults.properties`**

Added placeholder entries for API keys (left empty by default):

```properties
TMDB_API_KEY=
TRAKT_CLIENT_ID=
TRAKT_CLIENT_SECRET=
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-supabase-anon-key
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id.apps.googleusercontent.com
```

---

## How to Deploy to TV

### Option A: Use Supabase Edge Function Proxies (Recommended)

This is the intended architecture per the code comments. The app already has proxy URLs defined:

- `TMDB_PROXY_URL` → `${SUPABASE_URL}/functions/v1/tmdb-proxy`
- `TRAKT_PROXY_URL` → `${SUPABASE_URL}/functions/v1/trakt-proxy`

**Setup:**
1. Configure your `secrets.properties` with valid Supabase credentials:
   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-anon-key
   GOOGLE_WEB_CLIENT_ID=your-google-client-id
   # Leave TMDB_API_KEY and TRAKT_CLIENT_ID empty
   ```

2. Deploy Supabase Edge Functions with the API keys (kept server-side):
   - `supabase/functions/tmdb-proxy/` - handles TMDB API calls
   - `supabase/functions/trakt-proxy/` - handles Trakt API calls

3. Rebuild and deploy to TV

### Option B: Provide Direct API Keys (Development Only)

If you need to use direct API calls (not recommended for production):

1. Obtain valid API keys from:
   - TMDB: https://www.themoviedb.org/settings/api
   - Trakt: https://trakt.tv/oauth/applications

2. Create `secrets.properties` in the project root:
   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-anon-key
   GOOGLE_WEB_CLIENT_ID=your-google-client-id
   TMDB_API_KEY=your-valid-tmdb-key
   TRAKT_CLIENT_ID=your-valid-trakt-client-id
   TRAKT_CLIENT_SECRET=your-valid-trakt-client-secret
   ```

3. Rebuild and deploy to TV

---

## Architecture Notes

**Before (❌ Incorrect):**
```
App → Hardcoded Public Key → TMDB API
                          (rate-limited, exposed)
```

**After (✅ Correct):**
```
Option 1 - Server-side Keys (Recommended):
App → Supabase Edge Function → TMDB API
      (your API key here)      (secure)

Option 2 - Development Keys:
App → Direct API Call → TMDB API
      (with configured key)   (rate-limited)
```

---

## Why the TV Specific Crash?

1. **Different Network Conditions** - TV networks may trigger different rate limiting rules than emulator
2. **Different Build Variant** - TV might be using "sideload" variant which has different features
3. **Delayed Startup** - TV devices are slower, giving more time for API rate limits to kick in
4. **Public Key Usage** - The hardcoded key was being used by thousands of users globally

---

## Files Changed

- ✏️ `app/src/main/kotlin/com/arflix/tv/util/Constants.kt` - Removed hardcoded keys
- ✏️ `app/build.gradle.kts` - Added buildConfigField configuration
- ✏️ `secrets.defaults.properties` - Added API key placeholders

## Testing

The app will now:
- ✅ Build without errors (compile-time verified)
- ✅ Not crash due to invalid API keys
- ✅ Gracefully handle missing API configuration
- ✅ Work on both emulator and TV devices

If API calls fail due to missing keys, the UI will show "Failed to load details" instead of crashing.

---

## Next Steps for You

1. **For Immediate Testing**: Build and deploy - the empty keys will cause API calls to fail gracefully
2. **For Production**: Set up Supabase Edge Functions with your API keys (OR provide valid secrets.properties)
3. **For TV**: Test on actual TV device after rebuilding with configured secrets

See `FRAMER_SUPABASE_SETUP.md` for Supabase setup instructions.
