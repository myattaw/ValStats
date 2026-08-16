import { useCallback, useEffect, useState } from "react";
import { ChevronDown, Clock, Loader2, TrendingDown, TrendingUp } from "lucide-react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "./ui/collapsible";
import { Match } from "./Match/types/matchTypes";
import {
    API_BASE_URL,
    calculateADR,
    fetchMatchDetails,
    INITIAL_MATCHES_SIZE
} from "./Match/utils/matchUtils";
import { MatchDetailsPanel, MatchSkeleton } from "./Match/MatchComponents";

type LastKey = Record<string, string | number> | null;

export function MatchHistory({
                                 puuid,
                                 playerName,
                                 playerTag,
                                 selectedAct
                             }: {
    puuid?: string | null;
    playerName: string;
    playerTag: string;
    selectedAct: string;
}) {
    const [expandedMatch, setExpandedMatch] = useState<string | null>(null);
    const [loadingMatchId, setLoadingMatchId] = useState<string | null>(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [isInitialLoading, setIsInitialLoading] = useState(true);
    const [matches, setMatches] = useState<Match[]>([]);
    const [hoveredMatch, setHoveredMatch] = useState<string | null>(null);
    const [lastKey, setLastKey] = useState<LastKey>(null);
    const [hasMore, setHasMore] = useState(true);

    const isSeasonMode = selectedAct !== "all";

    const buildMatchesUrl = useCallback(
        (cursor?: LastKey) => {
            const params = new URLSearchParams({
                size: String(INITIAL_MATCHES_SIZE),
                act: selectedAct
            });

            if (cursor) {
                params.set("lastKey", encodeURIComponent(JSON.stringify(cursor)));
            }

            return `${API_BASE_URL}/matches/na/${encodeURIComponent(playerName)}/${encodeURIComponent(playerTag)}?${params.toString()}`;
        },
        [playerName, playerTag, selectedAct]
    );

    const fetchInitialMatches = useCallback(async () => {
        setIsInitialLoading(true);

        try {
            const res = await fetch(buildMatchesUrl());

            if (!res.ok) {
                console.error("Failed to fetch matches:", res.status);
                setMatches([]);
                setLastKey(null);
                setHasMore(false);
                return;
            }

            const json = await res.json();
            const data = Array.isArray(json?.data) ? json.data : [];

            data.sort((a: Match, b: Match) => b.date_raw - a.date_raw);

            setMatches(data);

            // Season mode is still not cursor-paginated on the backend, so don't show load-more there.
            setLastKey(json?.lastKey ?? null);
            setHasMore(!!json?.lastKey);

        } catch (e) {
            console.error("Error fetching matches:", e);
            setMatches([]);
            setLastKey(null);
            setHasMore(false);
        } finally {
            setIsInitialLoading(false);
        }
    }, [buildMatchesUrl, isSeasonMode]);

    useEffect(() => {
        setMatches([]);
        setExpandedMatch(null);
        setLoadingMatchId(null);
        setLoadingMore(false);
        setLastKey(null);
        setHasMore(true);
        fetchInitialMatches();
    }, [playerName, playerTag, selectedAct, fetchInitialMatches]);

    const RANK_NAMES = [
        "Unranked",
        "",
        "",
        "Iron 1", "Iron 2", "Iron 3",
        "Bronze 1", "Bronze 2", "Bronze 3",
        "Silver 1", "Silver 2", "Silver 3",
        "Gold 1", "Gold 2", "Gold 3",
        "Platinum 1", "Platinum 2", "Platinum 3",
        "Diamond 1", "Diamond 2", "Diamond 3",
        "Ascendant 1", "Ascendant 2", "Ascendant 3",
        "Immortal 1", "Immortal 2", "Immortal 3",
        "Radiant"
    ];

    const getRankName = (match: Match) => {
        if (match.rank && match.rank.trim() !== "") return match.rank;

        const idx = match.ranking_in_tier;
        if (typeof idx === "number" && idx >= 0 && idx < RANK_NAMES.length) {
            return RANK_NAMES[idx];
        }

        return "Unranked";
    };

    const handleLoadMore = async () => {
        if (!lastKey || loadingMore) return;

        setLoadingMore(true);

        try {
            const res = await fetch(buildMatchesUrl(lastKey));

            if (!res.ok) {
                console.error("Failed to load more matches:", res.status);
                return;
            }

            const json = await res.json();
            const newMatches = Array.isArray(json?.data) ? json.data : [];

            setMatches((prev) => {
                const existingIds = new Set(prev.map((m) => m.id));
                const unique = newMatches.filter((m: Match) => !existingIds.has(m.id));
                const combined = [...prev, ...unique];
                combined.sort((a, b) => b.date_raw - a.date_raw);
                return combined;
            });

            setLastKey(json?.lastKey ?? null);
            setHasMore(!!json?.lastKey);
        } catch (err) {
            console.error("Failed to load more matches:", err);
        } finally {
            setLoadingMore(false);
        }
    };

    const handleExpand = async (match: Match, isExpanded: boolean) => {
        if (loadingMatchId === match.id) return;

        if (!match.details) {
            setLoadingMatchId(match.id);
            setExpandedMatch(match.id);

            try {
                const details = await fetchMatchDetails(match.id);
                setMatches((prev) =>
                    prev.map((m) =>
                        m.id === match.id
                            ? { ...m, players: details.players, details, hasDetails: true }
                            : m
                    )
                );
            } catch (error) {
                console.error("Failed to load match details:", error);
            } finally {
                setLoadingMatchId(null);
            }
        } else {
            setExpandedMatch(isExpanded ? null : match.id);
        }
    };

    const canShowLoadMore = !isInitialLoading && hasMore;

    return (
        <div className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg overflow-hidden">
            <div className="border-b border-[#1a1a1a] p-6">
                <h3 className="text-white">Match History</h3>
            </div>

            <div className="p-6 space-y-4">
                {isInitialLoading ? (
                    Array.from({ length: 5 }).map((_, idx) => <MatchSkeleton key={idx} />)
                ) : (
                    <>
                        {matches.map((match) => {
                            const matchBgStyle = match.mapId
                                ? {
                                    backgroundImage: `url(https://media.valorant-api.com/maps/${match.mapId}/splash.png)`,
                                    backgroundPosition: "center",
                                    backgroundSize: "cover",
                                    backfaceVisibility: "hidden" as const
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
                                    open={isExpanded && !!match.details}
                                    onOpenChange={() => handleExpand(match, isExpanded)}
                                >
                                    <div>
                                        <CollapsibleTrigger
                                            className={`w-full text-left p-0 transition-colors relative overflow-hidden ${
                                                isExpanded && match.details
                                                    ? "rounded-t-lg border-t border-l border-r !border-b-0"
                                                    : "rounded-lg border"
                                            } ${borderColor}`}
                                            style={{ padding: 0 }}
                                            onMouseEnter={() => setHoveredMatch(match.id)}
                                            onMouseLeave={() => setHoveredMatch(null)}
                                        >
                                            <div className="relative p-5" style={matchBgStyle}>
                                                <div className="absolute inset-0 bg-black/75 z-0 pointer-events-none" />
                                                <div
                                                    className="absolute inset-0 z-10 pointer-events-none"
                                                    style={{ backgroundColor: overlayColor }}
                                                />
                                                <div
                                                    className="absolute inset-0 z-20 transition-opacity duration-200"
                                                    style={{
                                                        backgroundColor: hoverOverlayColor,
                                                        opacity: hoveredMatch === match.id ? 1 : 0
                                                    }}
                                                />

                                                <div className="flex items-center justify-between relative z-30">
                                                    <div className="flex items-center gap-4">
                                                        <div className="w-16 h-16 bg-black/30 rounded-lg flex items-center justify-center flex-shrink-0 overflow-hidden">
                                                            {match.agentIcon ? (
                                                                <img
                                                                    src={match.agentIcon}
                                                                    alt={match.agent}
                                                                    className="w-full h-full object-cover"
                                                                />
                                                            ) : (
                                                                <span className="text-sm">
                                                                    {match.agent && match.agent[0]}
                                                                </span>
                                                            )}
                                                        </div>

                                                        <div>
                                                            <div className="flex items-center gap-3 mb-2">
                                                                <span className="text-white">{match.map}</span>
                                                                <span className={`px-3 py-1 rounded ${resultBadgeColor}`}>
                                                                    {match.result}
                                                                </span>
                                                                <span className="text-gray-400 px-2 py-1 rounded bg-black/30">
                                                                    {match.score}-{match.enemy_score}
                                                                </span>
                                                                <div className="flex items-center gap-1.5 px-2 py-1 rounded bg-black/30">
                                                                    <img
                                                                        src={`https://media.valorant-api.com/competitivetiers/03621f52-342b-cf4e-4f86-9350a49c6d04/${match.rank_tier}/smallicon.png`}
                                                                        alt="Rank Icon"
                                                                        className="w-3 h-3"
                                                                    />
                                                                    <span className="text-xs text-gray-300">
                                                                        {getRankName(match)}
                                                                    </span>
                                                                </div>

                                                                {match.rrChange !== 0 && (
                                                                    <div
                                                                        className={`flex items-center gap-1 px-2 py-1 rounded bg-black/30 ${rrChangeColor}`}
                                                                    >
                                                                        {match.rrChange > 0 ? (
                                                                            <TrendingUp className="w-3 h-3" />
                                                                        ) : (
                                                                            <TrendingDown className="w-3 h-3" />
                                                                        )}
                                                                        <span className="text-xs">
                                                                            {match.rrChange > 0 ? "+" : ""}
                                                                            {match.rrChange} RR
                                                                        </span>
                                                                    </div>
                                                                )}
                                                            </div>

                                                            <div className="flex items-center gap-4">
                                                                <span className="text-gray-400">Agent: {match.agent}</span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">KDA: {match.kda}</span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">ACS: {match.acs}</span>
                                                                <span className="text-gray-400">•</span>
                                                                <span className="text-gray-400">
                                                                    ADR: {match.adr}
                                                                </span>
                                                            </div>
                                                        </div>
                                                    </div>

                                                    <div className="flex items-center gap-4">
                                                        <div className="flex items-center gap-2 text-gray-400">
                                                            <Clock className="w-4 h-4" />
                                                            <span>{match.date_raw
                                                                  ? new Date(match.date_raw * 1000).toLocaleString()
                                                                  : "Unknown"}
                                                            </span>
                                                        </div>

                                                        {loadingMatchId === match.id ? (
                                                            <Loader2 className="w-5 h-5 text-gray-400 animate-spin" />
                                                        ) : (
                                                            <ChevronDown
                                                                className={`w-5 h-5 text-gray-400 transition-transform ${
                                                                    isExpanded && match.details ? "rotate-180" : ""
                                                                }`}
                                                            />
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                        </CollapsibleTrigger>

                                        {isExpanded && match.details && (
                                            <CollapsibleContent>
                                                <MatchDetailsPanel details={match.details} roundsPlayed={match.rounds_played} viewerPuuid={puuid}/>
                                            </CollapsibleContent>
                                        )}
                                    </div>
                                </Collapsible>
                            );
                        })}
                    </>
                )}

                {canShowLoadMore && (
                    <button
                        onClick={handleLoadMore}
                        disabled={loadingMore || !lastKey}
                        className="w-full py-4 bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg text-white hover:bg-[#242424] transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                    >
                        {loadingMore ? (
                            <>
                                <Loader2 className="w-4 h-4 animate-spin" />
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
