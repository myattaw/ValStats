import { useEffect, useState } from 'react';
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
}

export function ActSelector({
                                selectedAct,
                                onActChange,
                                region,
                                name,
                                tag
                            }: ActSelectorProps) {

    const [acts, setActs] = useState<Act[]>([
        { value: 'all', label: 'All Acts' }
    ]);

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

                const sorted = data.sort((a: Act, b: Act) => {
                    const parse = (val: string) => {
                        const item = data.find((act) => act.value === val);
                        const match = (item?.seasonKey || val).match(/e(\d+)a(\d+)/i);
                        return match
                            ? { episode: parseInt(match[1]), act: parseInt(match[2]) }
                            : { episode: 0, act: 0 };
                    };

                    const A = parse(a.value);
                    const B = parse(b.value);

                    if (A.episode !== B.episode) {
                        return B.episode - A.episode;
                    }

                    return B.act - A.act;
                });

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

        if (region && name && tag) {
            fetchActs();
        }

        return () => {
            cancelled = true;
        };
    }, [region, name, tag]);

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
