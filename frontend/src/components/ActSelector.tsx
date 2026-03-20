import { useEffect, useState } from 'react';
import { Calendar } from 'lucide-react';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from './ui/select';
import { API_BASE_URL } from "./Match/utils/matchUtils";

interface Act {
    value: string;
    label: string;
}

interface ActSelectorProps {
    selectedAct: string;
    onActChange: (act: string) => void;
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
                    `${API_BASE_URL}/acts/${region}/${name}/${tag}`
                );

                if (!res.ok) {
                    throw new Error("Failed to fetch acts");
                }

                const data = await res.json();

// ✅ SORT ACTS HERE
                const sorted = data.sort((a: Act, b: Act) => {
                    const parse = (val: string) => {
                        const match = val.match(/e(\d+)a(\d+)/);
                        return match
                            ? { episode: parseInt(match[1]), act: parseInt(match[2]) }
                            : { episode: 0, act: 0 };
                    };

                    const A = parse(a.value);
                    const B = parse(b.value);

                    // newest first
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

    // Reset to "all" when player changes
    useEffect(() => {
        onActChange('all');
    }, [region, name, tag]);

    return (
        <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 text-gray-400">
                <Calendar className="w-4 h-4"/>
                <span className="text-sm">Act Filter:</span>
            </div>

            <Select value={selectedAct} onValueChange={onActChange}>
                <SelectTrigger className="w-[180px] bg-[#1a1a1a] border-[#2a2a2a] text-white hover:bg-[#242424] focus:ring-[#4a7cff] focus:ring-offset-0">
                    <SelectValue placeholder="Select Act" />
                </SelectTrigger>

                <SelectContent className="bg-[#1a1a1a] border-[#2a2a2a]">
                    {acts.map((act) => (
                        <SelectItem
                            key={act.value}
                            value={act.value}
                            className="text-white hover:bg-[#242424] focus:bg-[#242424] focus:text-white"
                        >
                            {act.label}
                        </SelectItem>
                    ))}
                </SelectContent>
            </Select>
        </div>
    );
}