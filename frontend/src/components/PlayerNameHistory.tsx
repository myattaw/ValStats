import { useEffect, useRef, useState } from 'react';
import { ChevronDown, X } from 'lucide-react';
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

export function PlayerNameHistory({
  puuid,
  refreshVersion = 0,
  onScanningChange
}: {
  puuid?: string;
  refreshVersion?: number;
  onScanningChange?: (isScanning: boolean) => void;
}) {
  const [names, setNames] = useState<NameObservation[]>([]);
  const [open, setOpen] = useState(false);
  const [isScanning, setIsScanning] = useState(false);
  const historyElement = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutsidePress = (event: PointerEvent) => {
      if (event.target instanceof Node && !historyElement.current?.contains(event.target)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', closeOnOutsidePress);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePress);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  useEffect(() => setOpen(false), [puuid]);

  useEffect(() => {
    onScanningChange?.(isScanning);
  }, [isScanning, onScanningChange]);

  useEffect(() => () => onScanningChange?.(false), [onScanningChange]);

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
    // Name-history sampling is independent of the recent-match refresh. Check
    // as soon as a profile PUUID is available; the backend's daily cooldown and
    // per-match checkpoints prevent duplicate Henrik detail requests.
    if (!puuid) return;
    const controller = new AbortController();
    const baseUrl = `${API_BASE_URL}/players/${encodeURIComponent(puuid)}/names`;

    const refreshNames = async () => {
      try {
        const force = refreshVersion > 0;
        if (!force) {
          const statusResponse = await fetch(`${baseUrl}/refresh-status`, { signal: controller.signal });
          if (!statusResponse.ok) return;
          const status = await statusResponse.json();
          if (status?.data?.refreshRequired !== true) return;
        }

        setIsScanning(true);
        let complete = false;
        while (!complete && !controller.signal.aborted) {
          // Each request processes at most five full-match checkpoints. Keep the
          // UI in the Names state until the backend confirms the entire timeline.
          const refreshResponse = await fetch(`${baseUrl}/refresh${force ? '?force=true' : ''}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{}',
            signal: controller.signal
          });
          if (!refreshResponse.ok || controller.signal.aborted) return;
          const refreshPayload = await refreshResponse.json();
          complete = refreshPayload?.data?.complete === true;
          if (!complete) {
            await new Promise((resolve) => window.setTimeout(resolve, 1000));
          }
        }

        // The scan writes new observations before reporting completion. Bypass
        // browser/CDN caches so this follow-up request always reads those names.
        const namesResponse = await fetch(`${baseUrl}?completedAt=${Date.now()}`, {
          signal: controller.signal,
          cache: 'no-store'
        });
        if (!namesResponse.ok) return;
        const payload = await namesResponse.json();
        if (!controller.signal.aborted) setNames(Array.isArray(payload?.data) ? payload.data : []);
      } catch (reason: any) {
        if (reason?.name !== 'AbortError') console.error('Failed to refresh player name history', reason);
      } finally {
        if (!controller.signal.aborted) setIsScanning(false);
      }
    };

    void refreshNames();
    return () => controller.abort();
  }, [puuid, refreshVersion]);

  const previousNames = names.filter((name) => !name.current);
  if (!previousNames.length) return null;

  const previousTimeline = [...previousNames]
    .sort((a, b) => b.lastSeen - a.lastSeen);

  return (
    <div ref={historyElement} className="name-history">
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
        <div className="name-history-list" role="dialog" aria-label="Previous Riot IDs">
          <div className="name-history-list-header">
            <p className="name-history-heading">This player has also played as:</p>
            <button type="button" className="name-history-close" onClick={() => setOpen(false)} aria-label="Close name history">
              <X />
            </button>
          </div>
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
