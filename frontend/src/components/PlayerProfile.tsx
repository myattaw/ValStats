import { Check, LoaderCircle, RefreshCw, Shield, Trophy } from 'lucide-react';
import { Skeleton } from './ui/skeleton';
import type { MmrData, ProfileData } from '../types/player';
import { PlayerNameHistory } from './PlayerNameHistory';

const TIER_SET = '03621f52-342b-cf4e-4f86-9350a49c6d04';

function rankIcon(tier?: number) {
  return tier ? `https://media.valorant-api.com/competitivetiers/${TIER_SET}/${tier}/smallicon.png` : undefined;
}

function RankCard({ label, name, icon, loading, rr }: { label: string; name: string; icon?: string; loading: boolean; rr?: number }) {
  const showProgress = rr !== undefined && rr >= 0 && !/^(Immortal|Radiant)/i.test(name);
  return (
    <div className="rank-card compact-rank-card">
      <div className="rank-icon">
        {loading ? <Skeleton className="h-10 w-10 rounded-full" /> : icon ? <img src={icon} alt="" /> : <Shield size={22} />}
      </div>
      <div className="rank-copy">
        <span>{label}</span><strong>{loading ? 'Loading…' : name}</strong>
        {!loading && rr !== undefined && <small>{rr} RR{showProgress ? ` · ${Math.max(0, 100 - rr)} to rank up` : ''}</small>}
        {!loading && showProgress && <div className="rr-progress" aria-label={`${rr} out of 100 rank rating`}><i style={{ width: `${Math.min(rr, 100)}%` }} /></div>}
      </div>
    </div>
  );
}

function rankOrder(name?: string) {
  const ranks = ['Iron 1','Iron 2','Iron 3','Bronze 1','Bronze 2','Bronze 3','Silver 1','Silver 2','Silver 3','Gold 1','Gold 2','Gold 3','Platinum 1','Platinum 2','Platinum 3','Diamond 1','Diamond 2','Diamond 3','Ascendant 1','Ascendant 2','Ascendant 3','Immortal 1','Immortal 2','Immortal 3','Radiant'];
  return name ? ranks.indexOf(name) : -1;
}

function rankTriangleIcon(name: string, direction: 'up' | 'down') {
  const tier = rankOrder(name) + 3;
  if (tier < 3) return undefined;
  return `https://media.valorant-api.com/competitivetiers/${TIER_SET}/${tier}/ranktriangle${direction}icon.png`;
}

function rankLargeIcon(name: string) {
  const tier = rankOrder(name) + 3;
  return tier >= 3
    ? `https://media.valorant-api.com/competitivetiers/${TIER_SET}/${tier}/largeicon.png`
    : undefined;
}

function ActRankCard({ label, season, loading }: { label: string; season?: import('../types/player').SeasonRank; loading: boolean }) {
  const wins = [...(season?.act_rank_wins ?? [])]
    .filter((entry) => entry.patched_tier && entry.patched_tier !== 'Unrated')
    .sort((a, b) => rankOrder(b.patched_tier) - rankOrder(a.patched_tier))
    .slice(0, 9);
  // Build partial act ranks from the bottom upward so every marker is
  // supported by a complete, symmetric row instead of collapsing vertically.
  const triangleSlots = [
    { left: 34, top: 44, direction: 'up' as const },
    { left: 22, top: 44, direction: 'down' as const },
    { left: 46, top: 44, direction: 'down' as const },
    { left: 10, top: 44, direction: 'up' as const },
    { left: 58, top: 44, direction: 'up' as const },
    { left: 34, top: 22, direction: 'down' as const },
    { left: 22, top: 22, direction: 'up' as const },
    { left: 46, top: 22, direction: 'up' as const },
    { left: 34, top: 0, direction: 'up' as const },
  ];
  const peak = wins[0]?.patched_tier ?? season?.final_rank_patched ?? 'Unranked';

  return (
    <div className="rank-card act-rank-card">
      <div className="act-rank-triangle" aria-label={`${peak} act rank with ${season?.wins ?? 0} wins`}>
        {loading ? <Skeleton className="h-11 w-16" /> : wins.length ? wins.map((win, index) => {
          const slot = triangleSlots[index];
          return (
            <img
              className="act-rank-win"
              key={`${win.patched_tier}-${index}`}
              src={rankTriangleIcon(win.patched_tier, slot.direction)}
              style={{ left: slot.left, top: slot.top }}
              alt=""
              title={win.patched_tier}
              onError={(event) => { event.currentTarget.style.display = 'none'; }}
            />
          );
        }) : <Shield size={22} />}
        {!loading && wins.length > 0 && (
          <img
            className="act-rank-center-icon"
            src={rankLargeIcon(peak)}
            alt={`${peak} rank icon`}
            onError={(event) => { event.currentTarget.style.display = 'none'; }}
          />
        )}
      </div>
      <div><span>{label} act rank</span><strong>{loading ? 'Loading…' : peak}</strong><small>{season?.wins ? `${season.wins} total wins` : 'No ranked wins'}</small></div>
    </div>
  );
}

function seasonOrder(key: string) {
  const match = /^e(\d+)a(\d+)$/i.exec(key);
  return match ? Number(match[1]) * 100 + Number(match[2]) : -1;
}

function seasonPeakOrder(season: import('../types/player').SeasonRank) {
  return Math.max(
    rankOrder(season.final_rank_patched),
    ...(season.act_rank_wins ?? []).map((win) => rankOrder(win.patched_tier))
  );
}

export function PlayerProfile({ profile, mmr, seasonKey, actLabel, loading, loadState, updatedAt, nameHistoryRefreshVersion }: {
  profile: ProfileData | null;
  mmr: MmrData | null;
  seasonKey?: string;
  actLabel: string;
  loading: boolean;
  loadState: 'initial-loading' | 'refreshing' | 'updated';
  updatedAt: Date | null;
  nameHistoryRefreshVersion: number;
}) {
  const validSeasonEntries = Object.entries(mmr?.by_season ?? {})
    .filter(([, value]) => !value.error)
    .sort(([a], [b]) => seasonOrder(b) - seasonOrder(a));
  const requestedSeason = seasonKey ? mmr?.by_season?.[seasonKey] : undefined;
  const season = requestedSeason && !requestedSeason.error ? requestedSeason : undefined;
  const peakActSeason = validSeasonEntries.reduce<import('../types/player').SeasonRank | undefined>(
    (best, [, candidate]) => !best || seasonPeakOrder(candidate) > seasonPeakOrder(best) ? candidate : best,
    undefined
  );
  const currentTier = season?.final_rank ?? mmr?.current_data?.currenttier;
  const currentName = season?.final_rank_patched ?? mmr?.current_data?.currenttierpatched ?? 'Unranked';
  const seasons = validSeasonEntries.map(([, value]) => value);
  const games = season ? season.number_of_games : seasons.reduce((sum, item) => sum + (item.number_of_games || 0), 0);
  const wins = season ? season.wins : seasons.reduce((sum, item) => sum + (item.wins || 0), 0);
  const winRate = games ? Math.round((wins / games) * 100) : 0;

  return (
    <section className="profile-panel">
      <div className="profile-identity">
        <div className="player-card">
          {loading ? <Skeleton className="h-full w-full" /> : profile?.card?.small ? <img src={profile.card.small} alt="Player card" /> : <Trophy />}
        </div>
        <div>
          <div className="profile-status-row">
            <span className="eyebrow">Competitive profile</span>
            <span className={`load-status ${loadState}`} title={updatedAt ? `Last updated ${updatedAt.toLocaleTimeString()}` : undefined} aria-live="polite">
              {loadState === 'initial-loading' ? <LoaderCircle /> : loadState === 'refreshing' ? <RefreshCw /> : <Check />}
              {loadState === 'initial-loading' ? 'Loading account' : loadState === 'refreshing' ? 'Refreshing' : 'Updated'}
            </span>
          </div>
          <div className="profile-name-row">
            <h1>{profile?.name ?? 'Loading player'}<span>#{profile?.tag ?? ''}</span></h1>
            <PlayerNameHistory puuid={profile?.puuid} refreshVersion={nameHistoryRefreshVersion} />
          </div>
          {profile?.account_level && <p>Account level {profile.account_level}</p>}
        </div>
      </div>
      <div className="rank-grid">
        <ActRankCard label="Peak" season={peakActSeason} loading={loading} />
        <RankCard label={actLabel} name={currentName} icon={season ? rankIcon(currentTier) : mmr?.current_data?.images?.small} loading={loading} rr={seasonKey ? undefined : mmr?.current_data?.ranking_in_tier} />
        <div className="rank-card compact-rank-card win-rate">
          <div className="win-ring" style={{ '--progress': `${winRate * 3.6}deg` } as React.CSSProperties}>{winRate}%</div>
          <div><span>Win rate</span><strong>{games ? `${wins}W · ${games - wins}L` : 'No games'}</strong></div>
        </div>
      </div>
    </section>
  );
}
