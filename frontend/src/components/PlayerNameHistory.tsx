import { useEffect, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { API_BASE_URL } from './Match/utils/matchUtils';

interface NameObservation {
  name: string;
  tag: string;
  firstSeen: number;
  lastSeen: number;
  observations: number;
  current: boolean;
}

function formatDate(timestamp: number) {
  return timestamp ? new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(timestamp * 1000) : 'Unknown';
}

export function PlayerNameHistory({ puuid }: { puuid?: string }) {
  const [names, setNames] = useState<NameObservation[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!puuid) return;
    const controller = new AbortController();
    const baseUrl = `${API_BASE_URL}/players/${encodeURIComponent(puuid)}/names`;
    let poll: number | undefined;

    const loadCachedNames = async () => {
      const response = await fetch(baseUrl, { signal: controller.signal });
      if (!response.ok) throw new Error('Name history unavailable');
      const payload = await response.json();
      setNames(Array.isArray(payload?.data) ? payload.data : []);
    };

    const run = async () => {
      try {
        await loadCachedNames();
        for (let batch = 0; batch < 10 && !controller.signal.aborted; batch++) {
          const statusResponse = await fetch(`${baseUrl}/refresh-status`, { signal: controller.signal });
          if (!statusResponse.ok) break;
          const status = await statusResponse.json();
          if (status?.data?.refreshRequired !== true) break;

          if (poll === undefined) {
            poll = window.setInterval(() => void loadCachedNames().catch(() => undefined), 2500);
          }
          await fetch(`${baseUrl}/refresh`, { method: 'POST', signal: controller.signal });
          if (!controller.signal.aborted) await loadCachedNames();
        }
        if (poll !== undefined) window.clearInterval(poll);
      } catch (reason: any) {
        if (poll !== undefined) window.clearInterval(poll);
        if (reason?.name !== 'AbortError') console.error('Failed to load player name history', reason);
      }
    };

    void run();
    return () => {
      controller.abort();
      if (poll !== undefined) window.clearInterval(poll);
    };
  }, [puuid]);

  const previousNames = names.filter((name) => !name.current);
  if (!previousNames.length) return null;

  const previousTimeline = [...previousNames]
    .sort((a, b) => b.lastSeen - a.lastSeen);

  return (
    <div className="name-history">
      <button
        className="name-history-toggle"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-label={`Show ${previousNames.length} previous Riot ${previousNames.length === 1 ? 'ID' : 'IDs'}`}
        title={`${previousNames.length} previous Riot ${previousNames.length === 1 ? 'ID' : 'IDs'}`}
      >
        <span className="name-history-count whitespace-nowrap">
          {previousNames.length} Name {previousNames.length === 1 ? 'Change' : 'Changes'}
        </span>
        <ChevronDown className={open ? 'open' : ''} />
      </button>
      {open && (
        <div className="name-history-list">
          <p className="name-history-heading">This player has also played as:</p>
          {previousTimeline.map((entry) => (
            <div key={`${entry.name}#${entry.tag}-${entry.firstSeen}`}>
              <strong>{entry.name}<span>#{entry.tag}</span></strong>
              <small>Last seen {formatDate(entry.lastSeen)}</small>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
