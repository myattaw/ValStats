import { Clock, Star, X } from 'lucide-react';
import type { RecentSearchHistory } from '../hooks/useRecentSearches';
import { recentSearchKey, type RecentSearch } from '../lib/recentSearches';
import '../styles/recent-searches.css';

export function RecentSearches({ history, onSelect, query = '', id }: {
  history: RecentSearchHistory;
  onSelect: (query: string) => void;
  query?: string;
  id?: string;
}) {
  const filter = query.trim().toLowerCase();
  const matches = (entry: RecentSearch) => recentSearchKey(entry).includes(filter);
  const favoriteKeys = new Set(history.favorites.map(recentSearchKey));
  const renderAccount = (entry: RecentSearch, recent: boolean) => {
    const riotId = entry.name + '#' + entry.tag;
    const favorite = favoriteKeys.has(recentSearchKey(entry));
    return <li key={recentSearchKey(entry)}>
      <button type="button" className="recent-searches-account" onClick={() => onSelect(riotId)} aria-label={'View ' + riotId}>
        <span className="recent-searches-name">{entry.name}</span><span className="recent-searches-tag">#{entry.tag}</span>
      </button>
      <button type="button" className="recent-searches-favorite" onClick={() => history.toggleFavorite(entry)}
        aria-pressed={favorite} aria-label={(favorite ? 'Unfavorite ' : 'Favorite ') + riotId} title={favorite ? 'Remove favorite' : 'Add favorite'}>
        <Star size={16} fill={favorite ? 'currentColor' : 'none'} />
      </button>
      {recent && <button type="button" className="recent-searches-remove" onClick={() => history.remove(entry)} aria-label={'Remove ' + riotId + ' from recent searches'}><X size={15} /></button>}
    </li>;
  };
  const favorites = history.favorites.filter(matches);
  const entries = history.entries.filter(matches);
  return <div className="saved-searches" id={id}>
    <section className="recent-searches" aria-label="Favorites">
      <div className="recent-searches-heading"><span><Star size={14} aria-hidden="true" />Favorites</span></div>
      {favorites.length ? <ul>{favorites.map((entry) => renderAccount(entry, false))}</ul>
        : <p className="recent-searches-empty">{history.favorites.length ? 'No favorites match.' : 'Star a recent search to keep it here.'}</p>}
    </section>
    <section className="recent-searches" aria-label="Recent searches">
      <div className="recent-searches-heading"><span><Clock size={14} aria-hidden="true" />Recent searches</span>
        {history.entries.length > 0 && <button type="button" className="recent-searches-clear" onClick={history.clear}>Clear recent</button>}
      </div>
      {entries.length ? <ul>{entries.map((entry) => renderAccount(entry, true))}</ul>
        : <p className="recent-searches-empty">{history.entries.length ? 'No recent searches match.' : 'Accounts you search for will appear here.'}</p>}
    </section>
    <p className="recent-searches-empty">Saved in this browser.</p>
  </div>;
}
