import { useEffect, useState } from 'react';
import { Gamepad2 } from 'lucide-react';
import { API_BASE_URL } from './Match/utils/matchUtils';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './ui/select';

type GameMode = { value: string; label: string };

export function GameModeSelector({ value, onChange, region, name, tag }: {
  value: string;
  onChange: (value: string, label: string) => void;
  region: string;
  name: string;
  tag: string;
}) {
  const [modes, setModes] = useState<GameMode[]>([{ value: 'competitive', label: 'Competitive' }]);

  useEffect(() => {
    if (!region || !name || !tag) return;
    const controller = new AbortController();
    fetch(`${API_BASE_URL}/modes/${encodeURIComponent(region)}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`, { signal: controller.signal })
      .then((response) => response.ok ? response.json() : Promise.reject(new Error('Failed to load modes')))
      .then((data: GameMode[]) => {
        if (Array.isArray(data) && data.length) setModes(data);
      })
      .catch((error) => {
        if (error?.name !== 'AbortError') console.error('Failed to load game modes', error);
      });
    return () => controller.abort();
  }, [region, name, tag]);

  return (
    <div className="filter-control">
      <span>Game mode</span>
      <Select value={value} onValueChange={(next) => {
        const selected = modes.find((mode) => mode.value === next);
        onChange(next, selected?.label ?? next);
      }}>
        <SelectTrigger className="filter-trigger mode-selector"><Gamepad2 size={14}/><SelectValue placeholder="Game mode"/></SelectTrigger>
        <SelectContent compact className="bg-[#1a1a1a] border-[#2a2a2a]">
          {modes.map((mode) => <SelectItem key={mode.value} value={mode.value}>{mode.label}</SelectItem>)}
        </SelectContent>
      </Select>
    </div>
  );
}
