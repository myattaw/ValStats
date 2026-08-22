export interface PlayerIdentifier {
  name: string;
  tag: string;
  puuid?: string;
  region?: string;
}

export interface ProfileData extends PlayerIdentifier {
  account_level?: number;
  puuid?: string;
  card?: { small: string };
}

export interface PlayerStats {
  matches_played?: number;
  wins?: number;
  losses?: number;
  draws?: number;
  win_rate?: number;
  kd_ratio: number;
  headshot_percent: number;
  avg_combat_score: number;
  kills_per_round: number;
  adr?: number;
}

export interface SeasonRank {
  error?: boolean | string;
  final_rank: number;
  final_rank_patched?: string;
  number_of_games: number;
  wins: number;
  old?: boolean;
  act_rank_wins?: Array<{ tier: number; patched_tier: string }>;
}

export interface MmrData {
  current_data?: {
    currenttier?: number;
    currenttierpatched?: string;
    ranking_in_tier?: number;
    images?: { small?: string };
  };
  highest_rank?: { tier?: number; patched_tier?: string };
  by_season?: Record<string, SeasonRank>;
}
