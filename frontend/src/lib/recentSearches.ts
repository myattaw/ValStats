import { parsePlayerQuery } from './player';

export type RecentSearch = { name: string; tag: string };
export const RECENT_SEARCHES_KEY = 'valstats.recent-searches.v1';
export const FAVORITE_SEARCHES_KEY = 'valstats.favorite-searches.v1';
export const RECENT_SEARCHES_LIMIT = 10;
export const recentSearchKey = (player: RecentSearch) => `${player.name}#${player.tag}`.toLowerCase();

export function addRecentSearch(entries: RecentSearch[], player: RecentSearch): RecentSearch[] {
  const parsed = parsePlayerQuery(`${player.name}#${player.tag}`);
  if (!parsed) return entries;
  const next = {name: parsed.name, tag: parsed.tag};
  return [next, ...entries.filter((entry) => recentSearchKey(entry) !== recentSearchKey(next))]
    .slice(0, RECENT_SEARCHES_LIMIT);
}

export function parseRecentSearches(value: string | null, limit = RECENT_SEARCHES_LIMIT): RecentSearch[] {
  try {
    const entries: unknown = JSON.parse(value ?? '[]');
    if (!Array.isArray(entries)) return [];
    const result: RecentSearch[] = [];
    const seen = new Set<string>();
    for (const entry of entries) {
      if (!entry || typeof entry.name !== 'string' || typeof entry.tag !== 'string') continue;
      const parsed = parsePlayerQuery(`${entry.name}#${entry.tag}`);
      if (!parsed) continue;
      const key = recentSearchKey(parsed);
      if (seen.has(key)) continue;
      seen.add(key);
      result.push({name: parsed.name, tag: parsed.tag});
      if (result.length === limit) break;
    }
    return result;
  } catch { return []; }
}
