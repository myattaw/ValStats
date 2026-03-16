import * as React from 'react';
import { Search, Target } from 'lucide-react';
import { StatsOverview } from './components/StatsOverview';
import { MatchHistory } from './components/MatchHistory';
import { ActSelector } from './components/ActSelector';
import { Skeleton } from './components/ui/skeleton';
import { API_BASE_URL } from "./components/Match/utils/matchUtils";
import { PlayerSearch } from './components/PlayerSearch';

/* ===============================
   Types
================================ */

interface ProfileData {
    name: string;
    tag: string;
    account_level?: number;
    puuid?: string;
    card?: {
        small: string;
    };
}

interface PlayerStats {
    kd_ratio: number;
    headshot_percent: number;
    avg_combat_score: number;
    kills_per_round: number;
}

interface PlayerIdentifier {
    name: string;
    tag: string;
}

/* ===============================
   Helpers
================================ */

function parsePlayerQuery(query: string): PlayerIdentifier | null {
    const trimmed = query.trim();
    const hashIndex = trimmed.lastIndexOf('#');

    if (hashIndex === -1 || hashIndex === 0 || hashIndex === trimmed.length - 1) {
        return null;
    }

    return {
        name: trimmed.substring(0, hashIndex),
        tag: trimmed.substring(hashIndex + 1)
    };
}

function parsePlayerFromUrl(): PlayerIdentifier | null {
    try {
        const path = window.location.pathname || '';
        const idMatch = path.match(/\/id\/(.+)$/i);

        if (idMatch && idMatch[1]) {
            const decoded = decodeURIComponent(idMatch[1]);
            const lastUnderscore = decoded.lastIndexOf('_');

            if (lastUnderscore > 0 && lastUnderscore < decoded.length - 1) {
                return {
                    name: decoded.substring(0, lastUnderscore),
                    tag: decoded.substring(lastUnderscore + 1)
                };
            }
        }
    } catch {
        return null;
    }

    return null;
}

const CircularProgress: React.FC<{ percentage: number; size?: number; stroke?: number }> = ({
                                                                                                percentage,
                                                                                                size = 64,
                                                                                                stroke = 8
                                                                                            }) => {

    const clamped = Math.max(0, Math.min(100, Number(percentage) || 0));
    const radius = (size - stroke) / 2;
    const circumference = 2 * Math.PI * radius;
    const halfCirc = circumference / 2;
    const offset = halfCirc * (1 - clamped / 100);

    const fontSize = Math.max(12, Math.round(size * 0.20));

    return (
        <div style={{ width: size, height: size }} className="flex items-center justify-center">

            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>

                <defs>
                    <linearGradient id="grad" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stopColor="#4a7cff" />
                        <stop offset="100%" stopColor="#2d5acc" />
                    </linearGradient>
                </defs>

                <g transform={`translate(${size / 2}, ${size / 2}) rotate(180)`}>

                    <circle
                        cx={0}
                        cy={0}
                        r={radius}
                        stroke="#111"
                        strokeWidth={stroke}
                        fill="transparent"
                        strokeDasharray={`${halfCirc} ${circumference}`}
                    />

                    <circle
                        cx={0}
                        cy={0}
                        r={radius}
                        stroke="url(#grad)"
                        strokeWidth={stroke}
                        fill="transparent"
                        strokeDasharray={`${halfCirc} ${circumference}`}
                        strokeDashoffset={offset}
                    />

                </g>

                <text
                    x="50%"
                    y={size * 0.72}
                    dominantBaseline="middle"
                    textAnchor="middle"
                    fontSize={fontSize}
                    fill="#fff"
                >
                    {clamped.toFixed(1).replace(/\.0$/, '')}%
                </text>

            </svg>

        </div>
    );
};

/* ===============================
   App
================================ */

export default function App() {

    const region = "na";

    const [searchQuery, setSearchQuery] = React.useState('');
    const [currentPlayer, setCurrentPlayer] = React.useState<PlayerIdentifier | null>(null);

    const [profile, setProfile] = React.useState<ProfileData | null>(null);
    const [stats, setStats] = React.useState<PlayerStats | null>(null);

    const [selectedAct, setSelectedAct] = React.useState('all');
    const [searchError, setSearchError] = React.useState<string | null>(null);

    const showHeaderSearch = currentPlayer !== null;

    /* =========================================
       Load player from URL
    ========================================= */

    React.useEffect(() => {
        const fromUrl = parsePlayerFromUrl();
        if (fromUrl) {
            setCurrentPlayer(fromUrl);
        }
    }, []);

    /* =========================================
       Fetch Profile
    ========================================= */

    React.useEffect(() => {

        if (!currentPlayer) return;

        const fetchProfile = async () => {

            setProfile(null);
            setSearchError(null);

            try {

                const res = await fetch(
                    `${API_BASE_URL}/account/${encodeURIComponent(currentPlayer.name)}/${encodeURIComponent(currentPlayer.tag)}`
                );

                if (!res.ok) throw new Error("Player not found");

                const json = await res.json();
                setProfile(json.data);

            } catch {

                setSearchError("Player not found");
                setProfile(null);

            }

        };

        fetchProfile();

    }, [currentPlayer]);

    /* =========================================
       Fetch Stats
    ========================================= */

    React.useEffect(() => {

        if (!currentPlayer) return;

        const fetchStats = async () => {

            setStats(null);

            try {

                const res = await fetch(
                    `${API_BASE_URL}/stats/${region}/${encodeURIComponent(currentPlayer.name)}/${encodeURIComponent(currentPlayer.tag)}?season=${selectedAct}`
                );

                if (!res.ok) return;

                const json = await res.json();
                setStats(json.data);

            } catch {}

        };

        fetchStats();

    }, [currentPlayer, selectedAct]);

    /* =========================================
       Search
    ========================================= */

    const handleSearch = (e: React.FormEvent) => {

        e.preventDefault();

        const parsed = parsePlayerQuery(searchQuery);

        if (!parsed) {
            setSearchError("Invalid format. Use Player#Tag");
            return;
        }

        setCurrentPlayer(parsed);
        setSearchQuery('');
        setSearchError(null);

        const newPath = `/id/${encodeURIComponent(parsed.name)}_${encodeURIComponent(parsed.tag)}`;
        window.history.pushState(null, '', newPath);

    };

    const handleLandingSearch = (query: string) => {

        const parsed = parsePlayerQuery(query);

        if (!parsed) {
            setSearchError("Invalid format. Use Player#Tag");
            return;
        }

        setCurrentPlayer(parsed);
        setSearchError(null);

        const newPath = `/id/${encodeURIComponent(parsed.name)}_${encodeURIComponent(parsed.tag)}`;
        window.history.pushState(null, '', newPath);

    };

    /* =========================================
       Derived
    ========================================= */

    const isProfileLoading = currentPlayer !== null && profile === null && !searchError;

    const defaultTierId = "03621f52-342b-cf4e-4f86-9350a49c6d04";
    const profileAny = profile as any || {};

    const peakTierIdx = profileAny.peak_tier_index ?? profileAny.peak_tier ?? 27;
    const currentTierIdx = profileAny.current_tier_index ?? profileAny.currenttier ?? 27;

    const peakIconUrl =
        `https://media.valorant-api.com/competitivetiers/${defaultTierId}/${peakTierIdx}/smallicon.png`;

    const currentIconUrl =
        `https://media.valorant-api.com/competitivetiers/${defaultTierId}/${currentTierIdx}/smallicon.png`;

    const winrate =
        profileAny.winrate ??
        profileAny.wl?.winrate ??
        56.3;

    /* =========================================
       UI
    ========================================= */

    return (

        <div className="min-h-screen bg-[#0a0a0a] text-white">

            {/* HEADER */}

            <header className="border-b border-[#1a1a1a] bg-[#0f0f0f]">

                <div className="max-w-7xl mx-auto px-6 py-4">

                    <div className="flex items-center justify-between">

                        <div className="flex items-center gap-3">
                            <Target className="w-8 h-8 text-[#4a7cff]" />
                            <h1>VALSTATS.COM</h1>
                        </div>

                        {showHeaderSearch && (

                            <form onSubmit={handleSearch} className="flex items-center gap-2">

                                <div className="relative">

                                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />

                                    <input
                                        value={searchQuery}
                                        onChange={(e) => {
                                            setSearchQuery(e.target.value);
                                            setSearchError(null);
                                        }}
                                        placeholder="Search player (Name#Tag)"
                                        className="bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg pl-10 pr-4 py-2 w-64"
                                    />

                                </div>

                                <button
                                    type="submit"
                                    className="px-4 py-2 bg-[#4a7cff] rounded-lg"
                                >
                                    Search
                                </button>

                            </form>

                        )}

                    </div>

                    {searchError && (
                        <div className="mt-2 text-[#f87171] text-sm">
                            {searchError}
                        </div>
                    )}

                </div>

            </header>

            {/* CONTENT */}

            <main className="max-w-7xl mx-auto px-6 py-8">

                {!currentPlayer && (

                    <div className="flex justify-center">

                        <div className="w-full max-w-3xl">
                            <PlayerSearch onSearch={handleLandingSearch} />
                        </div>

                    </div>

                )}

                {currentPlayer && (

                    <div className="space-y-6">

                        {/* Profile */}

                        <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-6">

                            <div className="flex items-center justify-between mb-6">

                                <div className="flex items-center gap-4">

                                    <div className="w-16 h-16 rounded-lg overflow-hidden">

                                        {isProfileLoading ? (

                                            <Skeleton className="w-16 h-16 rounded-lg bg-[#2a2a2a]" />

                                        ) : profile?.card?.small ? (

                                            <img
                                                src={profile.card.small}
                                                alt={profile.name}
                                                className="w-16 h-16 object-cover"
                                            />

                                        ) : null}

                                    </div>

                                    <div>

                                        {isProfileLoading ? (

                                            <Skeleton className="h-6 w-32 bg-[#2a2a2a]" />

                                        ) : (

                                            <>
                                                <h2>{profile?.name}</h2>
                                                <p className="text-gray-400">#{profile?.tag}</p>
                                            </>

                                        )}

                                    </div>

                                </div>

                                <div className="flex items-center gap-8">

                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Peak Rank</div>
                                        <img src={peakIconUrl} className="w-16 h-16" />
                                    </div>

                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Current Rank</div>
                                        <img src={currentIconUrl} className="w-16 h-16" />
                                    </div>

                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Winrate</div>
                                        <CircularProgress percentage={Number(winrate)} />
                                    </div>

                                </div>

                            </div>

                            <div className="border-t border-[#1a1a1a] pt-4">

                                <ActSelector
                                    selectedAct={selectedAct}
                                    onActChange={setSelectedAct}
                                />

                            </div>

                        </div>

                        {/* Stats */}

                        <StatsOverview stats={stats} />

                        {/* Matches */}

                        {profile?.puuid && (

                            <MatchHistory
                                puuid={profile.puuid}
                                playerName={currentPlayer.name}
                                playerTag={currentPlayer.tag}
                            />

                        )}

                    </div>

                )}

            </main>

        </div>

    );

}