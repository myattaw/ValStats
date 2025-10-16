import {useEffect, useState} from "react";
import {ChevronDown, Clock, Loader2, TrendingDown, TrendingUp} from "lucide-react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "./ui/collapsible";
import {Match, MatchHistoryProps} from './Match/types/matchTypes';
import {
    API_BASE_URL,
    buildMmrMap,
    calculateADR,
    DETAILED_MATCHES_SIZE,
    fetchMatchDetails,
    fetchMoreMatches,
    INITIAL_MATCHES_SIZE,
    processRecentMatches,
    processStoredMatches
} from './Match/utils/matchUtils';

import {MatchSkeleton, TeamDisplay} from './Match/MatchComponents';

export function MatchHistory({puuid}: MatchHistoryProps) {
    const [expandedMatch, setExpandedMatch] = useState<string | null>(null);
    const [loadingMatchId, setLoadingMatchId] = useState<string | null>(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [isInitialLoading, setIsInitialLoading] = useState(true);
    const [matches, setMatches] = useState<Match[]>([]);
    const [hoveredMatch, setHoveredMatch] = useState<string | null>(null);
    const [page, setPage] = useState(1);

    // Fetch initial match data
    useEffect(() => {
        setPage(1); // Reset page on puuid change
        fetchInitialMatches();
    }, [puuid]);

    // Helper functions
    const fetchInitialMatches = async () => {
        setIsInitialLoading(true);

        try {
            const [recentRes, storedRes, mmrRes] = await Promise.all([
                fetch(`${API_BASE_URL}/recent-matches/na/rages/alt?size=${DETAILED_MATCHES_SIZE}`),
                fetch(`${API_BASE_URL}/stored-matches/na/rages/alt?size=${INITIAL_MATCHES_SIZE}`),
                fetch(`${API_BASE_URL}/mmr-history/na/rages/alt`),
            ]);

            const recentJson = await recentRes.json();
            const storedJson = await storedRes.json();
            const mmrJson = await mmrRes.json();

            // Build MMR map
            const mmrMap = buildMmrMap(mmrJson.data || []);

            // Process matches
            const recentMatches = processRecentMatches(recentJson.data || [], mmrMap, mmrJson, puuid);
            const detailedMatchIds = new Set(recentMatches.map(m => m.id));
            const storedMatches = processStoredMatches(
                storedJson.data || [],
                mmrMap,
                detailedMatchIds
            );

            // Combine and sort matches
            const allMatches = [...recentMatches, ...storedMatches];
            allMatches.sort((a, b) => b.date_raw - a.date_raw);

            setMatches(allMatches);
        } catch (e) {
            console.error("Error fetching matches:", e);
            setMatches([]);
        } finally {
            setIsInitialLoading(false);
        }
    };

    // Handle match click
    const handleMatchClick = async (matchId: string) => {
        const match = matches.find((m) => m.id === matchId);
        if (!match) return;

        if (expandedMatch === matchId) {
            setExpandedMatch(null);
            return;
        }

        if (match.hasDetails && match.players) {
            setExpandedMatch(matchId);
            return;
        }

        setLoadingMatchId(matchId);
        try {
            const playerStats = await fetchMatchDetails(matchId);

            setMatches(prev =>
                prev.map(m =>
                    m.id === matchId
                        ? {...m, players: playerStats, hasDetails: true}
                        : m
                )
            );
            setExpandedMatch(matchId);
        } catch (error) {
            console.error("Failed to load match details:", error);
        } finally {
            setLoadingMatchId(null);
        }
    };

    // Calculate ADR for a player
    const calculateADRForMatch = (match: Match): number => {
        return calculateADR(match, puuid);
    };

    // Render match teams
    const renderMatchTeams = (match: Match) => {
        if (!match.players) return null;

        const allPlayers = match.players;
        const teams = match.teams;

        let topTeamName = "blue";
        let bottomTeamName = "red";

        if (teams) {
            if (match.result === "Victory") {
                topTeamName = teams.blue.has_won ? "blue" : "red";
                bottomTeamName = teams.blue.has_won ? "red" : "blue";
            } else {
                topTeamName = teams.blue.has_won ? "red" : "blue";
                bottomTeamName = teams.blue.has_won ? "blue" : "red";
            }
        }

        const topTeamPlayers = allPlayers
            .filter(p => p.team?.toLowerCase() === topTeamName)
            .sort((a, b) => b.score - a.score);

        const bottomTeamPlayers = allPlayers
            .filter(p => p.team?.toLowerCase() === bottomTeamName)
            .sort((a, b) => b.score - a.score);

        const topLabel = (teams && teams[topTeamName]?.has_won) ? "Victory" : "Defeat";
        const bottomLabel = (teams && teams[bottomTeamName]?.has_won) ? "Victory" : "Defeat";

        return (
            <>
                <TeamDisplay
                    label={topLabel === "Victory" ? "Winning Team (Victory)" : "Losing Team (Defeat)"}
                    players={topTeamPlayers}
                    isVictory={topLabel === "Victory"}
                    rounds_played={match.rounds_played}
                    isBottom={false}
                />
                <TeamDisplay
                    label={bottomLabel === "Victory" ? "Winning Team (Victory)" : "Losing Team (Defeat)"}
                    players={bottomTeamPlayers}
                    isVictory={bottomLabel === "Victory"}
                    rounds_played={match.rounds_played}
                    isBottom={true}
                />
            </>
        );
    };

    // Fallback rank names for tiers 0 (Unranked), 3-27 (Iron 1 to Radiant)
    const RANK_NAMES = [
        "Unranked", // 0
        "", "", // 1, 2 (unused)
        "Iron 1", "Iron 2", "Iron 3", // 3-5
        "Bronze 1", "Bronze 2", "Bronze 3", // 6-8
        "Silver 1", "Silver 2", "Silver 3", // 9-11
        "Gold 1", "Gold 2", "Gold 3", // 12-14
        "Platinum 1", "Platinum 2", "Platinum 3", // 15-17
        "Diamond 1", "Diamond 2", "Diamond 3", // 18-20
        "Ascendant 1", "Ascendant 2", "Ascendant 3", // 21-23
        "Immortal 1", "Immortal 2", "Immortal 3", // 24-26
        "Radiant" // 27
    ];

    // Helper to get fallback rank name
    const getRankName = (match: Match) => {
        if (match.rank && match.rank.trim() !== "") return match.rank;
        const idx = match.ranking_in_tier;
        if (typeof idx === "number" && idx >= 0 && idx < RANK_NAMES.length) {
            return RANK_NAMES[idx];
        }
        return "Unranked";
    };

    // Handle loading more matches
    const handleLoadMore = async () => {
        setLoadingMore(true);
        try {
            const nextPage = page + 1;
            const newMatches = await fetchMoreMatches(nextPage);
            setMatches(prev => {
                const existingIds = new Set(prev.map(m => m.id));
                const uniqueNewMatches = newMatches.filter(m => !existingIds.has(m.id));
                const combined = [...prev, ...uniqueNewMatches];
                combined.sort((a, b) => b.date_raw - a.date_raw);
                return combined;
            });
            setPage(nextPage);
        } catch (error) {
            console.error("Failed to load more matches:", error);
        } finally {
            setLoadingMore(false);
        }
    };

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
                            const matchBgStyle = match.mapId
                                ? {
                                    backgroundImage: `url(https://media.valorant-api.com/maps/${match.mapId}/splash.png)`,
                                    backgroundPosition: "center",
                                    backgroundSize: "cover",
                                }
                                : undefined;

                            const isExpanded = expandedMatch === match.id;
                            const isVictory = match.result === "Victory";
                            const borderColor = isVictory ? "border-[#4ade80]" : "border-[#f87171]";
                            const overlayColor = isVictory
                                ? "rgba(74, 222, 128, 0.1)"
                                : "rgba(248, 113, 113, 0.1)";
                            const hoverOverlayColor = isVictory
                                ? "rgba(74, 222, 128, 0.15)"
                                : "rgba(248, 113, 113, 0.15)";
                            const resultBadgeColor = isVictory
                                ? "bg-[#4ade80]/20 text-[#4ade80]"
                                : "bg-[#f87171]/20 text-[#f87171]";
                            const rrChangeColor = match.rrChange > 0 ? "text-[#4ade80]" : "text-[#f87171]";

                            return (
                                <Collapsible
                                    key={match.id}
                                    open={isExpanded}
                                    onOpenChange={() => handleMatchClick(match.id)}
                                >
                                    <div>
                                        <CollapsibleTrigger
                                            className={`w-full text-left p-0 transition-colors relative overflow-hidden ${
                                                isExpanded
                                                    ? "rounded-t-lg border-t border-l border-r !border-b-0"
                                                    : "rounded-lg border"
                                            } ${borderColor}`}
                                            style={{padding: 0}}
                                            onMouseEnter={() => setHoveredMatch(match.id)}
                                            onMouseLeave={() => setHoveredMatch(null)}
                                        >
                                            <div
                                                className="relative p-5"
                                                style={matchBgStyle}
                                            >
                                                {/* Background overlays */}
                                                <div className="absolute inset-0 bg-black/75 z-0 pointer-events-none"/>
                                                <div
                                                    className="absolute inset-0 z-10 pointer-events-none"
                                                    style={{backgroundColor: overlayColor}}
                                                />
                                                <div
                                                    className="absolute inset-0 z-20 transition-opacity duration-200"
                                                    style={{
                                                        backgroundColor: hoverOverlayColor,
                                                        opacity: hoveredMatch === match.id ? 1 : 0,
                                                    }}
                                                />

                                                {/* Match content */}
                                                <div className="flex items-center justify-between relative z-30">
                                                    <div className="flex items-center gap-4">
                                                        {/* Agent icon */}
                                                        <div
                                                            className="w-16 h-16 bg-black/30 rounded-lg flex items-center justify-center flex-shrink-0 overflow-hidden">
                                                            {match.agentIcon ? (
                                                                <img
                                                                    src={match.agentIcon}
                                                                    alt={match.agent}
                                                                    className="w-10 h-10 object-contain"
                                                                />
                                                            ) : (
                                                                <span className="text-sm">
                                  {match.agent && match.agent[0]}
                                </span>
                                                            )}
                                                        </div>

                                                        {/* Match details */}
                                                        <div>
                                                            <div className="flex items-center gap-3 mb-2">
                                                                <span className="text-white">{match.map}</span>
                                                                <span
                                                                    className={`px-3 py-1 rounded ${resultBadgeColor}`}>
                                                                    {match.result}
                                                                </span>
                                                                <span
                                                                    className="text-gray-400 px-2 py-1 rounded bg-black/30">
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
                                                                        {getRankName(match)}
                                                                    </span>
                                                                </div>
                                                                {/* Only show RR change if not 0 */}
                                                                {match.rrChange !== 0 && (
                                                                    <div
                                                                        className={`flex items-center gap-1 px-2 py-1 rounded bg-black/30 ${rrChangeColor}`}>
                                                                        {match.rrChange > 0 ? (
                                                                            <TrendingUp className="w-3 h-3"/>
                                                                        ) : (
                                                                            <TrendingDown className="w-3 h-3"/>
                                                                        )}
                                                                        <span className="text-xs">
                                                                            {match.rrChange > 0 ? "+" : ""}
                                                                            {match.rrChange} RR
                                                                        </span>
                                                                    </div>
                                                                )}
                                                            </div>

                                                            {/* Match stats */}
                                                            <div className="flex items-center gap-4">
                                <span className="text-gray-400">
                                  Agent: {match.agent}
                                </span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">
                                  KDA: {match.kda}
                                </span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">
                                  ACS: {match.acs}
                                </span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">
                                  ADR: {calculateADRForMatch(match)}
                                </span>
                                                            </div>
                                                        </div>
                                                    </div>

                                                    {/* Timestamp and expand button */}
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
                                                                    isExpanded ? "rotate-180" : ""
                                                                }`}
                                                            />
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                        </CollapsibleTrigger>

                                        {/* Expanded match content */}
                                        <CollapsibleContent>
                                            {match.players ? (
                                                <div>
                                                    {renderMatchTeams(match)}
                                                </div>
                                            ) : (
                                                <div
                                                    className="p-5 border-l border-r border-b rounded-b-lg bg-[#1a1a1a] flex justify-center">
                                                    <Loader2 className="w-6 h-6 text-gray-400 animate-spin"/>
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