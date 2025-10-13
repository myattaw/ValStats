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
    score: number;
    headshot: number;
    agentIcon?: string; // NEW: agent icon url
}

interface Match {
    id: string;
    map: string;
    mapId: string;
    result: "Victory" | "Defeat";
    score: number;
    enemy_score: number;
    kda: string;
    agent: string;
    acs: number;
    timestamp: string;
    rank: string;
    ranking_in_tier: number;
    rrChange: number;
    rounds_played: number;
    date_raw?: number;
    players?: PlayerStats[];
    agentIcon?: string; // NEW: agent icon for main match
}

export function MatchHistory() {
    const [expandedMatch, setExpandedMatch] = useState<string | null>(null);
    const [loadingMatchId, setLoadingMatchId] = useState<string | null>(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [isInitialLoading, setIsInitialLoading] = useState(true);
    const [matches, setMatches] = useState<Match[]>([]);
    const [hoveredMatch, setHoveredMatch] = useState<string | null>(null);

    // Fetch both match and mmr data, combine by match id
    useEffect(() => {
        const fetchCombinedMatches = async () => {
            setIsInitialLoading(true);
            try {
                const [matchRes, mmrRes] = await Promise.all([
                    fetch("http://localhost:57608/api/valorant/test"),
                    fetch("http://localhost:57608/api/valorant/test2"),
                ]);
                const matchJson = await matchRes.json();
                const mmrJson = await mmrRes.json();

                // Build a map of mmr data by match_id
                const mmrMap = new Map<string, any>();
                (mmrJson.data || []).forEach((mmr: any) => {
                    if (mmr.match_id) {
                        mmrMap.set(mmr.match_id, mmr);
                    }
                });

                // Combine match data with mmr data
                const combinedMatches: Match[] = (matchJson.data || []).map((match: any) => {
                    const meta = match.metadata;
                    const mmr = mmrMap.get(meta.matchid) || {};
                    const allPlayers = match.players?.all_players || [];
                    let yourPlayer = allPlayers[0];
                    if (mmrJson.name && mmrJson.tag) {
                        const found = allPlayers.find(
                            (p: any) =>
                                p.name === mmrJson.name && p.tag === mmrJson.tag
                        );
                        if (found) yourPlayer = found;
                    }
                    const kda = yourPlayer
                        ? `${yourPlayer.stats.kills}/${yourPlayer.stats.deaths}/${yourPlayer.stats.assists}`
                        : "";
                    const agent = yourPlayer ? yourPlayer.character : "";
                    const agentIcon = yourPlayer?.assets?.agent?.small || ""; // NEW
                    const score = yourPlayer ? yourPlayer.stats.score : 0;
                    // Use player's rounds_played if available, fallback to mmr.rounds_played or meta.rounds_played
                    const roundsPlayed =
                        (yourPlayer && yourPlayer.stats.rounds_played) ||
                        mmr.rounds_played ||
                        meta.rounds_played ||
                        0;
                    const acs = roundsPlayed > 0 ? Math.round(score / roundsPlayed) : 0;

                    // --- NEW: Get real team scores ---
                    let userTeam = yourPlayer?.team?.toLowerCase() || "blue";
                    let userScore = 0;
                    let enemyScore = 0;
                    if (match.teams) {
                        if (userTeam === "red") {
                            userScore = match.teams.red.rounds_won;
                            enemyScore = match.teams.blue.rounds_won;
                        } else {
                            userScore = match.teams.blue.rounds_won;
                            enemyScore = match.teams.red.rounds_won;
                        }
                    }

                    return {
                        id: meta.matchid,
                        map: meta.map || (mmr.map?.name ?? "Unknown"),
                        mapId: mmr.map?.id || "",
                        result: (match.teams && match.teams[userTeam]?.has_won)
                            ? "Victory"
                            : "Defeat",
                        kda,
                        agent,
                        agentIcon, // NEW
                        score: userScore,
                        enemy_score: enemyScore,
                        acs,
                        timestamp: mmr.date || "",
                        rank: mmr.currenttierpatched || "",
                        ranking_in_tier: mmr.currenttier || 0,
                        rrChange: mmr.mmr_change_to_last_game || 0,
                        date_raw: mmr.date_raw,
                        rounds_played: roundsPlayed,
                        players: allPlayers.map((p: any) => ({
                            name: p.name,
                            agent: p.character,
                            kills: p.stats.kills,
                            deaths: p.stats.deaths,
                            assists: p.stats.assists,
                            score: p.stats.score,
                            headshot: 0,
                            agentIcon: p.assets?.agent?.small || "", // NEW
                        })),
                    };
                });

                // Sort by date_raw descending (most recent first)
                combinedMatches.sort((a, b) => (b.date_raw || 0) - (a.date_raw || 0));
                setMatches(combinedMatches);
            } catch (e) {
                setMatches([]);
            } finally {
                setIsInitialLoading(false);
            }
        };
        fetchCombinedMatches();
    }, []);

    // Fetch match details from API
    const fetchMatchDetails = async (matchId: string): Promise<PlayerStats[]> => {
        const res = await fetch("http://localhost:57608/api/valorant/test");
        const data = await res.json();
        const players = data.data[0].players.all_players;
        return players.map((p: any) => ({
            name: p.name,
            agent: p.character,
            score: p.stats.score,
            kills: p.stats.kills,
            deaths: p.stats.deaths,
            assists: p.stats.assists,
            headshot: 0,
            agentIcon: p.assets?.agent?.small || "", // NEW
        }));
    };

    // Fetch more matches (for demo, just duplicate the initial matches)
    const fetchMoreMatches = async (): Promise<Match[]> => {
        // For demo, just duplicate the current matches with new ids
        return matches.map((m, idx) => ({
            ...m,
            id: `${m.id}-more-${idx}`,
        }));
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
            let acs = null;
            let kda = "";
            let agent = "";
            let roundsPlayed = null;
            if (playerStats.length > 0) {
                const p = playerStats[0];
                kda = `${p.kills}/${p.deaths}/${p.assists}`;
                agent = p.agent;
                // Try to get rounds played from player stats, fallback to match
                roundsPlayed = matches.find(m => m.id === matchId)?.rounds_played || 0;
                acs = roundsPlayed > 0 ? Math.round(p.score / roundsPlayed) : 0;
            }
            setMatches((prev) =>
                prev.map((m) =>
                    m.id === matchId
                        ? {
                            ...m,
                            players: playerStats,
                            kda: kda ?? m.kda,
                            agent: agent ?? m.agent,
                            acs: acs ?? m.acs,
                        }
                        : m,
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
                    <Skeleton className="w-16 h-16 rounded-lg bg-[#2a2a2a]"/>
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
                                                            className="w-16 h-16  bg-black/30 rounded-lg flex items-center justify-center flex-shrink-0 overflow-hidden"
                                                        >
                                                            {match.agentIcon ? (
                                                                <img
                                                                    src={match.agentIcon}
                                                                    alt={match.agent}
                                                                    className="w-10 h-10 object-contain"
                                                                />
                                                            ) : (
                                                                <span className="text-sm">
                                                                    {match.agent[0]}
                                                                </span>
                                                            )}
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
                                                                    {/* Real score */}
                                                                    {match.score}-{match.enemy_score}
                                </span>

                                                                <div
                                                                    className="flex items-center gap-1.5 px-2 py-1 rounded bg-black/30">
                                                                    <img
                                                                        src={`https://media.valorant-api.com/competitivetiers/03621f52-342b-cf4e-4f86-9350a49c6d04/${match.ranking_in_tier}/smallicon.png`}
                                                                        alt="Rank Icon"
                                                                        className="w-3 h-3"
                                                                    />
                                                                    <span className="text-xs text-gray-300">
                                    {match.rank}
                                  </span>
                                                                </div>

                                                                <div
                                                                    className={`flex items-center gap-1 px-2 py-1 rounded bg-black/30 ${
                                                                        match.rrChange > 0
                                                                            ? "text-[#4ade80]"
                                                                            : "text-[#f87171]"
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
                                                                                className="w-8 h-8 bg-gradient-to-br from-[#4a7cff] to-[#2d5acc] rounded flex items-center justify-center overflow-hidden"
                                                                            >
                                                                                {player.agentIcon ? (
                                                                                    <img
                                                                                        src={player.agentIcon}
                                                                                        alt={player.agent}
                                                                                        className="w-7 h-7 object-contain"
                                                                                    />
                                                                                ) : (
                                                                                    <span className="text-xs">
                                                                                        {player.agent[0]}
                                                                                    </span>
                                                                                )}
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
                                                                                    {player.score}
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
                                                                                className="w-8 h-8 bg-gradient-to-br from-[#f87171] to-[#dc2626] rounded flex items-center justify-center overflow-hidden"
                                                                            >
                                                                                {player.agentIcon ? (
                                                                                    <img
                                                                                        src={player.agentIcon}
                                                                                        alt={player.agent}
                                                                                        className="w-7 h-7 object-contain"
                                                                                    />
                                                                                ) : (
                                                                                    <span className="text-xs">
                                                                                        {player.agent[0]}
                                                                                    </span>
                                                                                )}
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
                                                                                    {player.score}
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