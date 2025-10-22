import * as React from 'react';
import {Search, Target} from 'lucide-react';
import {StatsOverview} from './components/StatsOverview';
import {MatchHistory} from './components/MatchHistory';
import {ActSelector} from './components/ActSelector';
import {Skeleton} from './components/ui/skeleton';
import {API_BASE_URL} from "./components/Match/utils/matchUtils";

// Constants

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

    const fontSize = Math.max(12, Math.round(size * 0.20)); // increase text size slightly

    return (
        <div style={{width: size, height: size}} className="flex items-center justify-center">
            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`${clamped}% winrate`}>
                <defs>
                    <linearGradient id="grad" x1="0%" y1="0%" x2="100%" y2="0%">
                        <stop offset="0%" stopColor="#4a7cff" />
                        <stop offset="100%" stopColor="#2d5acc" />
                    </linearGradient>
                </defs>

                {/* rotate 180deg so the arc starts at the LEFT and fills left->right */}
                <g transform={`translate(${size / 2}, ${size / 2}) rotate(180)`}>
                    {/* background/trail - draw only the top half using dasharray, rounded caps */}
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
                    {/* progress arc with rounded ends */}
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

                {/* percentage text centered vertically slightly below the arc */}
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

export default function App() {
    const [searchQuery, setSearchQuery] = React.useState('');
    const [selectedPlayer, setSelectedPlayer] = React.useState<string | null>('TenZ#NA1');
    const [selectedAct, setSelectedAct] = React.useState('all');
    const [profile, setProfile] = React.useState<ProfileData | null>(null);

    // Fetch player profile data
    React.useEffect(() => {
        const fetchProfile = async () => {
            try {
                const res = await fetch(`${API_BASE_URL}/account/rages/alt`);
                const json = await res.json();
                setProfile(json.data);
            } catch (e) {
                console.error("Error fetching profile:", e);
                setProfile(null);
            }
        };

        fetchProfile();
    }, []);

    const handleSearch = (e: React.FormEvent) => {
        e.preventDefault();
        if (searchQuery.trim()) {
            setSelectedPlayer(searchQuery);
        }
    };

    // Helpers
    const isProfileLoading = profile === null;

    // derive icons and winrate (use loose access and fallbacks)
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
                <div className="space-y-6">
                    {/* Player Info Bar */}
                    <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-6">
                        <div className="flex items-center justify-between mb-6">
                            <div className="flex items-center gap-4">
                                {/* ...existing code for avatar & name... */}
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

                            {/* NEW layout for Peak Rank / Current Rank / Winrate */}
                            <div className="flex items-center gap-8">
                                <div className="text-center">
                                    <div className="text-gray-400 mb-2">
                                        Peak Rank
                                    </div>
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
                                    <div className="text-gray-400 mb-2">
                                        Current Rank
                                    </div>
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
                                    <div className="text-gray-400 mb-2">
                                        Winrate
                                    </div>
                                    <CircularProgress percentage={Number(winrate)} />
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
                    {profile?.puuid && <MatchHistory puuid={profile.puuid}/>}
                </div>
            </main>
        </div>
    );
}
