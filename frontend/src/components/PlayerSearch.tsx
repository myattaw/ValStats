import { useState, type FormEvent } from 'react';
import { ArrowRight, Search, ShieldCheck } from 'lucide-react';

export function PlayerSearch({ onSearch, error }: { onSearch: (query: string) => void; error?: string | null }) {
  const [query, setQuery] = useState('');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSearch(query);
  };

  return (
    <section className="hero">
      <div className="hero-glow" />
      <div className="hero-copy">
        <div className="hero-kicker"><ShieldCheck size={15} /> Fast, focused, match-ready</div>
        <h1>Know your game.<br /><span>Own the next one.</span></h1>
        <p>Competitive stats, rank progression, and match history in one focused dashboard.</p>
        <form className="search-card" onSubmit={submit}>
          <label htmlFor="riot-id">Find a Valorant player</label>
          <div className="search-input">
            <span className="search-input-icon" aria-hidden="true"><Search size={18} /></span>
            <input id="riot-id" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Player#Tag" autoComplete="off" />
            <button type="submit"><span>View stats</span><ArrowRight size={18} /></button>
          </div>
          <div className="search-helper">Include the full Riot ID, including the tag.</div>
          {error && <p className="form-error" role="alert">{error}</p>}
        </form>
      </div>
      <div className="hero-visual" aria-hidden="true">
        <div className="radar"><div /><div /><div /><span /></div>
        <div className="visual-stat visual-stat-one"><span>K/D</span><strong>1.38</strong></div>
        <div className="visual-stat visual-stat-two"><span>HS%</span><strong>28.4</strong></div>
      </div>
    </section>
  );
}
