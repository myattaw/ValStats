import {useState} from "react";
import {Crosshair, Crown, Shield} from "lucide-react";
import {Skeleton} from "../ui/skeleton";
import {MatchDetails, MatchRound, PlayerStats} from './types/matchTypes';

const TIER_SET = "03621f52-342b-cf4e-4f86-9350a49c6d04";

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
export const MatchPlayerRow = ({player, teamStyle, rounds_played, rounds = [], selectedRound, onRoundSelect}: {
    player: PlayerStats;
    teamStyle: string;
    rounds_played: number;
    rounds?: MatchRound[];
    selectedRound?: number;
    onRoundSelect?: (round: number) => void;
}) => {
    const rp = player.rounds_played ?? rounds_played;
    return (
        <div className="scoreboard-player-row">
            <div className="scoreboard-player-identity">
                <div className={`w-7 h-7 rounded flex items-center justify-center overflow-hidden ${teamStyle}`}>
                    {player.agentIcon ? (
                        <img
                            src={player.agentIcon}
                            alt={player.agent}
                            className="w-6 h-6 object-contain"
                        />
                    ) : (
                        <span className="text-xs">{player.agent[0]}</span>
                    )}
                </div>
                <div>
                    <div className="text-white text-sm">{player.name}{player.tag && <span className="text-gray-500">#{player.tag}</span>}</div>
                    <div className="player-rank-line">
                        {player.currentTier ? <img src={`https://media.valorant-api.com/competitivetiers/${TIER_SET}/${player.currentTier}/smallicon.png`} alt=""/> : <Shield/>}
                        <span>{player.currentTierName || "Unranked"}</span>
                        <i>·</i><span>{player.agent}</span>
                    </div>
                </div>
            </div>
            <div className="player-round-track" aria-label={`${player.name} kills by round`}>
                {rounds.map((round) => {
                    const kills = round.kills.filter((kill) => kill.killerPuuid === player.puuid).length;
                    return <button
                        key={round.number}
                        className={`${kills ? "has-kill" : ""} ${selectedRound === round.number ? "selected" : ""}`}
                        onClick={() => onRoundSelect?.(round.number)}
                        title={`Round ${round.number}: ${kills} ${kills === 1 ? "kill" : "kills"}`}
                    >{kills || <span>·</span>}</button>;
                })}
            </div>
            <div className="scoreboard-player-stats">
                <StatDisplay label="K/D/A" value={`${player.kills}/${player.deaths}/${player.assists}`}/>
                <StatDisplay label="ACS" value={rp > 0 ? Math.round(player.score / rp).toString() : "0"}/>
                <StatDisplay label="ADR" value={rp > 0 ? Math.round(player.damage_made / rp).toString() : "0"}/>
                <StatDisplay label="HS%" value={getHeadshotPercentage(player)}/>
            </div>
        </div>
    );
};

const formatRoundTime = (milliseconds: number) => {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
    return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, "0")}`;
};

export const MatchDetailsPanel = ({details, roundsPlayed, viewerPuuid}: { details: MatchDetails; roundsPlayed: number; viewerPuuid?: string | null }) => {
    const [selectedRound, setSelectedRound] = useState(1);
    const viewer = details.players.find((player) => player.puuid === viewerPuuid);
    const ownTeamName = viewer?.team?.toLowerCase() || details.players[0]?.team?.toLowerCase();
    const ownTeam = details.players.filter((player) => player.team?.toLowerCase() === ownTeamName).sort((a, b) => b.score - a.score);
    const enemyTeam = details.players.filter((player) => player.team?.toLowerCase() !== ownTeamName).sort((a, b) => b.score - a.score);
    const round = details.rounds.find((item) => item.number === selectedRound) ?? details.rounds[0];
    const isOwnTeamWin = (winningTeam: string) => winningTeam.toLowerCase() === ownTeamName;

    return (
        <div className="border-x border-b border-white/10 rounded-b-lg bg-[#0b0f15] overflow-hidden">
            <div className="compact-match-details">
                <TeamDisplay label="Your team" players={ownTeam} isVictory={true} rounds_played={roundsPlayed} isBottom={false} rounds={details.rounds} selectedRound={round?.number} onRoundSelect={setSelectedRound}/>
                {round ? <article className="round-card selected-round inline-round-detail">
                        <header>
                            <span className={`round-number ${isOwnTeamWin(round.winningTeam) ? "ally" : "enemy"}`}>{round.number}</span>
                            <div><strong>{isOwnTeamWin(round.winningTeam) ? "Your team" : "Enemy team"} won round {round.number}</strong><small>{round.endType.replaceAll("_", " ")}</small></div>
                            <span className="round-kill-count">{round.kills.length} kills</span>
                        </header>
                        {(round.planter || round.defuser) && (
                            <div className="objective-row"><Crown className="w-3.5 h-3.5"/>{round.planter && `Planted by ${round.planter}`}{round.planter && round.defuser && " · "}{round.defuser && `Defused by ${round.defuser}`}</div>
                        )}
                        <div className="kill-feed">
                            {round.kills.length ? round.kills.map((kill, index) => (
                                <div className="kill-event" key={`${round.number}-${index}`}>
                                    <span>{kill.killerName}</span>
                                    <span className={kill.headshot ? "headshot" : ""}>
                                        {kill.weaponIcon ? <img src={kill.weaponIcon} alt={kill.weaponName}/> : <Crosshair/>}
                                        {kill.headshot && <b>HS</b>}
                                    </span>
                                    <span>{kill.victimName}</span>
                                    <time dateTime={`PT${Math.floor(kill.time / 1000)}S`}>{formatRoundTime(kill.time)}</time>
                                </div>
                            )) : <p className="empty-round">No kill events recorded for this round.</p>}
                        </div>
                    </article> : <div className="empty-details compact-empty">Round data was not supplied by the match provider.</div>}
                <TeamDisplay label="Enemy team" players={enemyTeam} isVictory={false} rounds_played={roundsPlayed} isBottom={true} rounds={details.rounds} selectedRound={round?.number} onRoundSelect={setSelectedRound}/>
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
                                isBottom,
                                rounds = [],
                                selectedRound,
                                onRoundSelect
                            }: {
    label: string;
    players: PlayerStats[];
    isVictory: boolean;
    rounds_played: number;
    isBottom: boolean;
    rounds?: MatchRound[];
    selectedRound?: number;
    onRoundSelect?: (round: number) => void;
}) => {
    const borderColor = isVictory ? "border-[#4ade80]" : "border-[#f87171]";
    const bgColor = isVictory ? "bg-[#4ade80]/10" : "bg-[#f87171]/10";
    const teamStyle = isVictory
        ? "bg-gradient-to-br from-[#4ade80] to-[#15803d]"
        : "bg-gradient-to-br from-[#f87171] to-[#dc2626]";

    return (
        <div
            className={`p-3 border-l border-r ${isBottom ? "border-b rounded-b-lg" : ""} ${bgColor} ${borderColor}`}>
            <h4 className="text-xs text-gray-400 mb-2">{label}</h4>
            <div className="space-y-1">
                {players.map((player, idx) => (
                    <MatchPlayerRow
                        key={idx}
                        player={player}
                        teamStyle={teamStyle}
                        rounds_played={rounds_played}
                        rounds={rounds}
                        selectedRound={selectedRound}
                        onRoundSelect={onRoundSelect}
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
