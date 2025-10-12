import {useEffect, useState} from "react";
import {ChevronDown, Clock, Loader2, TrendingDown, TrendingUp,} from "lucide-react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger,} from "./ui/collapsible";
import {Skeleton} from "./ui/skeleton";

interface PlayerStats {
    name: string;
    agent: string;
    kills: number;
    deaths: number;
    assists: number;
    acs: number;
    headshot: number;
}

interface Match {
    id: string;
    map: string;
    mapId: string;
    result: "Victory" | "Defeat";
    score: string;
    kda: string;
    agent: string;
    acs: number;
    timestamp: string;
    rank: string;
    rrChange: number;
    players?: PlayerStats[];
}

export function MatchHistory() {
    const [expandedMatch, setExpandedMatch] = useState<
        string | null
    >(null);
    const [loadingMatchId, setLoadingMatchId] = useState<
        string | null
    >(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [isInitialLoading, setIsInitialLoading] =
        useState(true);
    const [matches, setMatches] = useState<Match[]>([]);
    const [hoveredMatch, setHoveredMatch] = useState<
        string | null
    >(null);

    // Simulate initial loading (2-4 seconds)
    useEffect(() => {
        const loadingTime = Math.random() * 2000 + 2000;
        const timer = setTimeout(
            () => setIsInitialLoading(false),
            loadingTime,
        );
        return () => clearTimeout(timer);
    }, []);

    // Set initial matches after loading
    useEffect(() => {
        if (!isInitialLoading && matches.length === 0) {
            setMatches([
                {
                    id: "1",
                    map: "Ascent",
                    mapId: "7eaecc1b-4337-bbf6-6ab9-04b8f06b3319",
                    result: "Victory",
                    score: "13-7",
                    kda: "24/15/8",
                    agent: "Jett",
                    acs: 298,
                    timestamp: "2 hours ago",
                    rank: "Radiant",
                    rrChange: 23,
                },
                {
                    id: "2",
                    map: "Bind",
                    mapId: "2c9d57ec-4431-9c5e-2939-8f9ef6dd5cba",
                    result: "Defeat",
                    score: "11-13",
                    kda: "18/19/6",
                    agent: "Raze",
                    acs: 234,
                    timestamp: "4 hours ago",
                    rank: "Radiant",
                    rrChange: -18,
                },
                {
                    id: "3",
                    map: "Haven",
                    mapId: "2bee0dc9-4ffe-519b-1cbd-7fbe763a6047",
                    result: "Victory",
                    score: "13-9",
                    kda: "27/12/5",
                    agent: "Jett",
                    acs: 312,
                    timestamp: "6 hours ago",
                    rank: "Radiant",
                    rrChange: 21,
                },
                {
                    id: "4",
                    map: "Split",
                    mapId: "d960549e-485c-e861-8d71-aa9d1aed12a2",
                    result: "Victory",
                    score: "13-5",
                    kda: "21/10/7",
                    agent: "Reyna",
                    acs: 289,
                    timestamp: "8 hours ago",
                    rank: "Radiant",
                    rrChange: 25,
                },
                {
                    id: "5",
                    map: "Icebox",
                    mapId: "e2ad5c54-4114-a870-9641-8ea21279579a",
                    result: "Defeat",
                    score: "10-13",
                    kda: "16/18/4",
                    agent: "Jett",
                    acs: 215,
                    timestamp: "1 day ago",
                    rank: "Radiant",
                    rrChange: -20,
                },
            ]);
        }
    }, [isInitialLoading, matches.length]);

    const fetchMatchDetails = async (
        matchId: string,
    ): Promise<PlayerStats[]> => {
        await new Promise((resolve) => setTimeout(resolve, 800));
        return [
            {
                name: "TenZ",
                agent: "Jett",
                kills: 24,
                deaths: 15,
                assists: 8,
                acs: 298,
                headshot: 32,
            },
            {
                name: "SicK",
                agent: "Sage",
                kills: 18,
                deaths: 14,
                assists: 12,
                acs: 245,
                headshot: 28,
            },
            {
                name: "dapr",
                agent: "Cypher",
                kills: 16,
                deaths: 16,
                assists: 9,
                acs: 221,
                headshot: 24,
            },
            {
                name: "ShahZaM",
                agent: "Sova",
                kills: 15,
                deaths: 17,
                assists: 14,
                acs: 210,
                headshot: 26,
            },
            {
                name: "zombs",
                agent: "Omen",
                kills: 12,
                deaths: 18,
                assists: 10,
                acs: 189,
                headshot: 22,
            },
            {
                name: "Enemy1",
                agent: "Reyna",
                kills: 20,
                deaths: 19,
                assists: 5,
                acs: 267,
                headshot: 30,
            },
            {
                name: "Enemy2",
                agent: "Killjoy",
                kills: 14,
                deaths: 17,
                assists: 8,
                acs: 198,
                headshot: 25,
            },
            {
                name: "Enemy3",
                agent: "Breach",
                kills: 13,
                deaths: 18,
                assists: 11,
                acs: 187,
                headshot: 21,
            },
            {
                name: "Enemy4",
                agent: "Phoenix",
                kills: 16,
                deaths: 16,
                assists: 7,
                acs: 203,
                headshot: 27,
            },
            {
                name: "Enemy5",
                agent: "Brimstone",
                kills: 11,
                deaths: 15,
                assists: 9,
                acs: 175,
                headshot: 19,
            },
        ];
    };

    const fetchMoreMatches = async (): Promise<Match[]> => {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        return [
            {
                id: `${matches.length + 1}`,
                map: "Breeze",
                mapId: "f08d4e7a-4d7b-21f3-cc6c-8f46b1f1d0fa",
                result: "Victory",
                score: "13-8",
                kda: "22/14/6",
                agent: "Jett",
                acs: 276,
                timestamp: "2 days ago",
                rank: "Radiant",
                rrChange: 19,
            },
            {
                id: `${matches.length + 2}`,
                map: "Fracture",
                mapId: "b529448b-4d60-346e-e89e-00a4c527a405",
                result: "Defeat",
                score: "9-13",
                kda: "15/17/8",
                agent: "Raze",
                acs: 198,
                timestamp: "2 days ago",
                rank: "Radiant",
                rrChange: -22,
            },
            {
                id: `${matches.length + 3}`,
                map: "Lotus",
                mapId: "2fe4ed3a-450a-948b-6d6b-e89a78e680a9",
                result: "Victory",
                score: "13-11",
                kda: "26/13/9",
                agent: "Jett",
                acs: 301,
                timestamp: "3 days ago",
                rank: "Radiant",
                rrChange: 17,
            },
        ];
    };

    const handleMatchClick = async (matchId: string) => {
        const match = matches.find((m) => m.id === matchId);
        if (!match) return;
        if (expandedMatch === matchId) {
            setExpandedMatch(null);
            return;
        }
        if (match.players) {
            setExpandedMatch(matchId);
            return;
        }

        setLoadingMatchId(matchId);
        try {
            const playerStats = await fetchMatchDetails(matchId);
            setMatches((prev) =>
                prev.map((m) =>
                    m.id === matchId ? {...m, players: playerStats} : m,
                ),
            );
            setExpandedMatch(matchId);
        } catch (error) {
            console.error("Failed to load match details:", error);
        } finally {
            setLoadingMatchId(null);
        }
    };

    const handleLoadMore = async () => {
        setLoadingMore(true);
        try {
            const moreMatches = await fetchMoreMatches();
            setMatches((prev) => [...prev, ...moreMatches]);
        } catch (error) {
            console.error("Failed to load more matches:", error);
        } finally {
            setLoadingMore(false);
        }
    };

    const MatchSkeleton = () => (
        <div className="rounded-lg border border-[#2a2a2a] bg-[#1a1a1a] p-5">
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-4 flex-1">
                    <Skeleton className="w-12 h-12 rounded-lg bg-[#2a2a2a]"/>
                    <div className="flex-1 space-y-3">
                        <div className="flex items-center gap-3">
                            <Skeleton className="h-5 w-16 bg-[#2a2a2a]"/>
                            <Skeleton className="h-8 w-16 bg-[#2a2a2a]"/>
                            <Skeleton className="h-8 w-12 bg-[#2a2a2a]"/>
                            <Skeleton className="h-5 w-20 bg-[#2a2a2a]"/>
                            <Skeleton className="h-5 w-16 bg-[#2a2a2a]"/>
                        </div>
                        <div className="flex items-center gap-4">
                            <Skeleton className="h-4 w-24 bg-[#2a2a2a]"/>
                            <Skeleton className="h-4 w-32 bg-[#2a2a2a]"/>
                            <Skeleton className="h-4 w-20 bg-[#2a2a2a]"/>
                        </div>
                    </div>
                </div>
                <div className="flex items-center gap-4">
                    <Skeleton className="h-4 w-24 bg-[#2a2a2a]"/>
                    <Skeleton className="h-5 w-5 rounded bg-[#2a2a2a]"/>
                </div>
            </div>
        </div>
    );

    return (
        <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg overflow-hidden">
            <div className="border-b border-[#1a1a1a] p-6">
                <h3 className="text-white">Match History</h3>
            </div>
            <div className="p-6 space-y-4">
                {isInitialLoading ? (
                    Array.from({length: 5}).map((_, idx) => (
                        <MatchSkeleton key={idx}/>
                    ))
                ) : (
                    <>
                        {matches.map((match) => {
                            const matchBgStyle:
                                | React.CSSProperties
                                | undefined = match.mapId
                                ? {
                                    backgroundImage: `url(https://media.valorant-api.com/maps/${match.mapId}/splash.png)`,
                                    backgroundPosition: "center",
                                    backgroundSize: "cover",
                                }
                                : undefined;

                            return (
                                <Collapsible
                                    key={match.id}
                                    open={expandedMatch === match.id}
                                    onOpenChange={() =>
                                        handleMatchClick(match.id)
                                    }
                                >
                                    <div>
                                        <CollapsibleTrigger
                                            className={`w-full text-left p-0 transition-colors relative overflow-hidden ${
                                                expandedMatch === match.id
                                                    ? "rounded-t-lg border-t border-l border-r !border-b-0"
                                                    : "rounded-lg border"
                                            } ${
                                                match.result === "Victory"
                                                    ? "border-[#4ade80]"
                                                    : "border-[#f87171]"
                                            }`}
                                            style={{padding: 0}}
                                            onMouseEnter={() =>
                                                setHoveredMatch(match.id)
                                            }
                                            onMouseLeave={() => setHoveredMatch(null)}
                                        >
                                            <div
                                                className="relative p-5"
                                                style={matchBgStyle}
                                            >
                                                {/* Overlay for darkening background */}
                                                <div
                                                    className="absolute inset-0 bg-black/75 z-0 pointer-events-none"></div>
                                                {/* Colored overlay */}
                                                <div
                                                    className={`absolute inset-0 z-10 pointer-events-none`}
                                                    style={{
                                                        backgroundColor:
                                                            match.result === "Victory"
                                                                ? "rgba(74, 222, 128, 0.1)"
                                                                : "rgba(248, 113, 113, 0.1)",
                                                    }}
                                                />
                                                {/* Hover overlay */}
                                                <div
                                                    className="absolute inset-0 z-20 transition-opacity duration-200"
                                                    style={{
                                                        backgroundColor:
                                                            match.result === "Victory"
                                                                ? "rgba(74, 222, 128, 0.15)"
                                                                : "rgba(248, 113, 113, 0.15)",
                                                        opacity:
                                                            hoveredMatch === match.id ? 1 : 0,
                                                    }}
                                                />
                                                {/* Content */}
                                                <div className="flex items-center justify-between relative z-30">
                                                    <div className="flex items-center gap-4">
                                                        <div
                                                            className="w-12 h-12 bg-gradient-to-br from-[#4a7cff] to-[#2d5acc] rounded-lg flex items-center justify-center flex-shrink-0">
                              <span className="text-sm">
                                {match.agent[0]}
                              </span>
                                                        </div>

                                                        <div>
                                                            <div className="flex items-center gap-3 mb-2">
                                <span className="text-white">
                                  {match.map}
                                </span>
                                                                <span
                                                                    className={`px-3 py-1 rounded ${
                                                                        match.result === "Victory"
                                                                            ? "bg-[#4ade80]/20 text-[#4ade80]"
                                                                            : "bg-[#f87171]/20 text-[#f87171]"
                                                                    }`}
                                                                >
                                  {match.result}
                                </span>
                                                                <span
                                                                    className="text-gray-400 px-2 py-1 rounded bg-black/30">
                                  {match.score}
                                </span>

                                                                <div
                                                                    className="flex items-center gap-1.5 px-2 py-1 rounded bg-black/30">
                                                                    <img
                                                                        src="https://media.valorant-api.com/competitivetiers/03621f52-342b-cf4e-4f86-9350a49c6d04/27/smallicon.png"
                                                                        alt="Rank Icon"
                                                                        className="w-3 h-3"
                                                                    />
                                                                    <span className="text-xs text-gray-300">
                                    {match.rank}
                                  </span>
                                                                </div>

                                                                <div
                                                                    className={`flex items-center gap-1 px-2 py-1 rounded ${
                                                                        match.rrChange > 0
                                                                            ? "bg-[#4ade80]/10 text-[#4ade80]"
                                                                            : "bg-[#f87171]/10 text-[#f87171]"
                                                                    }`}
                                                                >
                                                                    {match.rrChange > 0 ? (
                                                                        <TrendingUp className="w-3 h-3"/>
                                                                    ) : (
                                                                        <TrendingDown className="w-3 h-3"/>
                                                                    )}
                                                                    <span className="text-xs">
                                    {match.rrChange > 0
                                        ? "+"
                                        : ""}
                                                                        {match.rrChange} RR
                                  </span>
                                                                </div>
                                                            </div>
                                                            <div className="flex items-center gap-4">
                                <span className="text-gray-400">
                                  Agent: {match.agent}
                                </span>
                                                                <span className="text-gray-400">
                                  •
                                </span>
                                                                <span className="text-gray-400">
                                  KDA: {match.kda}
                                </span>
                                                                <span className="text-gray-400">
                                  •
                                </span>
                                                                <span className="text-gray-400">
                                  ACS: {match.acs}
                                </span>
                                                            </div>
                                                        </div>
                                                    </div>
                                                    <div className="flex items-center gap-4">
                                                        <div className="flex items-center gap-2 text-gray-400">
                                                            <Clock className="w-4 h-4"/>
                                                            <span>{match.timestamp}</span>
                                                        </div>
                                                        {loadingMatchId === match.id ? (
                                                            <Loader2 className="w-5 h-5 text-gray-400 animate-spin"/>
                                                        ) : (
                                                            <ChevronDown
                                                                className={`w-5 h-5 text-gray-400 transition-transform ${
                                                                    expandedMatch === match.id
                                                                        ? "rotate-180"
                                                                        : ""
                                                                }`}
                                                            />
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                        </CollapsibleTrigger>
                                        <CollapsibleContent>
                                            {match.players && (
                                                <div>
                                                    {/* Winning Team */}
                                                    <div
                                                        className={`p-5 border-l border-r ${
                                                            match.result === "Victory"
                                                                ? "bg-[#4ade80]/10 border-[#4ade80]"
                                                                : "bg-[#f87171]/10 border-[#f87171]"
                                                        }`}
                                                    >
                                                        <h4 className="text-sm text-gray-400 mb-3">
                                                            {match.result === "Victory"
                                                                ? "Your Team (Victory)"
                                                                : "Enemy Team (Victory)"}
                                                        </h4>
                                                        <div className="space-y-2">
                                                            {match.players
                                                                .slice(0, 5)
                                                                .map((player, idx) => (
                                                                    <div
                                                                        key={idx}
                                                                        className="flex items-center justify-between p-3 bg-black/20 rounded-lg"
                                                                    >
                                                                        <div className="flex items-center gap-4">
                                                                            <div
                                                                                className="w-8 h-8 bg-gradient-to-br from-[#4a7cff] to-[#2d5acc] rounded flex items-center justify-center">
                                        <span className="text-xs">
                                          {player.agent[0]}
                                        </span>
                                                                            </div>
                                                                            <div>
                                                                                <div className="text-white text-sm">
                                                                                    {player.name}
                                                                                </div>
                                                                                <div className="text-xs text-gray-400">
                                                                                    {player.agent}
                                                                                </div>
                                                                            </div>
                                                                        </div>
                                                                        <div
                                                                            className="flex items-center gap-6 text-sm">
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    K/D/A
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.kills}/
                                                                                    {player.deaths}/
                                                                                    {player.assists}
                                                                                </div>
                                                                            </div>
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    ACS
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.acs}
                                                                                </div>
                                                                            </div>
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    HS%
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.headshot}%
                                                                                </div>
                                                                            </div>
                                                                        </div>
                                                                    </div>
                                                                ))}
                                                        </div>
                                                    </div>

                                                    {/* Losing Team */}
                                                    <div
                                                        className={`p-5 border-l border-r border-b rounded-b-lg ${
                                                            match.result === "Victory"
                                                                ? "bg-[#f87171]/10 border-[#f87171]"
                                                                : "bg-[#4ade80]/10 border-[#4ade80]"
                                                        }`}
                                                    >
                                                        <h4 className="text-sm text-gray-400 mb-3">
                                                            {match.result === "Victory"
                                                                ? "Enemy Team (Defeat)"
                                                                : "Your Team (Defeat)"}
                                                        </h4>
                                                        <div className="space-y-2">
                                                            {match.players
                                                                .slice(5, 10)
                                                                .map((player, idx) => (
                                                                    <div
                                                                        key={idx}
                                                                        className="flex items-center justify-between p-3 bg-black/20 rounded-lg"
                                                                    >
                                                                        <div className="flex items-center gap-4">
                                                                            <div
                                                                                className="w-8 h-8 bg-gradient-to-br from-[#f87171] to-[#dc2626] rounded flex items-center justify-center">
                                        <span className="text-xs">
                                          {player.agent[0]}
                                        </span>
                                                                            </div>
                                                                            <div>
                                                                                <div className="text-white text-sm">
                                                                                    {player.name}
                                                                                </div>
                                                                                <div className="text-xs text-gray-400">
                                                                                    {player.agent}
                                                                                </div>
                                                                            </div>
                                                                        </div>
                                                                        <div
                                                                            className="flex items-center gap-6 text-sm">
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    K/D/A
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.kills}/
                                                                                    {player.deaths}/
                                                                                    {player.assists}
                                                                                </div>
                                                                            </div>
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    ACS
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.acs}
                                                                                </div>
                                                                            </div>
                                                                            <div className="text-center">
                                                                                <div className="text-gray-400 text-xs">
                                                                                    HS%
                                                                                </div>
                                                                                <div className="text-white">
                                                                                    {player.headshot}%
                                                                                </div>
                                                                            </div>
                                                                        </div>
                                                                    </div>
                                                                ))}
                                                        </div>
                                                    </div>
                                                </div>
                                            )}
                                        </CollapsibleContent>
                                    </div>
                                </Collapsible>
                            );
                        })}
                    </>
                )}

                {/* Load More Button */}
                {!isInitialLoading && (
                    <button
                        onClick={handleLoadMore}
                        disabled={loadingMore}
                        className="w-full py-4 bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg text-white hover:bg-[#242424] transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                    >
                        {loadingMore ? (
                            <>
                                <Loader2 className="w-4 h-4 animate-spin"/>
                                Loading more matches...
                            </>
                        ) : (
                            "Load More Matches"
                        )}
                    </button>
                )}
            </div>
        </div>
    );
}