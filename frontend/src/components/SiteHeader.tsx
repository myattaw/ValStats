import { RecentSearches } from './RecentSearches';
import type { RecentSearchHistory } from '../hooks/useRecentSearches';
import { Search, Target, X } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';

export function SiteHeader({ compact, onSearch, recentSearches }: { compact: boolean; onSearch: (query: string) => boolean; recentSearches: RecentSearchHistory }) {
  const [historyOpen, setHistoryOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [mobileSearch, setMobileSearch] = useState(false);
  useEffect(() => {
    if (mobileSearch) inputRef.current?.focus();
    else setHistoryOpen(false);
  }, [mobileSearch]);
  const select = (value: string) => {
    if (!onSearch(value)) return;
    setQuery('');
    setHistoryOpen(false);
    setMobileSearch(false);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    select(query);
  };

  return (
    <header className="site-header">
      <div className="page-shell header-inner">
        <a className="brand" href="/" aria-label="ValStats home">
          <span className="brand-mark"><Target size={20} strokeWidth={2.4} /></span>
          <span>VAL<span>STATS</span></span>
        </a>
        {compact && (
          <form className={`header-search ${mobileSearch ? 'mobile-open' : ''}`} onSubmit={submit}
            onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setHistoryOpen(false); }}
            onKeyDown={(event) => { if (event.key === 'Escape') { inputRef.current?.focus(); setHistoryOpen(false); } }}>
            <Search size={17} aria-hidden="true" />
            <input ref={inputRef} value={query} onChange={(e) => { setQuery(e.target.value); setHistoryOpen(true); }}
              onFocus={() => setHistoryOpen(true)} onClick={() => setHistoryOpen(true)} autoComplete="off"
              aria-controls={historyOpen ? 'header-recent-searches' : undefined} placeholder="Player#Tag" aria-label="Riot ID" />
            <button type="submit" aria-label="Search player"><Search size={15}/><span>Search</span></button>
            {historyOpen && <div className="header-recent-searches">
              <RecentSearches id="header-recent-searches" history={recentSearches} onSelect={select} query={query} />
            </div>}
          </form>
        )}
        {compact && <button className="mobile-header-action mobile-search-action" type="button" aria-label="Search players" aria-expanded={mobileSearch} onClick={() => setMobileSearch((open) => !open)}>{mobileSearch ? <X/> : <Search/>}</button>}
      </div>
    </header>
  );
}
