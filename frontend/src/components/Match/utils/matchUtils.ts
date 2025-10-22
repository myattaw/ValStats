import { Match, PlayerStats } from '../types/matchTypes';

const API_BASE_URL = "http://localhost:51013/api/valorant";
const DEFAULT_PUUID = "37654ff9-b560-5b0f-a2bb-3e00e37b651b";
const INITIAL_MATCHES_SIZE = 15;
const DETAILED_MATCHES_SIZE = 3;
const LOAD_MORE_SIZE = 10;

export { API_BASE_URL, DEFAULT_PUUID, INITIAL_MATCHES_SIZE, DETAILED_MATCHES_SIZE, LOAD_MORE_SIZE };

export const buildMmrMap = (mmrData: any[]): Map<string, any> => {
    const mmrMap = new Map<string, any>();
    mmrData.forEach((mmr: any) => {
        if (mmr.match_id) {
            mmrMap.set(mmr.match_id, mmr);
        }
    });
    return mmrMap;
};

export const processRecentMatches = (
    matchesData: any[],
    mmrMap: Map<string, any>,
    mmrJson: any,
    puuid?: string
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

export const processStoredMatches = (
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

            // Get team names
            const userTeam = stats.team?.toLowerCase() || "blue";
            const enemyTeam = userTeam === "blue" ? "red" : "blue";

            // Get rounds won by each team
            const userScore = match.teams?.[userTeam] ?? match.teams?.[userTeam]?.rounds_won ?? 0;
            const enemyScore = match.teams?.[enemyTeam] ?? match.teams?.[enemyTeam]?.rounds_won ?? 0;

            // Calculate total rounds played
            const rounds_played = (match.teams?.red?.rounds_won ?? match.teams?.red ?? 0)
                + (match.teams?.blue?.rounds_won ?? match.teams?.blue ?? 0);

            // Calculate ACS and ADR
            const acs = rounds_played > 0 ? Math.round(stats.score / rounds_played) : 0;
            const damage_made = stats.damage?.made ?? 0;
            const adr = rounds_played > 0 ? Math.round(damage_made / rounds_played) : 0;

            // Extract shots
            const headshots = stats.shots?.head ?? 0;
            const bodyshots = stats.shots?.body ?? 0;
            const legshots = stats.shots?.leg ?? 0;

            return {
                id: meta.id,
                map: meta.map?.name || "Unknown",
                mapId: meta.map?.id || "",
                result: userScore > enemyScore ? "Victory" : "Defeat",
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
                rounds_played,
                teams: {
                    red: {rounds_won: match.teams?.red?.rounds_won ?? match.teams?.red ?? 0},
                    blue: {rounds_won: match.teams?.blue?.rounds_won ?? match.teams?.blue ?? 0},
                },
                puuid: stats.puuid,
                hasDetails: false,
                players: [
                    {
                        puuid: stats.puuid,
                        name: "", // Not available in stored-matches
                        agent: stats.character?.name || "",
                        kills: stats.kills,
                        deaths: stats.deaths,
                        assists: stats.assists,
                        score: stats.score ?? 0,
                        damage_made,
                        headshots,
                        bodyshots,
                        legshots,
                        agentIcon: `https://media.valorant-api.com/agents/${stats.character?.id}/displayicon.png`,
                        team: stats.team,
                        rounds_played,
                    }
                ],
            };
        });
};

export const parseDateSafely = (dateStr: string): Date => {
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

export const findUserPlayer = (allPlayers: any[], userPuuid?: string, mmrJson?: any): any => {
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

export const extractPlayerStats = (yourPlayer: any, meta: any, mmr: any) => {
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

export const extractTeamStats = (teams: any, userTeam: string) => {
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

export const mapPlayerData = (p: any, rounds_played?: number): PlayerStats => ({
    puuid: p.puuid,
    name: p.name,
    agent: p.character,
    kills: p.stats.kills,
    deaths: p.stats.deaths,
    assists: p.stats.assists,
    score: p.stats.score,
    damage_made: p.damage_made,
    headshots: p.stats.headshots,
    bodyshots: p.stats.bodyshots,
    legshots: p.stats.legshots,
    agentIcon: p.assets?.agent?.small || "",
    team: p.team,
    rounds_played: rounds_played,
});

export const fetchMatchDetails = async (matchId: string): Promise<PlayerStats[]> => {
    try {
        const res = await fetch(`${API_BASE_URL}/match/${matchId}`);
        const data = await res.json();

        if (data.status !== 200 || !data.data) {
            throw new Error("Failed to fetch match details");
        }

        // Support both array and object for data
        const matchData = Array.isArray(data.data) ? data.data[0] : data.data;
        const rounds_played = matchData?.metadata?.rounds_played ?? 0;
        const all_players = matchData?.players?.all_players || [];

        return all_players.map((p: any) => mapPlayerData(p, rounds_played));
    } catch (error) {
        console.error("Error fetching match details:", error);
        throw error;
    }
};

export const fetchMoreMatches = async (nextPage: number): Promise<Match[]> => {
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

export const calculateADR = (match: Match, puuid?: string): number => {
    if (!match.players || !match.rounds_played) return 0;

    const userPuuidToFind = puuid || DEFAULT_PUUID;
    const yourPlayer = match.players.find(p => p.puuid === userPuuidToFind);

    return yourPlayer
        ? Math.round(yourPlayer.damage_made / match.rounds_played)
        : 0;
};
