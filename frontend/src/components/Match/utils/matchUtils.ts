export const API_BASE_URL = "http://localhost:50163/api/valorant";

export const INITIAL_MATCHES_SIZE = 15;
export const LOAD_MORE_SIZE = 10;

/* ===============================
   ACT MAPPING (CRITICAL FIX)
================================ */

export const ACT_MAP: Record<string, string> = {
    "9d85c932-4820-c060-09c3-668636d4df1b": "e11a2",
    "3ea2b318-423b-cf86-25da-7cbb0eefbe2d": "e11a1",

    "4c4b8cff-43eb-13d3-8f14-96b783c90cd2": "e10a6",
    "5adc33fa-4f30-2899-f131-6fba64c5dd3a": "e10a5",
    "ac12e9b3-47e6-9599-8fa1-0bb473e5efc7": "e10a4",
    "aef237a0-494d-3a14-a1c8-ec8de84e309c": "e10a3",
    "16118998-4705-5813-86dd-0292a2439d90": "e10a2",
    "476b0893-4c2e-abd6-c5fe-708facff0772": "e10a1",

    "dcde7346-4085-de4f-c463-2489ed47983b": "e9a3",
    "292f58db-4c17-89a7-b1c0-ba988f0e9d98": "e9a2",
    "52ca6698-41c1-e7de-4008-8994d2221209": "e9a1",

    "4539cac3-47ae-90e5-3d01-b3812ca3274e": "e8a3",
    "22d10d66-4d2a-a340-6c54-408c7bd53807": "e8a2",
    "ec876e6c-43e8-fa63-ffc1-2e8d4db25525": "e8a1",

    "4401f9fd-4170-2e4c-4bc3-f3b4d7d150d1": "e7a3",
    "03dfd004-45d4-ebfd-ab0a-948ce780dac4": "e7a2",
    "0981a882-4e7d-371a-70c4-c3b4f46c504a": "e7a1",

    "2de5423b-4aad-02ad-8d9b-c0a931958861": "e6a3",
    "34093c29-4306-43de-452f-3f944bde22be": "e6a2",
    "9c91a445-4f78-1baa-a3ea-8f8aadf4914d": "e6a1",

    "aca29595-40e4-01f5-3f35-b1b3d304c96e": "e5a3",
    "7a85de9a-4032-61a9-61d8-f4aa2b4a84b6": "e5a2",
    "67e373c7-48f7-b422-641b-079ace30b427": "e5a1",

    "3e47230a-463c-a301-eb7d-67bb60357d4f": "e4a3",
    "d929bc38-4ab6-7da4-94f0-ee84f8ac141e": "e4a2"
};

/* ===============================
   MMR FETCH (NEW)
================================ */

export const fetchMMR = async (
    region: string,
    name: string,
    tag: string
) => {
    const res = await fetch(
        `${API_BASE_URL}/mmr/${region}/${encodeURIComponent(name)}/${encodeURIComponent(tag)}`
    );

    const json = await res.json();

    if (json.status !== 200 || !json.data) {
        throw new Error("Failed to fetch MMR");
    }

    return json.data;
};

/* ===============================
   MATCH HELPERS
================================ */

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