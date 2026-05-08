# Fix: Watchlist DirectionDown focus trap

## Issue
On the Watchlist screen, navigating Down from the sidebar to the grid cards works the first time, but after moving back Up to the sidebar, pressing Down again fails to return focus to the grid — focus gets stuck on the sidebar.

## Root Cause
The `Key.DirectionDown` handler in `WatchlistScreen.kt` used a coroutine-based approach to delegate focus from the sidebar to the grid:

```kotlin
scope.launch {
    runCatching { gridFocusRequester.requestFocus() }
    delay(50)
    if (!gridHasFocus) {
        runCatching { gridFocusRequester.requestFocus() }
        delay(80)
    }
    if (!gridHasFocus) {
        isSidebarFocused = true  // fallback: bounces back to sidebar
        runCatching { rootFocusRequester.requestFocus() }
    }
}
```

This introduced a race condition where `gridHasFocus` (set by Compose's async `onFocusChanged` callback) was stale when checked inside the coroutine. On the second DirectionDown attempt, the fallback logic could incorrectly set `isSidebarFocused = true`, bouncing focus back to the sidebar even though the grid successfully received focus.

## Fix
Replaced the coroutine-based approach with a direct synchronous `gridFocusRequester.requestFocus()` call — matching the pattern used by `SearchScreen` and `HomeScreen` which handle sidebar-to-content focus transitions correctly.

## Files Changed
- `app/src/main/kotlin/com/arflix/tv/ui/screens/watchlist/WatchlistScreen.kt` — Replaced coroutine + delay + fallback in DirectionDown handler with direct `requestFocus()` call.
