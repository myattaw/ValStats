import * as React from 'react';
import {Search, Target} from 'lucide-react';
import {StatsOverview} from './components/StatsOverview';
import {MatchHistory} from './components/MatchHistory';
import {ActSelector} from './components/ActSelector';

export default function App() {
    const [searchQuery, setSearchQuery] = React.useState('');
    const [selectedPlayer, setSelectedPlayer] = React.useState<string | null>('TenZ#NA1');
    const [selectedAct, setSelectedAct] = React.useState('all');

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        if (searchQuery.trim()) {
            setSelectedPlayer(searchQuery);
        }
    };

    return (
        <div className="min-h-screen bg-[#0a0a0a] text-white">
            {/* Header */}
            <header className="border-b border-[#1a1a1a] bg-[#0f0f0f]">
                <div className="max-w-7xl mx-auto px-6 py-4">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-3">
                            <Target className="w-8 h-8 text-[#4a7cff]"/>
                            <h1 className="text-white">VALSTATS.COM</h1>
                        </div>
                        <form onSubmit={handleSearch} className="flex items-center gap-2">
                            <div className="relative">
                                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400"/>
                                <input
                                    type="text"
                                    value={searchQuery}
                                    onChange={(e) => setSearchQuery(e.target.value)}
                                    placeholder="Search player..."
                                    className="bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg pl-10 pr-4 py-2 text-white placeholder-gray-500 focus:outline-none focus:border-[#4a7cff] transition-colors w-64"
                                />
                            </div>
                        </form>
                    </div>
                </div>
            </header>

            {/* Main Content */}
            <main className="max-w-7xl mx-auto px-6 py-8">

                {/* Stats Content */}
                {selectedPlayer && (
                    <div className="space-y-6">
                        {/* Player Info Bar */}
                        <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-6">
                            <div className="flex items-center justify-between mb-6">
                                <div className="flex items-center gap-4">
                                    <div
                                        className="w-16 h-16 bg-gradient-to-br from-[#4a7cff] to-[#2d5acc] rounded-lg flex items-center justify-center">
                                        <span className="text-2xl">T</span>
                                    </div>
                                    <div>
                                        <h2 className="text-white">TenZ</h2>
                                        <p className="text-gray-400">#NA1 • Radiant</p>
                                    </div>
                                </div>
                                <div className="flex items-center gap-8">
                                    <div className="text-center">
                                        <div className="text-gray-400">Peak Rating</div>
                                        <div className="text-white">534 RR</div>
                                    </div>
                                    <div className="text-center">
                                        <div className="text-gray-400">Current Rank</div>
                                        <div className="text-white">Radiant</div>
                                    </div>
                                    <div className="text-center">
                                        <div className="text-gray-400">Winrate</div>
                                        <div className="text-[#4ade80]">56.3%</div>
                                    </div>
                                </div>
                            </div>
                            <div className="border-t border-[#1a1a1a] pt-4">
                                <ActSelector selectedAct={selectedAct} onActChange={setSelectedAct}/>
                            </div>
                        </div>

                        {/* Stats Overview */}
                        <StatsOverview/>

                        {/* Match History */}
                        <MatchHistory/>
                    </div>
                )}
            </main>
        </div>
    );
}
