import * as React from 'react';
import {Search, Target} from 'lucide-react';
import {StatsOverview} from './components/StatsOverview';
import {MatchHistory} from './components/MatchHistory';
import {ActSelector} from './components/ActSelector';
import {Skeleton} from './components/ui/skeleton';
import {API_BASE_URL} from "./components/Match/utils/matchUtils";

// Types
interface ProfileData {
    name: string;
    tag: string;
    account_level?: number;
    puuid?: string;
    card?: {
        small: string;
    };
}

interface PlayerIdentifier {
    name: string;
    tag: string;
}

interface StatCardProps {
    title: string;
    value: string;
    isPositive?: boolean;
}

// Component for profile stat card
const StatCard: React.FC<StatCardProps> = ({title, value, isPositive}) => (
    <div className="text-center">
        <div className="text-gray-400">{title}</div>
        <div className={isPositive ? "text-[#4ade80]" : "text-white"}>{value}</div>
    </div>
);

// Component for profile header skeleton
const ProfileSkeleton: React.FC = () => (
    <>
        <div>
            <Skeleton className="h-6 w-32 mb-2 bg-[#2a2a2a]"/>
            <Skeleton className="h-4 w-24 bg-[#2a2a2a]"/>
        </div>
    </>
);

const CircularProgress: React.FC<{ percentage: number; size?: number; stroke?: number }> = ({percentage, size = 64, stroke = 8}) => {
    const clamped = Math.max(0, Math.min(100, Number(percentage) || 0));
    const radius = (size - stroke) / 2;
    const circumference = 2 * Math.PI * radius;
    const halfCirc = circumference / 2;

    const offset = halfCirc * (1 - clamped / 100);

    const fontSize = Math.max(12, Math.round(size * 0.20));

    return (
        <div style={{width: size, height: size}} className="flex items-center justify-center">
            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`${clamped}% winrate`}>
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
                        strokeLinecap="round"
                        strokeDasharray={`${halfCirc} ${circumference}`}
                        strokeDashoffset={0}
                    />
                    <circle
                        cx={0}
                        cy={0}
                        r={radius}
                        stroke="url(#grad)"
                        strokeWidth={stroke}
                        strokeLinecap="round"
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

// Helper to parse player search query (format: "name#tag")
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

// New: parse player identifier from current URL (supports /id/Name_Tag and tracker.gg style)
function parsePlayerFromUrl(): PlayerIdentifier | null {
    try {
        const path = (window.location && window.location.pathname) || '';
        // 1) vtl.lol style: /id/Name_Tag (split on last underscore)
        const idMatch = path.match(/\/id\/(.+)$/i);
        if (idMatch && idMatch[1]) {
            const decoded = decodeURIComponent(idMatch[1]);
            const lastUnderscore = decoded.lastIndexOf('_');
            if (lastUnderscore > 0 && lastUnderscore < decoded.length - 1) {
                const name = decoded.substring(0, lastUnderscore);
                const tag = decoded.substring(lastUnderscore + 1);
                return { name, tag };
            }
        }

        // 2) tracker.gg style: /valorant/profile/riot/NAME%23TAG/...
        const trackerMatch = path.match(/\/valorant\/profile\/riot\/([^\/]+)(\/|$)/i);
        if (trackerMatch && trackerMatch[1]) {
            const decoded = decodeURIComponent(trackerMatch[1]); // will decode %23 to '#'
            const parsed = parsePlayerQuery(decoded);
            if (parsed) return parsed;
            // fallback if tracker used NAME_Tag (rare)
            const lastUnderscore = decoded.lastIndexOf('_');
            if (lastUnderscore > 0 && lastUnderscore < decoded.length - 1) {
                return {
                    name: decoded.substring(0, lastUnderscore),
                    tag: decoded.substring(lastUnderscore + 1)
                };
            }
        }

        // 3) generic: if pathname contains a raw "Name#Tag" segment anywhere
        const segments = path.split('/').map(s => decodeURIComponent(s)).filter(Boolean);
        for (const seg of segments) {
            const parsed = parsePlayerQuery(seg);
            if (parsed) return parsed;
        }
    } catch (e) {
        console.warn('Failed to parse player from URL', e);
    }
    return null;
}

export default function App() {
    const [searchQuery, setSearchQuery] = React.useState('');
    const [currentPlayer, setCurrentPlayer] = React.useState<PlayerIdentifier>({name: 'wheaty', tag: '420'});
    const [selectedAct, setSelectedAct] = React.useState('all');
    const [profile, setProfile] = React.useState<ProfileData | null>(null);
    const [searchError, setSearchError] = React.useState<string | null>(null);

    // On mount: if URL contains a player identifier, use it
    React.useEffect(() => {
        const fromUrl = parsePlayerFromUrl();
        if (fromUrl) {
            setCurrentPlayer(fromUrl);
        }
    }, []);

    // Fetch player profile data when currentPlayer changes
    React.useEffect(() => {
        const fetchProfile = async () => {
            setProfile(null); // Reset to show loading state
            setSearchError(null);
            try {
                const res = await fetch(`${API_BASE_URL}/account/${encodeURIComponent(currentPlayer.name)}/${encodeURIComponent(currentPlayer.tag)}`);
                if (!res.ok) {
                    throw new Error('Player not found');
                }
                const json = await res.json();
                setProfile(json.data);
            } catch (e) {
                console.error("Error fetching profile:", e);
                setSearchError('Player not found. Please check the name and tag.');
                setProfile(null);
            }
        };

        fetchProfile();
    }, [currentPlayer]);

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        const parsed = parsePlayerQuery(searchQuery);
        if (parsed) {
            setCurrentPlayer(parsed);
            setSearchQuery('');
            setSearchError(null);
            // Push a shareable URL: /id/Name_Tag (encode components)
            try {
                const urlName = encodeURIComponent(parsed.name);
                const urlTag = encodeURIComponent(parsed.tag);
                const newPath = `/id/${urlName}_${urlTag}`;
                window.history.pushState(null, '', newPath);
            } catch (err) {
                // ignore pushState errors (e.g., in some embedded contexts)
            }
        } else {
            setSearchError('Invalid format. Use: PlayerName#Tag');
        }
    };

    // Helpers
    const isProfileLoading = profile === null && !searchError;

    // derive icons and winrate
    const defaultTierId = "03621f52-342b-cf4e-4f86-9350a49c6d04";
    const profileAny = profile as any || {};
    const peakTierIdx = typeof profileAny.peak_tier_index === 'number' ? profileAny.peak_tier_index : profileAny.peak_tier || profileAny.peak_ranking_in_tier || 27;
    const currentTierIdx = typeof profileAny.current_tier_index === 'number' ? profileAny.current_tier_index : profileAny.currenttier || profileAny.ranking_in_tier || 27;
    const peakIconUrl = `https://media.valorant-api.com/competitivetiers/${defaultTierId}/${peakTierIdx}/smallicon.png`;
    const currentIconUrl = `https://media.valorant-api.com/competitivetiers/${defaultTierId}/${currentTierIdx}/smallicon.png`;
    const winrate = typeof profileAny.winrate === 'number' ? profileAny.winrate : (profileAny.wl ? (profileAny.wl.winrate || 56.3) : 56.3);

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
                                    onChange={(e) => {
                                        setSearchQuery(e.target.value);
                                        setSearchError(null);
                                    }}
                                    placeholder="Search player (Name#Tag)..."
                                    className="bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg pl-10 pr-4 py-2 text-white placeholder-gray-500 focus:outline-none focus:border-[#4a7cff] transition-colors w-64"
                                />
                            </div>
                            <button
                                type="submit"
                                className="px-4 py-2 bg-[#4a7cff] text-white rounded-lg hover:bg-[#3d6ae0] transition-colors"
                            >
                                Search
                            </button>
                        </form>
                    </div>
                    {searchError && (
                        <div className="mt-2 text-[#f87171] text-sm">{searchError}</div>
                    )}
                </div>
            </header>

            {/* Main Content */}
            <main className="max-w-7xl mx-auto px-6 py-8">
                <div className="space-y-6">
                    {/* Player Info Bar */}
                    <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-6">
                        <div className="flex items-center justify-between mb-6">
                            <div className="flex items-center gap-4">
                                <div className="w-16 h-16 rounded-lg flex items-center justify-center overflow-hidden">
                                    {isProfileLoading ? (
                                        <Skeleton className="w-16 h-16 rounded-lg bg-[#2a2a2a]"/>
                                    ) : profile?.card?.small ? (
                                        <img
                                            src={profile.card.small}
                                            alt={profile.name}
                                            className="w-16 h-16 object-cover rounded-lg"
                                        />
                                    ) : null}
                                </div>

                                <div>
                                    {isProfileLoading ? (
                                        <ProfileSkeleton/>
                                    ) : searchError ? (
                                        <h2 className="text-[#f87171]">Player not found</h2>
                                    ) : (
                                        <>
                                            <h2 className="text-white">{profile?.name}</h2>
                                            <p className="text-gray-400">
                                                #{profile?.tag}
                                                {profile?.account_level ? ` • Lv.${profile.account_level}` : ""}
                                            </p>
                                        </>
                                    )}
                                </div>
                            </div>

                            {/* Rank and Winrate section */}
                            {!searchError && (
                                <div className="flex items-center gap-8">
                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Peak Rank</div>
                                        <div className="flex justify-center">
                                            <img
                                                src={peakIconUrl}
                                                alt="Peak Rank"
                                                className="w-16 h-16"
                                                onError={(e) => { (e.currentTarget as HTMLImageElement).src = peakIconUrl; }}
                                            />
                                        </div>
                                    </div>

                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Current Rank</div>
                                        <div className="flex justify-center">
                                            <img
                                                src={currentIconUrl}
                                                alt="Current Rank"
                                                className="w-16 h-16"
                                                onError={(e) => { (e.currentTarget as HTMLImageElement).src = currentIconUrl; }}
                                            />
                                        </div>
                                    </div>

                                    <div className="text-center">
                                        <div className="text-gray-400 mb-2">Winrate</div>
                                        <CircularProgress percentage={Number(winrate)} />
                                    </div>
                                </div>
                            )}
                        </div>

                        <div className="border-t border-[#1a1a1a] pt-4">
                            <ActSelector selectedAct={selectedAct} onActChange={setSelectedAct}/>
                        </div>
                    </div>

                    {/* Stats Overview */}
                    {/*<StatsOverview/>*/}

                    {/* Match History - pass player info */}
                    {profile?.puuid && (
                        <MatchHistory
                            puuid={profile.puuid}
                            playerName={currentPlayer.name}
                            playerTag={currentPlayer.tag}
                        />
                    )}
                </div>
            </main>
        </div>
    );
}
