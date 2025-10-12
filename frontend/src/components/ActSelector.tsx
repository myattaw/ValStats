import {Calendar} from 'lucide-react';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from './ui/select';

interface ActSelectorProps {
    selectedAct: string;
    onActChange: (act: string) => void;
}

export function ActSelector({selectedAct, onActChange}: ActSelectorProps) {
    const acts = [
        {value: 'all', label: 'All Acts'},
        {value: 'e9a1', label: 'Episode 9: Act 1'},
        {value: 'e8a3', label: 'Episode 8: Act 3'},
        {value: 'e8a2', label: 'Episode 8: Act 2'},
        {value: 'e8a1', label: 'Episode 8: Act 1'},
        {value: 'e7a3', label: 'Episode 7: Act 3'},
        {value: 'e7a2', label: 'Episode 7: Act 2'},
        {value: 'e7a1', label: 'Episode 7: Act 1'},
    ];

    return (
        <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 text-gray-400">
                <Calendar className="w-4 h-4"/>
                <span className="text-sm">Act Filter:</span>
            </div>
            <Select value={selectedAct} onValueChange={onActChange}>
                <SelectTrigger
                    className="w-[180px] bg-[#1a1a1a] border-[#2a2a2a] text-white hover:bg-[#242424] focus:ring-[#4a7cff] focus:ring-offset-0">
                    <SelectValue/>
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
