import { useCallback, useEffect, useMemo, useState } from 'react';
import { API_BASE_URL } from '../components/Match/utils/matchUtils';
import type { MmrData, PlayerIdentifier, PlayerStats, ProfileData } from '../types/player';

const REGION = 'na';

async function request<T>(path: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, { signal });
  if (!response.ok) throw new Error(`Request failed (${response.status})`);

  const payload = await response.json();
  if (typeof payload?.status === 'number' && payload.status >= 400) {
    throw new Error(payload.error || 'The backend rejected the request.');
  }
  return payload?.data ?? payload;
}

function isAbortError(reason: unknown) {
  return reason instanceof DOMException && reason.name === 'AbortError';
}

export function usePlayerData(player: PlayerIdentifier | null, actId: string, mode: string) {
  const [resolvedPlayer, setResolvedPlayer] = useState<PlayerIdentifier | null>(player);
  const [identityLoading, setIdentityLoading] = useState(false);
  const [profile, setProfile] = useState<ProfileData | null>(null);
  const [stats, setStats] = useState<PlayerStats | null>(null);
  const [mmr, setMmr] = useState<MmrData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [rankLoading, setRankLoading] = useState(false);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const refreshPlayerData = useCallback(() => setRefreshVersion((value) => value + 1), []);

  useEffect(() => {
    if (!player) {
      setResolvedPlayer(null);
      return;
    }
    if (player.name && player.tag) {
      setResolvedPlayer(player);
      return;
    }
    if (!player.puuid) return;

    const controller = new AbortController();
    setIdentityLoading(true);
    setError(null);
    request<{name: string; tag: string; puuid: string; region?: string}>(
      `/players/${encodeURIComponent(player.puuid)}`,
      controller.signal
    ).then((identity) => {
      if (!identity?.name || !identity?.tag) throw new Error('No identity is stored for this UUID.');
      setResolvedPlayer({name: identity.name, tag: identity.tag, puuid: player.puuid});
    }).catch((reason) => {
      if (!isAbortError(reason)) {
        console.error('Failed to resolve player UUID', reason);
        setError('We could not resolve this player profile from its UUID.');
      }
    }).finally(() => {
      if (!controller.signal.aborted) setIdentityLoading(false);
    });

    return () => controller.abort();
  }, [player]);

  useEffect(() => {
    setProfile(null);
    setStats(null);
    setMmr(null);
    setUpdatedAt(null);
  }, [player]);

  useEffect(() => {
    if (!resolvedPlayer?.name || !resolvedPlayer.tag) return;
    const interval = window.setInterval(() => setRefreshVersion((value) => value + 1), 5 * 60 * 1000);
    return () => window.clearInterval(interval);
  }, [player]);

  // Account validity is independent from season-specific statistics. A failed
  // stats or MMR request must never turn a valid account into "player not found".
  useEffect(() => {
    if (!resolvedPlayer?.name || !resolvedPlayer.tag) return;
    const controller = new AbortController();
    const name = encodeURIComponent(resolvedPlayer.name);
    const tag = encodeURIComponent(resolvedPlayer.tag);

    setProfileLoading(true);
    setError(null);

    request<ProfileData>(`/account/${name}/${tag}`, controller.signal)
      .then(setProfile)
      .catch((reason) => {
        if (!isAbortError(reason)) {
          console.error('Failed to load player account', reason);
          setError('We could not load this player. Check the Riot ID and try again.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setProfileLoading(false);
      });

    return () => controller.abort();
  }, [resolvedPlayer, refreshVersion]);

  useEffect(() => {
    if (!resolvedPlayer?.name || !resolvedPlayer.tag) return;
    const controller = new AbortController();
    const name = encodeURIComponent(resolvedPlayer.name);
    const tag = encodeURIComponent(resolvedPlayer.tag);
    const season = encodeURIComponent(actId);

    setRankLoading(true);

    Promise.allSettled([
      request<PlayerStats>(`/stats/${REGION}/${name}/${tag}?seasonId=${season}&mode=${encodeURIComponent(mode)}`, controller.signal),
      request<MmrData>(`/mmr/${REGION}/${name}/${tag}?seasonId=${season}`, controller.signal),
    ]).then(([statsResult, mmrResult]) => {
      if (controller.signal.aborted) return;

      if (statsResult.status === 'fulfilled') setStats(statsResult.value);
      else if (!isAbortError(statsResult.reason)) console.error('Failed to load player stats', statsResult.reason);

      if (mmrResult.status === 'fulfilled') setMmr(mmrResult.value);
      else if (!isAbortError(mmrResult.reason)) console.error('Failed to load player MMR', mmrResult.reason);

      setRankLoading(false);
      if (statsResult.status === 'fulfilled' || mmrResult.status === 'fulfilled') setUpdatedAt(new Date());
    });

    return () => controller.abort();
  }, [resolvedPlayer, actId, mode, refreshVersion]);

  const loadState = useMemo<'initial-loading' | 'refreshing' | 'updated'>(() => {
    if ((identityLoading || profileLoading || rankLoading) && !profile && !stats && !mmr) return 'initial-loading';
    if (identityLoading || profileLoading || rankLoading) return 'refreshing';
    return 'updated';
  }, [identityLoading, profileLoading, rankLoading, profile, stats, mmr]);

  return {
    profile,
    stats,
    mmr,
    error,
    profileLoading,
    rankLoading,
    loadState,
    updatedAt,
    resolvedPlayer,
    region: REGION,
    refreshPlayerData,
  };
}
