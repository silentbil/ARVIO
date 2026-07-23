// Direct port of app/.../telegram/TelegramSearchMatcher.kt — the query builder
// and relevance scorer that decide which Telegram video files match a title.
// Kept byte-for-byte compatible so the web results rank like the Android app's.

const SEP = "[\\s._\\-x+,&:]{0,2}";
const SEP_MID = "[\\s._\\-x+,&:]{0,4}";

const EPISODE_PATTERN = new RegExp(
  `[Ss][e]?(?:ason)?${SEP}(\\d{1,2})${SEP_MID}[Ee][p]?(?:isode)?${SEP}(\\d{1,4})` +
    `|ע(?:ונה)?${SEP}(\\d{1,2})${SEP_MID}פ(?:רק)?${SEP}(\\d{1,4})`,
  "i"
);
// Season-1 fallback: episode-only Hebrew marker (פרק 5 / פ5) with no season prefix
const EPISODE_ONLY_PATTERN = /פ(?:רק)?[\s._\-x+,&:]{0,2}(\d{1,4})/;
const YEAR_PATTERN = /\b(19|20)\d{2}\b/g;
const NOISE = /[._\-\[\]()'",!?:]/g;
const MULTI_SPACE = /\s+/g;
const SIZE_SUFFIX = /\.(mkv|mp4|avi|mov|wmv|m4v|ts|m2ts)$/i;
const HEBREW_MIN = 0x0590;
const HEBREW_MAX = 0x05ff;

export function score(params: {
  fileName: string;
  caption: string;
  title: string;
  localizedTitle?: string | null;
  englishTitle?: string | null;
  year?: number | null;
  season?: number | null;
  episode?: number | null;
}): number {
  const { fileName, caption, title, localizedTitle, englishTitle, year, season, episode } = params;
  const combined = `${fileName} ${caption}`;
  const normalizedCombined = normalize(combined);
  const normalizedTitle = normalize(title);
  const normalizedLocalized = localizedTitle ? normalize(localizedTitle) : null;
  const normalizedEnglish = englishTitle ? normalize(englishTitle) : null;

  const engMatch = !!normalizedEnglish && normalizedEnglish.length > 0 && normalizedCombined.includes(normalizedEnglish);
  const locMatch = !!normalizedLocalized && normalizedLocalized.length > 0 && normalizedCombined.includes(normalizedLocalized);
  const appMatch = normalizedCombined.includes(normalizedTitle);

  if (!engMatch && !locMatch && !appMatch) return 0;

  let s = 60;

  if (year != null) {
    const fileYears = Array.from(combined.matchAll(YEAR_PATTERN)).map((m) => Number(m[0]));
    if (fileYears.includes(year)) s += 20;
    else if (fileYears.some((y) => Math.abs(y - year) === 1)) s += 5;
    else if (fileYears.length === 0) s += 5;
    else s -= 10;
  }

  if (season != null && episode != null) {
    const seFile = extractSeasonEpisode(fileName);
    const seCaption = extractSeasonEpisode(caption);
    const rightSE =
      (seFile?.[0] === season && seFile?.[1] === episode) ||
      (seCaption?.[0] === season && seCaption?.[1] === episode);
    if (rightSE) {
      s += 20;
    } else if (seFile != null || seCaption != null) {
      return 0; // pattern found but wrong S/E
    } else if (season === 1) {
      const epFile = extractEpisodeOnly(fileName);
      const epCaption = extractEpisodeOnly(caption);
      if (epFile === episode || epCaption === episode) s += 20;
      else return 0; // no identifiable episode marker → not this episode
    } else {
      // Episode search but the file carries no season/episode marker at all
      // (a movie, a season pack, a special). On web we retrieve by bare title,
      // so — unlike Android's filename-targeted search — these show up in bulk;
      // reject them instead of letting a strong title match pass at threshold.
      return 0;
    }
  } else if (season == null) {
    if (EPISODE_PATTERN.test(combined) || EPISODE_PATTERN.test(normalizedCombined)) {
      s -= 20;
    }
  }

  return Math.max(0, Math.min(100, s));
}

function extractSeasonEpisode(text: string): [number, number] | null {
  const m = EPISODE_PATTERN.exec(text) ?? EPISODE_PATTERN.exec(normalize(text));
  if (!m) return null;
  const sVal = toInt(m[1]) ?? toInt(m[3]);
  const eVal = toInt(m[2]) ?? toInt(m[4]);
  if (sVal == null || eVal == null) return null;
  return [sVal, eVal];
}

function extractEpisodeOnly(text: string): number | null {
  const m = EPISODE_ONLY_PATTERN.exec(text) ?? EPISODE_ONLY_PATTERN.exec(normalize(text));
  return m ? toInt(m[1]) : null;
}

export function buildMovieQueries(
  title: string,
  year?: number | null,
  localizedTitle?: string | null,
  englishTitle?: string | null
): string[] {
  const primary = englishTitle ? cleanTitle(englishTitle) : cleanTitle(title);
  const localized = localizedTitle ? cleanTitle(localizedTitle) : null;
  const queries: string[] = [];
  if (year != null) queries.push(`${primary} ${year}`);
  queries.push(primary);
  if (localized && localized.toLowerCase() !== primary.toLowerCase()) {
    if (year != null) queries.push(`${localized} ${year}`);
    queries.push(localized);
  }
  return distinct(queries);
}

export function buildSeriesQueries(
  title: string,
  season: number,
  episode: number,
  localizedTitle?: string | null,
  englishTitle?: string | null,
  languageCode = "en"
): string[] {
  const engBase = englishTitle ? cleanTitle(englishTitle) : cleanTitle(title);
  const locBase = localizedTitle ? cleanTitle(localizedTitle) : null;
  const titlesAreSame = locBase == null || locBase.toLowerCase() === engBase.toLowerCase();
  const s = String(season);
  const e = String(episode);
  const s2 = String(season).padStart(2, "0");
  const e2 = String(episode).padStart(2, "0");

  const queries: string[] = [];

  if (languageCode === "he") {
    const hebTitle = titlesAreSame ? engBase : locBase ?? engBase;
    queries.push(`${hebTitle} ע${s} פ${e}`, `${hebTitle} ע${s}פ${e}`, `${hebTitle} עונה ${s} פרק ${e}`);
    if (season === 1) queries.push(`${hebTitle} פ${e}`, `${hebTitle} פרק ${e}`);
  }

  if (!titlesAreSame && locBase) {
    queries.push(`${locBase} s${s}e${e}`, `${locBase} s${s2}e${e2}`, `${locBase} s${s} e${e}`, `${locBase} s${s2} e${e2}`);
  }

  queries.push(`${engBase} s${s}e${e}`, `${engBase} s${s2}e${e2}`, `${engBase} s${s} e${e}`, `${engBase} s${s2} e${e2}`);

  // Bare-title queries. Telegram's browser search (messages.SearchGlobal) matches
  // caption/text tokens, so exact "sXXeXX" phrases often miss files whose season/
  // episode only lives in the filename. Retrieve by title and let score() confirm
  // the S/E from the filename (it already requires the exact S/E and rejects
  // wrong ones). This mirrors how the movie path already searches.
  queries.push(engBase);
  if (!titlesAreSame && locBase) queries.push(locBase);

  return distinct(queries.map((q) => q.toLowerCase()));
}

export function isHebrew(str: string): boolean {
  for (const ch of str) {
    const code = ch.codePointAt(0) ?? 0;
    if (code >= HEBREW_MIN && code <= HEBREW_MAX) return true;
  }
  return false;
}

function cleanTitle(title: string): string {
  const stripped = title.replace(/:/g, "").replace(/ {2}/g, " ").trim();
  return stripped.normalize("NFKD").replace(/\p{Mn}+/gu, "");
}

function normalize(text: string): string {
  return text
    .replace(SIZE_SUFFIX, "")
    .replace(NOISE, " ")
    .replace(MULTI_SPACE, " ")
    .trim()
    .toLowerCase();
}

function toInt(v: string | undefined): number | null {
  if (v == null || v === "") return null;
  const n = Number.parseInt(v, 10);
  return Number.isNaN(n) ? null : n;
}

function distinct(arr: string[]): string[] {
  return Array.from(new Set(arr));
}
