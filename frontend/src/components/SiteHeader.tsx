import { Search, Target } from 'lucide-react';
import { useState, type FormEvent } from 'react';

export function SiteHeader({ compact, onSearch }: { compact: boolean; onSearch: (query: string) => void }) {
  const [query, setQuery] = useState('');
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
          <form className="header-search" onSubmit={submit}>
            <Search size={17} aria-hidden="true" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Player#Tag" aria-label="Riot ID" />
            <button type="submit">Search</button>
          </form>
        )}
      </div>
    </header>
  );
}
