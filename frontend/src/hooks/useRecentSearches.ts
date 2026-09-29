import { useCallback, useEffect, useState } from 'react';
import { addRecentSearch, parseRecentSearches, recentSearchKey, RECENT_SEARCHES_KEY, FAVORITE_SEARCHES_KEY, type RecentSearch } from '../lib/recentSearches';
import { parsePlayerQuery } from '../lib/player';

function readSearches(key: string) {
  try { return parseRecentSearches(window.localStorage.getItem(key), key === FAVORITE_SEARCHES_KEY ? Infinity : undefined); }
  catch { return []; }
}

function useStoredSearches(key: string) {
  const [entries, setEntries] = useState<RecentSearch[]>(() => readSearches(key));
  const update = useCallback((change: (current: RecentSearch[]) => RecentSearch[]) => {
    setEntries((current) => {
      const next = change(current);
      try { window.localStorage.setItem(key, JSON.stringify(next)); }
      catch { /* Saved accounts still work for this session if storage is unavailable. */ }
      return next;
    });
  }, [key]);
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      if (event.key === key || event.key === null) setEntries(readSearches(key));
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, [key]);
  return { entries, update };
}

export function useRecentSearches() {
  const { entries, update } = useStoredSearches(RECENT_SEARCHES_KEY);
  const { entries: favorites, update: updateFavorites } = useStoredSearches(FAVORITE_SEARCHES_KEY);
  const remember = useCallback((player: RecentSearch) => update((current) => addRecentSearch(current, player)), [update]);
  const remove = useCallback((player: RecentSearch) => update((current) =>
    current.filter((entry) => recentSearchKey(entry) !== recentSearchKey(player))), [update]);
  const clear = useCallback(() => update(() => []), [update]);
  const toggleFavorite = useCallback((player: RecentSearch) => {
    const parsed = parsePlayerQuery(player.name + '#' + player.tag);
    if (!parsed) return;
    updateFavorites((current) => current.some((entry) => recentSearchKey(entry) === recentSearchKey(parsed))
      ? current.filter((entry) => recentSearchKey(entry) !== recentSearchKey(parsed))
      : [...current, { name: parsed.name, tag: parsed.tag }]);
  }, [updateFavorites]);
  return { entries, favorites, remember, remove, clear, toggleFavorite };
}

export type RecentSearchHistory = ReturnType<typeof useRecentSearches>;
