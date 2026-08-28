import { useEffect, useMemo, useState } from 'react';
import type { MmrData } from '../types/player';
import { Calendar } from 'lucide-react';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "./ui/select";

import { API_BASE_URL } from "./Match/utils/matchUtils";

interface Act {
    value: string;
    label: string;
    seasonKey?: string;
}

interface ActSelectorProps {
    selectedAct: string;
    onActChange: (id: string, label: string, seasonKey?: string) => void;
    region: string;
    name: string;
    tag: string;
    mmr?: MmrData | null;
    mmrLoading?: boolean;
}

function sortActs(acts: Act[]) {
    return [...acts].sort((a, b) => {
        const parse = (act: Act) => {
            const match = (act.seasonKey || act.value).match(/e(\d+)a(\d+)/i);
            return match ? Number(match[1]) * 100 + Number(match[2]) : -1;
        };
        return parse(b) - parse(a);
    });
}

export function ActSelector({
                                selectedAct,
                                onActChange,
                                region,
                                name,
                                tag,
                                mmr,
                                mmrLoading = false
                            }: ActSelectorProps) {

    const [acts, setActs] = useState<Act[]>([
        { value: 'all', label: 'All Acts' }
    ]);

    const mmrActs = useMemo(() => sortActs(
        Object.entries(mmr?.by_season ?? {})
            .filter(([, season]) => !season.error)
            .map(([seasonKey, season]) => ({
                value: season.season_id || seasonKey,
                label: season.season_name || seasonKey.replace(/^e(\d+)a(\d+)$/i, 'Episode $1 Act $2'),
                seasonKey
            }))
    ), [mmr]);

    useEffect(() => {
        if (mmrActs.length > 0) {
            setActs([{ value: 'all', label: 'All Acts' }, ...mmrActs]);
        }
    }, [mmrActs]);

    // Fetch acts dynamically
    useEffect(() => {
        let cancelled = false;

        async function fetchActs() {
            try {
                const res = await fetch(
                    `${API_BASE_URL}/acts/${encodeURIComponent(region)}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`
                );

                if (!res.ok) throw new Error("Failed to fetch acts");

                const payload = await res.json();
                const data: Act[] = (Array.isArray(payload?.data) ? payload.data : Array.isArray(payload) ? payload : []).map((act: Act & { key?: string; code?: string }) => {
                    const searchable = `${act.seasonKey || ''} ${act.key || ''} ${act.code || ''} ${act.value || ''} ${act.label || ''}`;
                    const compactMatch = searchable.match(/e(\d+)a(\d+)/i);
                    const labelMatch = searchable.match(/episode\s*(\d+).*act\s*(\d+)/i);
                    return {
                        value: act.value,
                        label: act.label,
                        seasonKey: act.seasonKey || act.key || act.code || (compactMatch ? `e${compactMatch[1]}a${compactMatch[2]}` : labelMatch ? `e${labelMatch[1]}a${labelMatch[2]}` : undefined),
                    };
                });

                const merged = new Map<string, Act>();
                [...mmrActs, ...data].forEach((act) => merged.set(act.seasonKey || act.value, act));
                const sorted = sortActs([...merged.values()]);

                if (!cancelled) {
                    setActs([
                        { value: 'all', label: 'All Acts' },
                        ...sorted
                    ]);
                }

            } catch (err) {
                console.error("Failed to load acts", err);
            }
        }

        // MMR is the primary, complete source. Only run the expensive cached-
        // match fallback when MMR has finished and supplied no usable seasons.
        if (region && name && tag && !mmrLoading && mmrActs.length === 0) {
            fetchActs();
        }

        return () => {
            cancelled = true;
        };
    }, [region, name, tag, mmrActs, mmrLoading]);

    // Reset to default when player changes
    useEffect(() => {
        onActChange('all', 'All Acts');
    }, [region, name, tag]);

    return (
        <div className="filter-control">
            <span>Act</span>
            <Select
                value={selectedAct}
                onValueChange={(value) => {
                    const selected = acts.find(a => a.value === value);
                    onActChange(value, selected?.label || "All Acts", selected?.seasonKey);
                }}
            >
                <SelectTrigger
                    className="filter-trigger"
                >
                    <Calendar className="w-3.5 h-3.5" />
                    <SelectValue
                        placeholder="Select Act"
                        className="truncate text-sm"
                    />
                </SelectTrigger>

                <SelectContent compact className="bg-[#1a1a1a] border-[#2a2a2a]">
                    {acts.map((act) => (
                        <SelectItem
                            key={act.value}
                            value={act.value}
                            className="text-sm text-white hover:bg-[#242424] focus:bg-[#242424] focus:text-white"
                        >
                            {act.label}
                        </SelectItem>
                    ))}
                </SelectContent>
            </Select>
        </div>
    );
}
