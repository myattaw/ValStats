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
    tag?: string;
    currentTier?: number;
    currentTierName?: string;
    cardIcon?: string;
    level?: number;
    economySpent?: number;
    economyLoadout?: number;
    partyId?: string;
}

export interface RoundKill {
    killerPuuid?: string;
    killerName: string;
    victimPuuid?: string;
    victimName: string;
    weaponName: string;
    weaponIcon?: string;
    headshot: boolean;
    time: number;
    playerLocations: EventPlayerLocation[];
    victimLocation?: EventLocation;
}

export interface EventLocation {
    x: number;
    y: number;
}

export interface EventPlayerLocation {
    puuid: string;
    team?: string;
    location: EventLocation;
    viewRadians?: number;
}

export interface MatchRound {
    number: number;
    winningTeam: string;
    endType: string;
    planter?: string;
    defuser?: string;
    kills: RoundKill[];
}

export interface MatchDetails {
    players: PlayerStats[];
    rounds: MatchRound[];
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
    adr?: number;
    timestamp: string;
    date_raw: number;
    server?: string;
    rank: string;
    ranking_in_tier: number;
    rank_tier: number;
    rrChange: number;
    rounds_played: number;
    players?: PlayerStats[];
    details?: MatchDetails;
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
