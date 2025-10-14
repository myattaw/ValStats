import * as React from 'react';
import { Search, Target } from 'lucide-react';
import { StatsOverview } from './components/StatsOverview';
import { MatchHistory } from './components/MatchHistory';
import { ActSelector } from './components/ActSelector';
import { Skeleton } from './components/ui/skeleton';

// Constants
const API_BASE_URL = 'http://localhost:64457/api/valorant';

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
const StatCard: React.FC<StatCardProps> = ({ title, value, isPositive }) => (
  <div className="text-center">
    <div className="text-gray-400">{title}</div>
    <div className={isPositive ? "text-[#4ade80]" : "text-white"}>{value}</div>
  </div>
);

// Component for profile header skeleton
const ProfileSkeleton: React.FC = () => (
  <>
    <div>
      <Skeleton className="h-6 w-32 mb-2 bg-[#2a2a2a]" />
      <Skeleton className="h-4 w-24 bg-[#2a2a2a]" />
    </div>
  </>
);

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

  return (
    <div className="min-h-screen bg-[#0a0a0a] text-white">
      {/* Header */}
      <header className="border-b border-[#1a1a1a] bg-[#0f0f0f]">
        <div className="max-w-7xl mx-auto px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Target className="w-8 h-8 text-[#4a7cff]" />
              <h1 className="text-white">VALSTATS.COM</h1>
            </div>

            <form onSubmit={handleSearch} className="flex items-center gap-2">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
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
                <div className="w-16 h-16 rounded-lg flex items-center justify-center overflow-hidden">
                  {isProfileLoading ? (
                    <Skeleton className="w-16 h-16 rounded-lg bg-[#2a2a2a]" />
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
                    <ProfileSkeleton />
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

              <div className="flex items-center gap-8">
                <StatCard title="Peak Rating" value="534 RR" />
                <StatCard title="Current Rank" value="Radiant" />
                <StatCard title="Winrate" value="56.3%" isPositive={true} />
              </div>
            </div>

            <div className="border-t border-[#1a1a1a] pt-4">
              <ActSelector selectedAct={selectedAct} onActChange={setSelectedAct} />
            </div>
          </div>

          {/* Stats Overview */}
          <StatsOverview />

          {/* Match History */}
          {profile?.puuid && <MatchHistory puuid={profile.puuid} />}
        </div>
      </main>
    </div>
  );
}
