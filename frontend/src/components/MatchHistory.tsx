import {useEffect, useState} from "react";
import {ChevronDown, Clock, Loader2, TrendingDown, TrendingUp} from "lucide-react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "./ui/collapsible";
import {Skeleton} from "./ui/skeleton";

// Types
interface PlayerStats {
    puuid: string;
    name: string;
    agent: string;
    kills: number;
    deaths: number;
    assists: number;
    score: number;
    damage_made: number;
    headshot: number;
    agentIcon?: string;
    team?: string;
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
    date_raw: number;
    rank: string;
    ranking_in_tier: number;
    rrChange: number;
    rounds_played: number;
    players?: PlayerStats[];
    agentIcon?: string;
    teams?: any;
    hasDetails?: boolean;
    puuid?: string;
}

interface MatchHistoryProps {
    puuid?: string;
}

// Constants
const API_BASE_URL = "http://localhost:64457/api/valorant";
const DEFAULT_PUUID = "37654ff9-b560-5b0f-a2bb-3e00e37b651b";
const INITIAL_MATCHES_SIZE = 15;
const DETAILED_MATCHES_SIZE = 3;
const LOAD_MORE_SIZE = 10;

// Component for displaying match player data
const MatchPlayerRow = ({player, teamStyle, rounds_played}: {
    player: PlayerStats;
    teamStyle: string;
    rounds_played: number;
}) => (
    <div className="flex items-center justify-between p-3 bg-black/20 rounded-lg">
        <div className="flex items-center gap-4">
            <div className={`w-8 h-8 rounded flex items-center justify-center overflow-hidden ${teamStyle}`}>
                {player.agentIcon ? (
                    <img
                        src={player.agentIcon}
                        alt={player.agent}
                        className="w-7 h-7 object-contain"
                    />
                ) : (
                    <span className="text-xs">{player.agent[0]}</span>
                )}
            </div>
            <div>
                <div className="text-white text-sm">{player.name}</div>
                <div className="text-xs text-gray-400">{player.agent}</div>
            </div>
        </div>
        <div className="flex items-center gap-6 text-sm">
            <StatDisplay label="K/D/A" value={`${player.kills}/${player.deaths}/${player.assists}`}/>
            <StatDisplay label="ACS" value={Math.round(player.score / rounds_played).toString()}/>
            <StatDisplay label="ADR" value={Math.round(player.damage_made / rounds_played).toString()}/>
            <StatDisplay label="HS%" value={`${player.headshot}%`}/>
        </div>
    </div>
);

// Component for displaying player stats
const StatDisplay = ({label, value}: { label: string; value: string }) => (
    <div className="text-center">
        <div className="text-gray-400 text-xs">{label}</div>
        <div className="text-white">{value}</div>
    </div>
);

// Component for team display
const TeamDisplay = ({
                         label,
                         players,
                         isVictory,
                         rounds_played
                     }: {
    label: string;
    players: PlayerStats[];
    isVictory: boolean;
    rounds_played: number;
}) => {
    const borderColor = isVictory ? "border-[#4ade80]" : "border-[#f87171]";
    const bgColor = isVictory ? "bg-[#4ade80]/10" : "bg-[#f87171]/10";
    const teamStyle = isVictory
        ? "bg-gradient-to-br from-[#4a7cff] to-[#2d5acc]"
        : "bg-gradient-to-br from-[#f87171] to-[#dc2626]";

    return (
        <div
            className={`p-5 border-l border-r ${!label.includes("Losing") ? "" : "border-b rounded-b-lg"} ${bgColor} ${borderColor}`}>
            <h4 className="text-sm text-gray-400 mb-3">{label}</h4>
            <div className="space-y-2">
                {players.map((player, idx) => (
                    <MatchPlayerRow
                        key={idx}
                        player={player}
                        teamStyle={teamStyle}
                        rounds_played={rounds_played}
                    />
                ))}
            </div>
        </div>
    );
};

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
            const recentMatches = processRecentMatches(recentJson.data || [], mmrMap, mmrJson);
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

    const buildMmrMap = (mmrData: any[]): Map<string, any> => {
        const mmrMap = new Map<string, any>();
        mmrData.forEach((mmr: any) => {
            if (mmr.match_id) {
                mmrMap.set(mmr.match_id, mmr);
            }
        });
        return mmrMap;
    };

    const processRecentMatches = (
        matchesData: any[],
        mmrMap: Map<string, any>,
        mmrJson: any
    ): Match[] => {
        return matchesData.map(match => {
            const meta = match.metadata;
            const mmr = mmrMap.get(meta.matchid) || {};
            const allPlayers = match.players?.all_players || [];

            const startedDate = parseDateSafely(meta.started_at);
            const dateRaw = startedDate.getTime();
            const displayDate = mmr.date || startedDate.toLocaleString();

            const yourPlayer = findUserPlayer(allPlayers, puuid, mmrJson);
            const playerStats = extractPlayerStats(yourPlayer, meta, mmr);
            const teamStats = extractTeamStats(match.teams, yourPlayer?.team?.toLowerCase() || "blue");

            return {
                id: meta.matchid,
                map: meta.map || (mmr.map?.name ?? "Unknown"),
                mapId: mmr.map?.id || "",
                result: (match.teams && match.teams[playerStats.userTeam]?.has_won)
                    ? "Victory" : "Defeat",
                kda: playerStats.kda,
                agent: playerStats.agent,
                agentIcon: playerStats.agentIcon,
                score: teamStats.userScore,
                enemy_score: teamStats.enemyScore,
                acs: playerStats.acs,
                timestamp: displayDate,
                date_raw: dateRaw,
                rank: mmr.currenttierpatched || "",
                ranking_in_tier: mmr.currenttier || 0,
                rrChange: mmr.mmr_change_to_last_game || 0,
                rounds_played: playerStats.roundsPlayed,
                players: allPlayers.map(mapPlayerData),
                teams: match.teams,
                puuid: playerStats.selfPuuid,
                hasDetails: true,
            };
        });
    };

    const processStoredMatches = (
        matchesData: any[],
        mmrMap: Map<string, any>,
        detailedMatchIds: Set<string>
    ): Match[] => {
        return matchesData
            .filter(match => !detailedMatchIds.has(match.meta.id))
            .map(match => {
                const meta = match.meta;
                const stats = match.stats;
                const mmr = mmrMap.get(meta.id) || {};

                const startedDate = parseDateSafely(meta.started_at);
                const dateRaw = startedDate.getTime();
                const displayDate = mmr.date || startedDate.toLocaleString();

                const kda = `${stats.kills}/${stats.deaths}/${stats.assists}`;
                const acs = meta.rounds_played > 0 ? Math.round(stats.score / meta.rounds_played) : 0;

                const userTeam = stats.team.toLowerCase() || "blue";
                const userScore = match.teams[userTeam] || 0;
                const enemyScore = match.teams[userTeam === "blue" ? "red" : "blue"] || 0;

                return {
                    id: meta.id,
                    map: meta.map?.name || "Unknown",
                    mapId: meta.map?.id || "",
                    result: userTeam === "blue"
                        ? (match.teams.blue > match.teams.red ? "Victory" : "Defeat")
                        : (match.teams.red > match.teams.blue ? "Victory" : "Defeat"),
                    kda,
                    agent: stats.character?.name || "",
                    agentIcon: `https://media.valorant-api.com/agents/${stats.character?.id}/displayicon.png`,
                    score: userScore,
                    enemy_score: enemyScore,
                    acs,
                    timestamp: displayDate,
                    date_raw: dateRaw,
                    rank: mmr.currenttierpatched || "",
                    ranking_in_tier: mmr.currenttier || stats.tier || 0,
                    rrChange: mmr.mmr_change_to_last_game || 0,
                    rounds_played: meta.rounds_played || 0,
                    teams: {
                        red: {rounds_won: match.teams.red},
                        blue: {rounds_won: match.teams.blue},
                    },
                    puuid: stats.puuid,
                    hasDetails: false,
                };
            });
    };

    const parseDateSafely = (dateStr: string): Date => {
        let date = new Date();
        try {
            if (dateStr) {
                date = new Date(dateStr);
                if (isNaN(date.getTime())) {
                    console.warn(`Invalid date: ${dateStr}, using current time`);
                    date = new Date();
                }
            }
        } catch (e) {
            console.error("Error parsing date:", e);
        }
        return date;
    };

    const findUserPlayer = (allPlayers: any[], userPuuid?: string, mmrJson?: any): any => {
        if (!allPlayers.length) return null;

        let player = allPlayers[0];

        if (userPuuid) {
            const found = allPlayers.find(p => p.puuid === userPuuid);
            if (found) player = found;
        } else if (mmrJson?.name && mmrJson?.tag) {
            const found = allPlayers.find(
                p => p.name === mmrJson.name && p.tag === mmrJson.tag
            );
            if (found) player = found;
        }

        return player;
    };

    const extractPlayerStats = (yourPlayer: any, meta: any, mmr: any) => {
        if (!yourPlayer) {
            return {
                kda: "",
                agent: "",
                agentIcon: "",
                score: 0,
                selfPuuid: "",
                roundsPlayed: 0,
                acs: 0,
                userTeam: "blue"
            };
        }

        const kda = `${yourPlayer.stats.kills}/${yourPlayer.stats.deaths}/${yourPlayer.stats.assists}`;
        const agent = yourPlayer.character || "";
        const agentIcon = yourPlayer?.assets?.agent?.small || "";
        const score = yourPlayer.stats.score || 0;
        const selfPuuid = yourPlayer.puuid || "";
        const roundsPlayed = meta.rounds_played || mmr.rounds_played || 0;
        const acs = roundsPlayed > 0 ? Math.round(score / roundsPlayed) : 0;
        const userTeam = yourPlayer.team?.toLowerCase() || "blue";

        return {
            kda, agent, agentIcon, score, selfPuuid, roundsPlayed, acs, userTeam
        };
    };

    const extractTeamStats = (teams: any, userTeam: string) => {
        let userScore = 0;
        let enemyScore = 0;

        if (teams) {
            if (userTeam === "red") {
                userScore = teams.red.rounds_won;
                enemyScore = teams.blue.rounds_won;
            } else {
                userScore = teams.blue.rounds_won;
                enemyScore = teams.red.rounds_won;
            }
        }

        return {userScore, enemyScore};
    };

    const mapPlayerData = (p: any): PlayerStats => ({
        puuid: p.puuid,
        name: p.name,
        agent: p.character,
        kills: p.stats.kills,
        deaths: p.stats.deaths,
        assists: p.stats.assists,
        score: p.stats.score,
        damage_made: p.damage_made,
        headshot: 0,
        agentIcon: p.assets?.agent?.small || "",
        team: p.team,
    });

    // Fetch match details for a specific match
    const fetchMatchDetails = async (matchId: string) => {
        try {
            const res = await fetch(`${API_BASE_URL}/match/${matchId}/na/rages/alt`);
            const data = await res.json();

            if (data.status !== 200 || !data.data) {
                throw new Error("Failed to fetch match details");
            }

            return (data.data.players?.all_players || []).map(mapPlayerData);
        } catch (error) {
            console.error("Error fetching match details:", error);
            throw error;
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

    // Fetch more matches
    const fetchMoreMatches = async (nextPage: number): Promise<Match[]> => {
        try {
            const storedRes = await fetch(
                `${API_BASE_URL}/stored-matches/na/rages/alt?size=${LOAD_MORE_SIZE}&page=${nextPage}`
            );
            const storedJson = await storedRes.json();

            return processStoredMatches(
                storedJson.data || [],
                new Map<string, any>(), // No MMR data for older matches
                new Set<string>()
            );
        } catch (error) {
            console.error("Failed to load more matches:", error);
            return [];
        }
    };

    const handleLoadMore = async () => {
        setLoadingMore(true);
        try {
            const nextPage = page + 1;
            const moreMatches = await fetchMoreMatches(nextPage);

            setMatches(prev => {
                // Remove duplicates by id
                const allMatches = [...prev, ...moreMatches];
                const uniqueMatchesMap = new Map<string, Match>();
                allMatches.forEach(m => {
                    uniqueMatchesMap.set(m.id, m);
                });
                const uniqueMatches = Array.from(uniqueMatchesMap.values());
                uniqueMatches.sort((a, b) => b.date_raw - a.date_raw);
                return uniqueMatches;
            });
            setPage(nextPage);
        } catch (error) {
            console.error("Failed to load more matches:", error);
        } finally {
            setLoadingMore(false);
        }
    };

    // Calculate ADR for a player
    const calculateADR = (match: Match): number => {
        if (!match.players || !match.rounds_played) return 0;

        const userPuuidToFind = puuid || DEFAULT_PUUID;
        const yourPlayer = match.players.find(p => p.puuid === userPuuidToFind);

        return yourPlayer
            ? Math.round(yourPlayer.damage_made / match.rounds_played)
            : 0;
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
                />
                <TeamDisplay
                    label={bottomLabel === "Victory" ? "Winning Team (Victory)" : "Losing Team (Defeat)"}
                    players={bottomTeamPlayers}
                    isVictory={bottomLabel === "Victory"}
                    rounds_played={match.rounds_played}
                />
            </>
        );
    };

    // Loading skeleton component
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
                                    {match.rank}
                                  </span>
                                                                </div>

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
                                  ADR: {calculateADR(match)}
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
