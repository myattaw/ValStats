export interface PlayerStats {
    puuid: string;
    name: string;
    agent: string;
    kills: number;
    deaths: number;
    assists: number;
    score: number;
    damage_made: number;
    agentIcon?: string;
    team?: string;
    bodyshots?: number;
    headshots?: number;
    legshots?: number;
    rounds_played?: number; // <-- Add this
}

export interface Match {
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

export interface MatchHistoryProps {
    puuid?: string;
    playerName: string;
    playerTag: string;
}