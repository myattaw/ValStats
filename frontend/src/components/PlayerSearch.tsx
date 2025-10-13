import * as React from 'react';
import {useState} from 'react';
import {Search, X} from 'lucide-react';

interface PlayerSearchProps {
    onSearch: (query: string) => void;
}

export function PlayerSearch({onSearch}: PlayerSearchProps) {
    const [query, setQuery] = useState('');

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        onSearch(query);
    };

    const clearSearch = () => {
        setQuery('');
    };

    return (
        <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-8">
            <div className="max-w-2xl mx-auto">
                <h2 className="text-white text-center mb-6">Search Player Stats</h2>
                <form onSubmit={handleSubmit} className="relative">
                    <div className="relative">
                        <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400"/>
                        <input
                            type="text"
                            value={query}
                            onChange={(e) => setQuery(e.target.value)}
                            placeholder="Search by player name or tag (e.g., TenZ#NA1)"
                            className="w-full bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg pl-12 pr-12 py-4 text-white placeholder-gray-500 focus:outline-none focus:border-[#4a7cff] transition-colors"
                        />
                        {query && (
                            <button
                                type="button"
                                onClick={clearSearch}
                                className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 hover:text-white transition-colors"
                            >
                                <X className="w-5 h-5"/>
                            </button>
                        )}
                    </div>
                    <button
                        type="submit"
                        className="w-full mt-4 bg-[#4a7cff] hover:bg-[#3d6ae6] text-white py-3 rounded-lg transition-colors"
                    >
                        Search Player
                    </button>
                </form>
            </div>
        </div>
    );
}
