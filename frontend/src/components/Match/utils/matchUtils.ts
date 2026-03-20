export const API_BASE_URL = "http://localhost:58449/api/valorant";

export const INITIAL_MATCHES_SIZE = 15;
export const LOAD_MORE_SIZE = 10;

// Only keep what you actually need now

export const calculateADR = (match: any, puuid?: string): number => {
    if (!match.players || !match.rounds_played) return 0;

    const player = match.players.find((p: any) => p.puuid === puuid);

    return player
        ? Math.round(player.damage_made / match.rounds_played)
        : 0;
};

export const fetchMatchDetails = async (matchId: string) => {
    const res = await fetch(`${API_BASE_URL}/match/${matchId}`);
    const data = await res.json();

    if (data.status !== 200 || !data.data) {
        throw new Error("Failed to fetch match details");
    }

    const matchData = data.data;
    const players = matchData.players?.all_players || [];

    return players.map((p: any) => ({
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
    }));
};