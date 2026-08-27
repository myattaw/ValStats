import { Search, Target, X } from 'lucide-react';
import { useState, type FormEvent } from 'react';

export function SiteHeader({ compact, onSearch }: { compact: boolean; onSearch: (query: string) => void }) {
  const [query, setQuery] = useState('');
  const [mobileSearch, setMobileSearch] = useState(false);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSearch(query);
  };

  return (
    <header className="site-header">
      <div className="page-shell header-inner">
        <a className="brand" href="/" aria-label="ValStats home">
          <span className="brand-mark"><Target size={20} strokeWidth={2.4} /></span>
          <span>VAL<span>STATS</span></span>
        </a>
        {compact && (
          <form className={`header-search ${mobileSearch ? 'mobile-open' : ''}`} onSubmit={submit}>
            <Search size={17} aria-hidden="true" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Player#Tag" aria-label="Riot ID" />
            <button type="submit" aria-label="Search player"><Search size={15}/><span>Search</span></button>
          </form>
        )}
        {compact && <button className="mobile-header-action mobile-search-action" type="button" aria-label="Search players" aria-expanded={mobileSearch} onClick={() => setMobileSearch((open) => !open)}>{mobileSearch ? <X/> : <Search/>}</button>}
      </div>
    </header>
  );
}
