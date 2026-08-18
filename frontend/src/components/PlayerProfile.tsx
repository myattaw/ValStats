import { Check, LoaderCircle, RefreshCw, Shield, Trophy } from 'lucide-react';
import { Skeleton } from './ui/skeleton';
import type { MmrData, ProfileData } from '../types/player';
import { PlayerNameHistory } from './PlayerNameHistory';

const TIER_SET = '03621f52-342b-cf4e-4f86-9350a49c6d04';

function rankIcon(tier?: number) {
  return tier ? `https://media.valorant-api.com/competitivetiers/${TIER_SET}/${tier}/smallicon.png` : undefined;
}

function RankCard({ label, name, icon, loading }: { label: string; name: string; icon?: string; loading: boolean }) {
  return (
    <div className="rank-card">
      <div className="rank-icon">
        {loading ? <Skeleton className="h-10 w-10 rounded-full" /> : icon ? <img src={icon} alt="" /> : <Shield size={22} />}
      </div>
      <div><span>{label}</span><strong>{loading ? 'Loading…' : name}</strong></div>
    </div>
  );
}

export function PlayerProfile({ profile, mmr, seasonKey, actLabel, loading, loadState, updatedAt }: {
  profile: ProfileData | null;
  mmr: MmrData | null;
  seasonKey?: string;
  actLabel: string;
  loading: boolean;
  loadState: 'initial-loading' | 'refreshing' | 'updated';
  updatedAt: Date | null;
}) {
  const season = seasonKey ? mmr?.by_season?.[seasonKey] : undefined;
  const currentTier = season?.final_rank ?? mmr?.current_data?.currenttier;
  const currentName = season?.final_rank_patched ?? mmr?.current_data?.currenttierpatched ?? 'Unranked';
  const peakTier = mmr?.highest_rank?.tier;
  const peakName = mmr?.highest_rank?.patched_tier ?? 'Unranked';
  const seasons = Object.values(mmr?.by_season ?? {});
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
            <PlayerNameHistory puuid={profile?.puuid} />
          </div>
          {profile?.account_level && <p>Account level {profile.account_level}</p>}
        </div>
      </div>
      <div className="rank-grid">
        <RankCard label="Peak rank" name={peakName} icon={rankIcon(peakTier)} loading={loading} />
        <RankCard label={actLabel} name={currentName} icon={season ? rankIcon(currentTier) : mmr?.current_data?.images?.small} loading={loading} />
        <div className="rank-card win-rate">
          <div className="win-ring" style={{ '--progress': `${winRate * 3.6}deg` } as React.CSSProperties}>{winRate}%</div>
          <div><span>Win rate</span><strong>{games ? `${wins}W · ${games - wins}L` : 'No games'}</strong></div>
        </div>
      </div>
    </section>
  );
}
