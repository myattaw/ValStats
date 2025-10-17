import {Skeleton} from "../ui/skeleton";
import {PlayerStats} from './types/matchTypes';

// Helper to calculate headshot percentage
function getHeadshotPercentage(player: PlayerStats): string {
    const hs = player.headshots ?? 0;
    const bs = player.bodyshots ?? 0;
    const ls = player.legshots ?? 0;
    const total = hs + bs + ls;
    if (total === 0) return "0%";

    return `${Math.round((hs / total) * 100)}%`;
}

// Component for displaying match player data
export const MatchPlayerRow = ({player, teamStyle, rounds_played}: {
    player: PlayerStats;
    teamStyle: string;
    rounds_played: number;
}) => {
    const rp = player.rounds_played ?? rounds_played;
    return (
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
                <StatDisplay label="ACS" value={rp > 0 ? Math.round(player.score / rp).toString() : "0"}/>
                <StatDisplay label="ADR" value={rp > 0 ? Math.round(player.damage_made / rp).toString() : "0"}/>
                <StatDisplay label="HS%" value={getHeadshotPercentage(player)}/>
            </div>
        </div>
    );
};

// Component for displaying player stats
export const StatDisplay = ({label, value}: { label: string; value: string }) => (
    <div className="text-center">
        <div className="text-gray-400 text-xs">{label}</div>
        <div className="text-white">{value}</div>
    </div>
);

// Component for team display
export const TeamDisplay = ({
                                label,
                                players,
                                isVictory,
                                rounds_played,
                                isBottom
                            }: {
    label: string;
    players: PlayerStats[];
    isVictory: boolean;
    rounds_played: number;
    isBottom: boolean;
}) => {
    const borderColor = isVictory ? "border-[#4ade80]" : "border-[#f87171]";
    const bgColor = isVictory ? "bg-[#4ade80]/10" : "bg-[#f87171]/10";
    const teamStyle = isVictory
        ? "bg-gradient-to-br from-[#4a7cff] to-[#2d5acc]"
        : "bg-gradient-to-br from-[#f87171] to-[#dc2626]";

    return (
        <div
            className={`p-5 border-l border-r ${isBottom ? "border-b rounded-b-lg" : ""} ${bgColor} ${borderColor}`}>
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

// Loading skeleton component
export const MatchSkeleton = () => (
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