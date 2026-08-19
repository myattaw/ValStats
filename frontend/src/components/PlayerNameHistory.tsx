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

export function PlayerNameHistory({ puuid, refreshing = false }: { puuid?: string; refreshing?: boolean }) {
  const [names, setNames] = useState<NameObservation[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!puuid) return;
    const controller = new AbortController();
    const baseUrl = `${API_BASE_URL}/players/${encodeURIComponent(puuid)}/names`;

    void fetch(baseUrl, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('Name history unavailable');
        return response.json();
      })
      .then((payload) => setNames(Array.isArray(payload?.data) ? payload.data : []))
      .catch((reason: any) => {
        if (reason?.name !== 'AbortError') console.error('Failed to load player name history', reason);
      });

    return () => controller.abort();
  }, [puuid]);

  useEffect(() => {
    if (!puuid || !refreshing) return;
    const controller = new AbortController();
    const baseUrl = `${API_BASE_URL}/players/${encodeURIComponent(puuid)}/names`;

    const refreshNames = async () => {
      try {
        const statusResponse = await fetch(`${baseUrl}/refresh-status`, { signal: controller.signal });
        if (!statusResponse.ok) return;
        const status = await statusResponse.json();
        if (status?.data?.refreshRequired !== true) return;

        const refreshResponse = await fetch(`${baseUrl}/refresh`, { method: 'POST', signal: controller.signal });
        if (!refreshResponse.ok || controller.signal.aborted) return;

        const namesResponse = await fetch(baseUrl, { signal: controller.signal });
        if (!namesResponse.ok) return;
        const payload = await namesResponse.json();
        if (!controller.signal.aborted) setNames(Array.isArray(payload?.data) ? payload.data : []);
      } catch (reason: any) {
        if (reason?.name !== 'AbortError') console.error('Failed to refresh player name history', reason);
      }
    };

    void refreshNames();
    return () => controller.abort();
  }, [puuid, refreshing]);

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
