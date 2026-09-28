import type { MatchDetails } from "../types/matchTypes";

/**
 * In development Vite proxies this path to Micronaut. In production, either
 * serve the frontend and API from the same origin or set VITE_API_BASE_URL to
 * the API Gateway URL (including /api/valorant).
 */
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "/api/valorant").replace(/\/$/, "");

export const INITIAL_MATCHES_SIZE = 15;
export const LOAD_MORE_SIZE = 10;

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

// Keep a bounded browser cache and share in-flight preloads with dropdown clicks.
const detailCache = new Map<string, MatchDetails>();
const detailRequests = new Map<string, Promise<MatchDetails | undefined>>();

function rememberDetails(id: string, details: MatchDetails) {
    detailCache.delete(id);
    detailCache.set(id, details);
    if (detailCache.size > 100) detailCache.delete(detailCache.keys().next().value!);
    return details;
}

export const preloadMatchDetails = async (matchIds: string[]): Promise<Map<string, MatchDetails>> => {
    const ids = [...new Set(matchIds)].slice(0, 20);
    const missing = ids.filter((id) => !detailCache.has(id) && !detailRequests.has(id));
    if (missing.length) {
        const batch = (async () => {
            const data: Record<string, any> = {};
            let remaining = missing;
            while (remaining.length) {
                try {
                    const response = await fetch(API_BASE_URL + "/matches/cached-details?" + new URLSearchParams({ids: remaining.join(",")}));
                    if (!response.ok) break;
                    const payload = await response.json();
                    if (payload.status !== 200) break;
                    Object.assign(data, payload.data ?? {});
                    const deferred: string[] = Array.isArray(payload.deferred) ? payload.deferred : [];
                    const next = remaining.filter((id) => deferred.includes(id) && !data[id]);
                    if (next.length >= remaining.length) break;
                    remaining = next;
                } catch { break; }
            }
            return data;
        })();
        for (const id of missing) {
            const request = batch.then((data) => {
                if (!data[id]?.players?.all_players?.length) return undefined;
                return rememberDetails(id, normalizeMatchDetails(data[id]));
            }).finally(() => detailRequests.delete(id));
            detailRequests.set(id, request);
        }
    }
    const result = new Map<string, MatchDetails>();
    await Promise.all(ids.map(async (id) => {
        const details = detailCache.get(id) ?? await detailRequests.get(id)?.catch(() => undefined);
        if (details) result.set(id, details);
    }));
    return result;
};

export const fetchMatchDetails = async (matchId: string): Promise<MatchDetails> => {
    const cached = detailCache.get(matchId) ?? await detailRequests.get(matchId);
    if (cached) return cached;
    // Recheck after waiting for a cache-only preload: another click may have started the fallback.
    const pending = detailRequests.get(matchId);
    if (pending) {
        const details = await pending;
        if (details) return details;
    }
    const request = (async () => {
        const res = await fetch(API_BASE_URL + "/match/" + encodeURIComponent(matchId));
        if (!res.ok) throw new Error("Failed to fetch match details");
        const data = await res.json();
        if (data.status !== 200 || !data.data?.players?.all_players?.length) {
            throw new Error("Match details are unavailable");
        }
        return rememberDetails(matchId, normalizeMatchDetails(data.data));
    })();
    detailRequests.set(matchId, request);
    try { return await request; }
    finally { detailRequests.delete(matchId); }
};

export const normalizeMatchDetails = (matchData: any): MatchDetails => {
    const players = matchData.players?.all_players || [];

    const normalizedPlayers = players.map((p: any) => ({
        puuid: p.puuid,
        name: p.name,
        tag: p.tag,
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
        cardIcon: p.assets?.card?.small || "",
        team: p.team,
        currentTier: p.currenttier ?? p.competitive_tier ?? p.tier ?? 0,
        currentTierName: p.currenttier_patched || p.competitive_tier_name || p.rank || "Unranked",
        level: Number.isFinite(Number(p.account_level ?? p.accountLevel ?? p.level))
            ? Number(p.account_level ?? p.accountLevel ?? p.level)
            : undefined,
        economySpent: p.economy?.spent?.overall ?? 0,
        economyLoadout: p.economy?.loadout_value?.average ?? 0,
        partyId: p.party_id || p.partyId || p.party?.id,
    }));

    const playerNames = new Map(players.map((p: any) => [p.puuid, `${p.name}${p.tag ? `#${p.tag}` : ""}`]));
    const normalizeKill = (kill: any, fallbackKillerPuuid?: string) => {
        const killerPuuid = kill.killer_puuid || fallbackKillerPuuid;
        return {
            killerPuuid,
            killerName: kill.killer_display_name || playerNames.get(killerPuuid) || "Unknown",
            victimPuuid: kill.victim_puuid,
            victimName: kill.victim_display_name || playerNames.get(kill.victim_puuid) || "Unknown",
            weaponName: kill.damage_weapon_name || kill.damage_weapon_assets?.display_name || "Weapon",
            weaponIcon: kill.damage_weapon_assets?.killfeed_icon,
            headshot: kill.finishing_damage?.damage_type === "Headshot" || kill.is_headshot === true,
            time: Number(kill.kill_time_in_round) || 0,
            playerLocations: (kill.player_locations_on_kill || kill.player_locations || []).map((entry: any) => ({
                puuid: entry.player_puuid,
                team: entry.player_team,
                location: {
                    x: Number(entry.location?.x) || 0,
                    y: Number(entry.location?.y) || 0,
                },
                viewRadians: entry.view_radians,
            })),
            victimLocation: kill.victim_death_location ? {
                x: Number(kill.victim_death_location.x) || 0,
                y: Number(kill.victim_death_location.y) || 0,
            } : undefined,
        };
    };

    const globalKills = Array.isArray(matchData.kills) ? matchData.kills : [];
    const roundsAreZeroBased = globalKills.some((kill: any) => Number(kill.round) === 0);
    const rounds = (matchData.rounds || []).map((round: any, index: number) => ({
        number: index + 1,
        winningTeam: round.winning_team || "Unknown",
        endType: round.end_type || "Round complete",
        planter: round.plant_events?.planted_by?.display_name,
        defuser: round.defuse_events?.defused_by?.display_name,
        kills: (() => {
            const roundNumber = roundsAreZeroBased ? index : index + 1;
            const topLevelKills = globalKills
                .filter((kill: any) => Number(kill.round) === roundNumber)
                .map((kill: any) => normalizeKill(kill));
            const nestedKills = (round.player_stats || []).flatMap((stat: any) =>
                (stat.kill_events || stat.kills || []).map((kill: any) => normalizeKill(kill, stat.player_puuid))
            );
            return (topLevelKills.length ? topLevelKills : nestedKills)
                .sort((a: { time: number }, b: { time: number }) => a.time - b.time);
        })(),
    }));

    return { players: normalizedPlayers, rounds };
};
