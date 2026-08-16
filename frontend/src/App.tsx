import { useEffect, useState } from 'react';
import { AlertCircle } from 'lucide-react';
import { ActSelector } from './components/ActSelector';
import { MatchHistory } from './components/MatchHistory';
import { PlayerProfile } from './components/PlayerProfile';
import { PlayerSearch } from './components/PlayerSearch';
import { SiteHeader } from './components/SiteHeader';
import { StatsOverview } from './components/StatsOverview';
import { usePlayerData } from './hooks/usePlayerData';
import { parsePlayerFromUrl, parsePlayerQuery, playerPath } from './lib/player';
import type { PlayerIdentifier } from './types/player';

export default function App() {
  const [player, setPlayer] = useState<PlayerIdentifier | null>(() => parsePlayerFromUrl());
  const [act, setAct] = useState({ id: 'all', label: 'Current act' });
  const [inputError, setInputError] = useState<string | null>(null);
  const { profile, stats, mmr, error, profileLoading, rankLoading, region } = usePlayerData(player, act.id);

  useEffect(() => {
    const syncFromHistory = () => setPlayer(parsePlayerFromUrl());
    window.addEventListener('popstate', syncFromHistory);
    return () => window.removeEventListener('popstate', syncFromHistory);
  }, []);

  const search = (query: string) => {
    const next = parsePlayerQuery(query);
    if (!next) {
      setInputError('Enter a complete Riot ID in the format Player#Tag.');
      return;
    }
    setInputError(null);
    setAct({ id: 'all', label: 'Current act' });
    setPlayer(next);
    window.history.pushState(null, '', playerPath(next));
  };

  return (
    <div className="app-frame">
      <SiteHeader compact={Boolean(player)} onSearch={search} />
      <main className="page-shell main-content">
        {!player ? (
          <PlayerSearch onSearch={search} error={inputError} />
        ) : (
          <div className="dashboard">
            {(inputError || error) && <div className="error-banner"><AlertCircle size={18} />{inputError || error}</div>}
            <PlayerProfile profile={profile} mmr={mmr} actId={act.id} actLabel={act.label} loading={profileLoading || rankLoading} />
            <div className="section-toolbar">
              <div><span className="eyebrow">Performance</span><h2>Competitive overview</h2></div>
              <ActSelector selectedAct={act.id} onActChange={(id, label) => setAct({ id, label })} region={region} name={player.name} tag={player.tag} />
            </div>
            <StatsOverview stats={stats} loading={rankLoading} />
            <MatchHistory puuid={profile?.puuid} playerName={player.name} playerTag={player.tag} selectedAct={act.id} />
          </div>
        )}
      </main>
      <footer className="page-shell footer">VALSTATS <span>·</span> Unofficial Valorant statistics</footer>
    </div>
  );
}
