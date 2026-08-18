import { useEffect, useState } from 'react';
import { AlertCircle } from 'lucide-react';
import { ActSelector } from './components/ActSelector';
import { GameModeSelector } from './components/GameModeSelector';
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
  const [act, setAct] = useState<{ id: string; label: string; seasonKey?: string }>({ id: 'all', label: 'Current act' });
  const [mode, setMode] = useState({ id: 'competitive', label: 'Competitive' });
  const [inputError, setInputError] = useState<string | null>(null);
  const { profile, stats, mmr, error, rankLoading, loadState, updatedAt, resolvedPlayer, region } = usePlayerData(player, act.id, mode.id);
  const activePlayer = resolvedPlayer ?? player;

  useEffect(() => {
    const syncFromHistory = () => setPlayer(parsePlayerFromUrl());
    window.addEventListener('popstate', syncFromHistory);
    return () => window.removeEventListener('popstate', syncFromHistory);
  }, []);

  useEffect(() => {
    if (!player?.puuid || !resolvedPlayer?.name || !resolvedPlayer.tag) return;
    const canonicalPath = playerPath(resolvedPlayer);
    if (window.location.pathname !== canonicalPath) {
      window.history.replaceState(null, '', canonicalPath);
    }
  }, [player?.puuid, resolvedPlayer]);

  const search = (query: string) => {
    const next = parsePlayerQuery(query);
    if (!next) {
      setInputError('Enter a complete Riot ID in the format Player#Tag.');
      return;
    }
    setInputError(null);
    setAct({ id: 'all', label: 'Current act' });
    setMode({ id: 'competitive', label: 'Competitive' });
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
            <PlayerProfile profile={profile} mmr={mmr} seasonKey={act.seasonKey} actLabel={act.label} loading={loadState === 'initial-loading'} loadState={loadState} updatedAt={updatedAt} />
            <section className="overview-panel">
              <div className="section-toolbar">
                <div><span className="eyebrow">Performance</span><h2>{mode.label} overview</h2></div>
                <div className="history-filters">
                  <GameModeSelector value={mode.id} onChange={(id, label) => setMode({ id, label })} region={region} name={activePlayer?.name ?? ''} tag={activePlayer?.tag ?? ''}/>
                  <ActSelector selectedAct={act.id} onActChange={(id, label, seasonKey) => setAct({ id, label, seasonKey })} region={region} name={activePlayer?.name ?? ''} tag={activePlayer?.tag ?? ''} />
                </div>
              </div>
              <StatsOverview stats={stats} loading={rankLoading} />
            </section>
            {activePlayer?.name && activePlayer.tag && <MatchHistory puuid={profile?.puuid ?? player.puuid} playerName={activePlayer.name} playerTag={activePlayer.tag} selectedAct={act.id} selectedMode={mode.id} />}
          </div>
        )}
      </main>
      <footer className="page-shell footer">VALSTATS <span>·</span> Unofficial Valorant statistics</footer>
    </div>
  );
}
