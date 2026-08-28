import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, ChevronsDown, Clock, Loader2, MapPin, Sparkles, TrendingDown, TrendingUp } from "lucide-react";
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

function normalizeMatch(raw: Match & Record<string, any>): Match {
    const suppliedAdr = raw.adr ?? raw.ADR ?? raw.average_damage_per_round ?? raw.averageDamagePerRound ?? raw.stats?.adr;
    const numericAdr = Number(suppliedAdr);
    const damage = Number(raw.damage_made ?? raw.damageMade ?? raw.stats?.damage_made ?? raw.stats?.damageMade);
    const rounds = Number(raw.rounds_played ?? raw.roundsPlayed);
    const adr = Number.isFinite(numericAdr) && numericAdr > 0
        ? Math.round(numericAdr)
        : Number.isFinite(damage) && damage > 0 && rounds > 0
            ? Math.round(damage / rounds)
            : 0;

    const server = raw.server ?? raw.cluster ?? raw.meta?.cluster;

    return {...raw, adr, server};
}

function formatMatchDate(dateRaw: number, timestamp?: string) {
    const numericDate = Number(dateRaw);
    const date = Number.isFinite(numericDate) && numericDate > 0
        ? new Date(numericDate > 10_000_000_000 ? numericDate : numericDate * 1000)
        : timestamp
            ? new Date(timestamp)
            : null;

    if (!date || Number.isNaN(date.getTime())) return "Unknown date";

    const day = new Intl.DateTimeFormat(undefined, {
        month: "short",
        day: "numeric"
    }).format(date);
    const time = new Intl.DateTimeFormat(undefined, {
        hour: "numeric",
        minute: "2-digit"
    }).format(date);

    return `${day} · ${time}`;
}

function isRecentMatch(match: Match) {
    const raw = Number(match.date_raw);
    const occurredAt = Number.isFinite(raw) && raw > 0
        ? (raw > 10_000_000_000 ? raw : raw * 1000)
        : match.timestamp
            ? new Date(match.timestamp).getTime()
            : 0;

    const age = Date.now() - occurredAt;
    return occurredAt > 0 && age >= 0 && age <= 60 * 60 * 1000;
}

function getOrdinal(value: number) {
    const mod100 = value % 100;
    if (mod100 >= 11 && mod100 <= 13) return `${value}th`;
    switch (value % 10) {
        case 1: return `${value}st`;
        case 2: return `${value}nd`;
        case 3: return `${value}rd`;
        default: return `${value}th`;
    }
}

function getLobbyPlacement(players: Match["players"], viewerPuuid?: string | null) {
    if (!players?.length || !viewerPuuid) return null;

    const sortedPlayers = [...players].sort((a, b) => b.score - a.score);
    const placementIndex = sortedPlayers.findIndex((player) => player.puuid === viewerPuuid);
    if (placementIndex < 0) return null;
    if (placementIndex === 0) return "MVP";

    const viewer = sortedPlayers[placementIndex];
    const isTeamMvp = sortedPlayers
        .filter((player) => player.team?.toLowerCase() === viewer.team?.toLowerCase())
        .every((player) => player.puuid === viewer.puuid || player.score <= viewer.score);

    return isTeamMvp ? "TEAM MVP" : getOrdinal(placementIndex + 1);
}

export function MatchHistory({
                                 puuid,
                                 region,
                                 playerName,
                                 playerTag,
                                 selectedAct,
                                 selectedMode,
                                 onRefreshingChange,
                                 onRefreshComplete
                             }: {
    puuid?: string | null;
    region: string;
    playerName: string;
    playerTag: string;
    selectedAct: string;
    selectedMode: string;
    onRefreshingChange?: (refreshing: boolean) => void;
    onRefreshComplete?: () => void;
}) {
    const [expandedMatch, setExpandedMatch] = useState<string | null>(null);
    const [loadingMatchId, setLoadingMatchId] = useState<string | null>(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [isInitialLoading, setIsInitialLoading] = useState(true);
    const [isBackgroundRefreshing, setIsBackgroundRefreshing] = useState(false);
    const [matches, setMatches] = useState<Match[]>([]);
    const [newMatchIds, setNewMatchIds] = useState<Set<string>>(() => new Set());
    const visibleMatchIds = useRef<Set<string>>(new Set());
    const [hoveredMatch, setHoveredMatch] = useState<string | null>(null);
    const [lastKey, setLastKey] = useState<LastKey>(null);
    const [hasMore, setHasMore] = useState(true);

    const isSeasonMode = selectedAct !== "all";

    const buildMatchesUrl = useCallback(
        (cursor?: LastKey) => {
            const params = new URLSearchParams({
                size: String(INITIAL_MATCHES_SIZE),
                act: selectedAct,
                mode: selectedMode
            });

            if (cursor) {
                params.set("lastKey", encodeURIComponent(JSON.stringify(cursor)));
            }

            return `${API_BASE_URL}/matches/${encodeURIComponent(region)}/${encodeURIComponent(playerName)}/${encodeURIComponent(playerTag)}?${params.toString()}`;
        },
        [region, playerName, playerTag, selectedAct, selectedMode]
    );

    const fetchInitialMatches = useCallback(async (showLoading = true) => {
        if (showLoading) setIsInitialLoading(true);

        try {
            const res = await fetch(buildMatchesUrl());

            if (!res.ok) {
                console.error("Failed to fetch matches:", res.status);
                if (showLoading) {
                    setMatches([]);
                    setLastKey(null);
                    setHasMore(false);
                }
                return;
            }

            const json = await res.json();
            const data: Match[] = Array.isArray(json?.data) ? json.data.map(normalizeMatch) : [];

            data.sort((a: Match, b: Match) => b.date_raw - a.date_raw);

            if (showLoading || data.length > 0) {
                if (!showLoading) {
                    const addedIds = data
                        .filter((match) => !visibleMatchIds.current.has(match.id))
                        .map((match) => match.id);
                    if (addedIds.length > 0) {
                        setNewMatchIds((current) => new Set([...current, ...addedIds]));
                    }
                }
                visibleMatchIds.current = new Set(data.map((match) => match.id));
                setMatches(data);
                // Season mode is still not cursor-paginated on the backend, so don't show load-more there.
                setLastKey(json?.lastKey ?? null);
                setHasMore(!!json?.lastKey);
            }

        } catch (e) {
            console.error("Error fetching matches:", e);
            if (showLoading) {
                setMatches([]);
                setLastKey(null);
                setHasMore(false);
            }
        } finally {
            if (showLoading) setIsInitialLoading(false);
        }
    }, [buildMatchesUrl, isSeasonMode]);

    const refreshMatches = useCallback(async () => {
        setIsBackgroundRefreshing(true);
        try {
            const baseUrl = `${API_BASE_URL}/matches/${encodeURIComponent(region)}/${encodeURIComponent(playerName)}/${encodeURIComponent(playerTag)}`;
            const statusResponse = await fetch(`${baseUrl}/refresh-status`);
            if (!statusResponse.ok) return;
            const statusPayload = await statusResponse.json();
            if (statusPayload?.data?.refreshRequired !== true) return;

            onRefreshingChange?.(true);
            const refreshResponse = await fetch(`${baseUrl}/refresh`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: "{}"
            });
            if (!refreshResponse.ok) return;

            const deadline = Date.now() + 2 * 60 * 1000;
            while (Date.now() < deadline) {
                await new Promise((resolve) => window.setTimeout(resolve, 2500));
                const nextStatusResponse = await fetch(`${baseUrl}/refresh-status`);
                if (!nextStatusResponse.ok) continue;
                const nextStatus = await nextStatusResponse.json();
                if (nextStatus?.data?.refreshRequired !== true) break;
            }

            await fetchInitialMatches(false);
            onRefreshComplete?.();
        } catch (error) {
            console.error("Background match refresh failed", error);
        } finally {
            setIsBackgroundRefreshing(false);
            onRefreshingChange?.(false);
        }
    }, [region, playerName, playerTag, fetchInitialMatches, onRefreshingChange, onRefreshComplete]);

    useEffect(() => {
        setMatches([]);
        setNewMatchIds(new Set());
        visibleMatchIds.current = new Set();
        setExpandedMatch(null);
        setLoadingMatchId(null);
        setLoadingMore(false);
        setLastKey(null);
        setHasMore(true);
        void fetchInitialMatches().then(refreshMatches);
        return () => onRefreshingChange?.(false);
    }, [playerName, playerTag, selectedAct, selectedMode, fetchInitialMatches, refreshMatches, onRefreshingChange]);

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

    const getCompactRankName = (match: Match) => {
        const rank = getRankName(match);
        const matchResult = rank.match(/^(Iron|Bronze|Silver|Gold|Platinum|Diamond|Ascendant|Immortal)\s+(\d)$/i);
        if (!matchResult) return rank.toLowerCase() === "radiant" ? "RAD" : "UR";
        const [, tier, division] = matchResult;
        const abbreviations: Record<string, string> = {
            iron: "I", bronze: "B", silver: "S", gold: "G", platinum: "P",
            diamond: "D", ascendant: "A", immortal: "IMM"
        };
        return `${abbreviations[tier.toLowerCase()]}${division}`;
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
            const newMatches: Match[] = Array.isArray(json?.data) ? json.data.map(normalizeMatch) : [];

            setMatches((prev) => {
                const existingIds = new Set(prev.map((m) => m.id));
                const unique = newMatches.filter((m: Match) => !existingIds.has(m.id));
                const combined = [...prev, ...unique];
                combined.sort((a, b) => b.date_raw - a.date_raw);
                visibleMatchIds.current = new Set(combined.map((match) => match.id));
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
        <section className="match-history-panel">
            <div className="match-history-heading">
                <h3 className="text-white">Match History</h3>
            </div>

            <div className="match-history-content space-y-4">
                {isInitialLoading || (isBackgroundRefreshing && matches.length === 0) ? (
                    Array.from({ length: 5 }).map((_, idx) => <MatchSkeleton key={idx} />)
                ) : (
                    <>
                        {matches.map((match) => {
                            const detailAdr = calculateADR(match, puuid ?? undefined);
                            const displayedAdr = match.adr && match.adr > 0 ? Math.round(match.adr) : detailAdr;
                            const matchBgStyle = match.mapId
                                ? {
                                    backgroundImage: `url(https://media.valorant-api.com/maps/${match.mapId}/splash.png)`,
                                    backgroundPosition: "center",
                                    backgroundSize: "cover",
                                    backfaceVisibility: "hidden" as const
                                }
                                : undefined;

                            const isExpanded = expandedMatch === match.id;
                            const isNew = newMatchIds.has(match.id);
                            const isRecent = isRecentMatch(match);
                            const lobbyPlacement = getLobbyPlacement(match.players, puuid);
                            const isVictory = match.result === "Victory";
                            const borderColor = isVictory ? "match-victory" : "match-defeat";
                            const overlayColor = "rgba(8, 13, 18, 0.70)";
                            const hoverOverlayColor = "rgba(255, 255, 255, 0.025)";
                            const resultBadgeColor = isVictory
                                ? "text-[#4ade80]"
                                : "text-[#f87171]";
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
                                            <div className="match-card-body relative p-5" style={matchBgStyle}>
                                                <div className="match-map-dimmer absolute inset-0 z-0 pointer-events-none" />
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

                                                <div className="match-entry-grid relative z-30">
                                                    <div className="match-agent-group">
                                                        <div className="match-card-agent bg-black/30 rounded-lg flex items-center justify-center flex-shrink-0 overflow-hidden">
                                                            {match.agentIcon ? (
                                                                <img src={match.agentIcon} alt={match.agent} className="w-full h-full object-cover" />
                                                            ) : (
                                                                <span className="text-sm">{match.agent && match.agent[0]}</span>
                                                            )}
                                                        </div>
                                                        <span className="match-agent-copy">
                                                            <span className="match-agent-name" title={match.agent}>{match.agent}</span>
                                                            <span className="match-agent-badges">
                                                                {lobbyPlacement && (
                                                                    <span className={`match-placement-badge ${lobbyPlacement.includes("MVP") ? "is-mvp" : ""}`}>
                                                                        {lobbyPlacement}
                                                                    </span>
                                                                )}
                                                                {(isNew || isRecent) && (
                                                                    <span
                                                                        className="match-state-badge"
                                                                        style={isNew
                                                                            ? { backgroundColor: "rgba(168, 85, 247, 0.25)", color: "#d8b4fe" }
                                                                            : { backgroundColor: "rgba(245, 158, 11, 0.25)", color: "#fcd34d" }}
                                                                        title={isNew ? "Loaded during this refresh" : "Played within the last hour"}
                                                                    >
                                                                        {isNew ? <Sparkles /> : <Clock />}
                                                                        <span>{isNew ? "New" : "Recent"}</span>
                                                                    </span>
                                                                )}
                                                            </span>
                                                        </span>
                                                    </div>

                                                    <span className="match-separator match-separator-major" aria-hidden="true" />

                                                    <div className="match-main-info">
                                                        <div className="match-primary-row">
                                                            <span className="match-map-name" title={match.map}>{match.map}</span>
                                                            <span className="match-result-score">
                                                                <span className="match-result">{match.result}</span>
                                                                <span className="match-score">{match.score} <span aria-hidden="true">–</span> {match.enemy_score}</span>
                                                            </span>
                                                            <span className="match-rank-group">
                                                                <img
                                                                    src={`https://media.valorant-api.com/competitivetiers/03621f52-342b-cf4e-4f86-9350a49c6d04/${match.rank_tier}/smallicon.png`}
                                                                    alt=""
                                                                    className="match-rank-icon"
                                                                />
                                                                <span className="rank-name-full">{getRankName(match)}</span>
                                                                <span className="rank-name-compact" aria-label={getRankName(match)}>{getCompactRankName(match)}</span>
                                                            </span>
                                                            <span className={`match-rr-change ${rrChangeColor}`}>
                                                                {match.rrChange !== 0 && (
                                                                    <>
                                                                        {match.rrChange > 0 ? <TrendingUp /> : <TrendingDown />}
                                                                        <span>{match.rrChange > 0 ? "+" : ""}{match.rrChange}<span className="rr-suffix"> RR</span></span>
                                                                    </>
                                                                )}
                                                            </span>
                                                        </div>

                                                        <div className="match-performance-row">
                                                            <span><small>KDA</small><strong>{match.kda}</strong></span>
                                                            <i aria-hidden="true" />
                                                            <span><small>ACS</small><strong>{match.acs}</strong></span>
                                                            <i aria-hidden="true" />
                                                            <span><small>ADR</small><strong>{displayedAdr || "—"}</strong></span>
                                                        </div>
                                                    </div>

                                                    <span className="match-separator match-separator-major" aria-hidden="true" />

                                                    <div className="match-card-meta">
                                                        <div className="match-server-time">
                                                            {match.server && (
                                                                <div className="match-server-time-row">
                                                                    <MapPin />
                                                                    <span className="truncate">{match.server}</span>
                                                                </div>
                                                            )}
                                                            <div className="match-server-time-row">
                                                                <Clock />
                                                                <time dateTime={match.timestamp}>{formatMatchDate(match.date_raw, match.timestamp)}</time>
                                                            </div>
                                                        </div>
                                                    </div>

                                                    <span className="match-card-chevron" aria-hidden="true">
                                                        {loadingMatchId === match.id ? (
                                                            <Loader2 className="animate-spin" />
                                                        ) : (
                                                            <ChevronDown className={`transition-all duration-200 ${isExpanded && match.details ? "rotate-180" : ""}`} />
                                                        )}
                                                    </span>
                                                </div>

                                                <div className="match-card-summary match-card-summary-legacy flex items-center justify-between relative z-30" aria-hidden="true">
                                                    <div className="match-card-primary flex items-center gap-4">
                                                        <div className="match-card-agent w-16 h-16 bg-black/30 rounded-lg flex items-center justify-center flex-shrink-0 overflow-hidden">
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

                                                        <div className="match-card-copy">
                                                            <div className="match-card-badges flex flex-wrap items-center gap-2 mb-2">
                                                                <span className="match-map-name text-white">{match.map}</span>
                                                                <span className={`px-3 py-1 rounded ${resultBadgeColor}`}>
                                                                    {match.result}
                                                                </span>
                                                                <span className="match-score text-gray-400 px-2 py-1 rounded bg-black/30">
                                                                    {match.score}-{match.enemy_score}
                                                                </span>
                                                                <div className="flex items-center gap-1.5 px-2 py-1 rounded bg-black/30">
                                                                    <img
                                                                        src={`https://media.valorant-api.com/competitivetiers/03621f52-342b-cf4e-4f86-9350a49c6d04/${match.rank_tier}/smallicon.png`}
                                                                        alt="Rank Icon"
                                                                        className="w-3 h-3"
                                                                    />
                                                                    <span className="rank-name-full text-xs text-gray-300">
                                                                        {getRankName(match)}
                                                                    </span>
                                                                    <span className="rank-name-compact text-xs text-gray-300" aria-label={getRankName(match)}>
                                                                        {getCompactRankName(match)}
                                                                    </span>
                                                                </div>

                                                                {match.rrChange !== 0 && (
                                                                    <div
                                                                        className={`flex shrink-0 items-center gap-1 px-2 py-1 rounded bg-black/30 ${rrChangeColor}`}
                                                                    >
                                                                        {match.rrChange > 0 ? (
                                                                            <TrendingUp className="w-3 h-3" />
                                                                        ) : (
                                                                            <TrendingDown className="w-3 h-3" />
                                                                        )}
                                                                        <span className="text-xs">
                                                                            {match.rrChange > 0 ? "+" : ""}
                                                                            {match.rrChange}<span className="rr-suffix"> RR</span>
                                                                        </span>
                                                                    </div>
                                                                )}
                                                                {(isNew || isRecent) && (
                                                                    <div
                                                                        className="flex shrink-0 items-center gap-1 px-2 py-1 rounded"
                                                                        style={isNew
                                                                            ? { backgroundColor: "rgba(168, 85, 247, 0.25)", color: "#d8b4fe" }
                                                                            : { backgroundColor: "rgba(245, 158, 11, 0.25)", color: "#fcd34d" }}
                                                                        title={isNew ? "Loaded during this refresh" : "Played within the last hour"}
                                                                    >
                                                                        {isNew ? (
                                                                            <Sparkles className="w-3 h-3" />
                                                                        ) : (
                                                                            <Clock className="w-3 h-3" />
                                                                        )}
                                                                        <span className="text-xs">{isNew ? "New" : "Recent"}</span>
                                                                    </div>
                                                                )}
                                                            </div>

                                                            <div className="match-card-stats flex w-full min-w-0 items-center justify-between gap-3 rounded bg-black/30 px-2 py-1 text-xs text-gray-400">
                                                                <span className="match-agent-name min-w-0 truncate">{match.agent}</span>
                                                                <span className="shrink-0 whitespace-nowrap"><small>KDA</small>{match.kda}</span>
                                                                <span className="shrink-0 whitespace-nowrap"><small>ACS</small>{match.acs}</span>
                                                                <span className="shrink-0 whitespace-nowrap"><small>ADR</small>{displayedAdr || "—"}</span>
                                                            </div>
                                                        </div>
                                                    </div>

                                                    <div className="match-card-meta flex items-center gap-3">
                                                        <div className="match-server-time">
                                                            {match.server && (
                                                                <div className="match-server-time-row">
                                                                    <MapPin className="h-3.5 w-3.5 flex-shrink-0" />
                                                                    <span className="truncate">{match.server}</span>
                                                                </div>
                                                            )}
                                                            <div className="match-server-time-row">
                                                                <Clock className="h-3.5 w-3.5" />
                                                                <time dateTime={match.timestamp}>{formatMatchDate(match.date_raw, match.timestamp)}</time>
                                                            </div>
                                                        </div>

                                                        {loadingMatchId === match.id ? (
                                                            <Loader2 className="h-5 w-5 animate-spin text-gray-400" />
                                                        ) : (
                                                            <ChevronDown className={`h-5 w-5 text-gray-400 transition-all duration-200 hover:text-white ${isExpanded && match.details ? "rotate-180" : ""}`} />
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                        </CollapsibleTrigger>

                                        {match.details && (
                                            <CollapsibleContent className="match-details-collapsible">
                                                <MatchDetailsPanel details={match.details} roundsPlayed={match.rounds_played} viewerPuuid={puuid} mapId={match.mapId}/>
                                            </CollapsibleContent>
                                        )}
                                    </div>
                                </Collapsible>
                            );
                        })}
                    </>
                )}

                {canShowLoadMore && (
                    <div className="load-more-row">
                        <span />
                        <button
                            onClick={handleLoadMore}
                            disabled={loadingMore || !lastKey}
                            className="load-more-button"
                            aria-label={loadingMore ? "Loading more matches" : "Load more matches"}
                        >
                            <span className="load-more-icon">
                                {loadingMore ? <Loader2 className="animate-spin" /> : <ChevronsDown />}
                            </span>
                            <span><strong>{loadingMore ? "Loading matches" : "Load more"}</strong><small>{loadingMore ? "Fetching match history…" : "Show older matches"}</small></span>
                        </button>
                        <span />
                    </div>
                )}
            </div>
        </section>
    );
}
