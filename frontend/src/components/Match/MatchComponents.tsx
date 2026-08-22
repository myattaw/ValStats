import {useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type UIEvent as ReactUIEvent} from "react";
import {ChevronDown, Crosshair, Crown, Map as MapIcon, RotateCcw, Shield, ZoomIn, ZoomOut} from "lucide-react";
import {Skeleton} from "../ui/skeleton";
import {EventLocation, MatchDetails, MatchRound, PlayerStats, RoundKill} from './types/matchTypes';
import {playerUuidPath} from '../../lib/player';

const TIER_SET = "03621f52-342b-cf4e-4f86-9350a49c6d04";

interface MapData {
    displayIcon: string;
    xMultiplier: number;
    yMultiplier: number;
    xScalar: number;
    yScalar: number;
}

const TacticalMap = ({mapId, event, players, ownTeam}: { mapId?: string; event?: RoundKill; players: PlayerStats[]; ownTeam?: string }) => {
    const [map, setMap] = useState<MapData | null>(null);
    const [zoom, setZoom] = useState(1);
    const [pan, setPan] = useState({x: 0, y: 0});
    const drag = useRef<{pointerId: number; x: number; y: number; originX: number; originY: number} | null>(null);
    const pointers = useRef(new Map<number, {x: number; y: number}>());
    const pinch = useRef<{distance: number; zoom: number} | null>(null);
    const mapElement = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        if (!mapId) return;
        const controller = new AbortController();
        fetch(`https://valorant-api.com/v1/maps/${encodeURIComponent(mapId)}`, {signal: controller.signal})
            .then((response) => response.ok ? response.json() : Promise.reject(new Error("Map unavailable")))
            .then((payload) => setMap({
                displayIcon: payload.data.displayIcon,
                xMultiplier: Number(payload.data.xMultiplier),
                yMultiplier: Number(payload.data.yMultiplier),
                xScalar: Number(payload.data.xScalarToAdd ?? payload.data.xScalar),
                yScalar: Number(payload.data.yScalarToAdd ?? payload.data.yScalar),
            }))
            .catch((reason) => {
                if (reason?.name !== "AbortError") console.error("Failed to load tactical map", reason);
            });
        return () => controller.abort();
    }, [mapId]);

    useEffect(() => {
        const element = mapElement.current;
        if (!element) return;
        const handleNativeWheel = (wheelEvent: globalThis.WheelEvent) => {
            wheelEvent.preventDefault();
            wheelEvent.stopPropagation();
            setZoom((value) => Math.max(.75, Math.min(2.5, value + (wheelEvent.deltaY < 0 ? .15 : -.15))));
        };
        element.addEventListener("wheel", handleNativeWheel, {passive: false});
        return () => element.removeEventListener("wheel", handleNativeWheel);
    }, [map?.displayIcon]);

    const position = (location: EventLocation) => {
        const left = (location.y * (map?.xMultiplier ?? 0) + (map?.xScalar ?? 0)) * 100;
        const top = (location.x * (map?.yMultiplier ?? 0) + (map?.yScalar ?? 0)) * 100;
        if (!Number.isFinite(left) || !Number.isFinite(top)) return undefined;
        return {
            left: `${Math.max(1, Math.min(99, left))}%`,
            top: `${Math.max(1, Math.min(99, top))}%`,
        };
    };

    if (!map?.displayIcon) return <div className="map-placeholder">Map positioning unavailable</div>;

    const victim = players.find((player) => player.puuid === event?.victimPuuid);
    const victimRelation = victim?.team?.toLowerCase() === ownTeam ? "teammate" : "enemy";
    const resetView = () => {
        setZoom(1);
        setPan({x: 0, y: 0});
    };
    const handlePointerDown = (pointerEvent: ReactPointerEvent<HTMLDivElement>) => {
        if (pointerEvent.button !== 0) return;
        pointerEvent.currentTarget.setPointerCapture(pointerEvent.pointerId);
        pointers.current.set(pointerEvent.pointerId, {x: pointerEvent.clientX, y: pointerEvent.clientY});
        if (pointers.current.size === 1) {
            drag.current = {pointerId: pointerEvent.pointerId, x: pointerEvent.clientX, y: pointerEvent.clientY, originX: pan.x, originY: pan.y};
        } else if (pointers.current.size === 2) {
            const [first, second] = [...pointers.current.values()];
            pinch.current = {distance: Math.hypot(second.x - first.x, second.y - first.y), zoom};
            drag.current = null;
        }
    };
    const handlePointerMove = (pointerEvent: ReactPointerEvent<HTMLDivElement>) => {
        if (!pointers.current.has(pointerEvent.pointerId)) return;
        pointers.current.set(pointerEvent.pointerId, {x: pointerEvent.clientX, y: pointerEvent.clientY});
        if (pointers.current.size >= 2 && pinch.current) {
            const [first, second] = [...pointers.current.values()];
            const distance = Math.hypot(second.x - first.x, second.y - first.y);
            if (pinch.current.distance > 0) {
                setZoom(Math.max(.75, Math.min(2.5, pinch.current.zoom * distance / pinch.current.distance)));
            }
            return;
        }
        if (!drag.current || drag.current.pointerId !== pointerEvent.pointerId) return;
        setPan({x: drag.current.originX + pointerEvent.clientX - drag.current.x, y: drag.current.originY + pointerEvent.clientY - drag.current.y});
    };
    const handlePointerUp = (pointerEvent: ReactPointerEvent<HTMLDivElement>) => {
        pointers.current.delete(pointerEvent.pointerId);
        pinch.current = null;
        if (drag.current?.pointerId === pointerEvent.pointerId) drag.current = null;
        if (pointers.current.size === 1) {
            const [pointerId, remaining] = [...pointers.current.entries()][0];
            drag.current = {pointerId, x: remaining.x, y: remaining.y, originX: pan.x, originY: pan.y};
        }
        if (pointerEvent.currentTarget.hasPointerCapture(pointerEvent.pointerId)) pointerEvent.currentTarget.releasePointerCapture(pointerEvent.pointerId);
    };

    return (
        <div className="map-stage">
        <div ref={mapElement} className="tactical-map" onPointerDown={handlePointerDown} onPointerMove={handlePointerMove} onPointerUp={handlePointerUp} onPointerCancel={handlePointerUp} onDoubleClick={resetView} title="Pinch or scroll to zoom · Drag to pan · Double-click to reset">
            <div className="map-canvas" style={{transform: `translate(calc(-50% + ${pan.x}px), calc(-50% + ${pan.y}px)) scale(${zoom})`}}>
            <img src={map.displayIcon} alt="Round tactical map"/>
            {event?.playerLocations.map((entry) => {
                const player = players.find((item) => item.puuid === entry.puuid);
                const relation = (player?.team || entry.team)?.toLowerCase() === ownTeam ? "teammate" : "enemy";
                const role = entry.puuid === event.killerPuuid ? " killer" : entry.puuid === event.victimPuuid ? " victim dead" : "";
                const markerPosition = position(entry.location);
                if (!markerPosition) return null;
                return <span
                    key={entry.puuid}
                    className={`map-player ${relation}${role}`}
                    style={markerPosition}
                    title={`${player?.name || "Player"} · ${player?.agent || "Unknown agent"}`}
                >{player?.agentIcon ? <img src={player.agentIcon} alt={player.agent}/> : player?.agent?.slice(0, 2).toUpperCase() || "?"}</span>;
            })}
            {event?.victimLocation && !event.playerLocations.some((entry) => entry.puuid === event.victimPuuid) && (() => {
                const markerPosition = position(event.victimLocation!);
                return markerPosition ? <span className={`map-player ${victimRelation} victim dead`} style={markerPosition} title={event.victimName}>
                    {victim?.agentIcon ? <img src={victim.agentIcon} alt={victim.agent}/> : victim?.agent?.slice(0, 2).toUpperCase() || "×"}
                </span> : null;
            })()}
            {!event && <div className="map-overlay-message">Select an event</div>}
            </div>
        </div>
        <div className="map-controls" aria-label="Map zoom controls">
            <button onClick={() => setZoom((value) => Math.max(.75, value - .25))} disabled={zoom <= .75} title="Zoom out"><ZoomOut/></button>
            <span>{Math.round(zoom * 100)}%</span>
            <button onClick={() => setZoom((value) => Math.min(2.5, value + .25))} disabled={zoom >= 2.5} title="Zoom in"><ZoomIn/></button>
            <button onClick={resetView} disabled={zoom === 1 && pan.x === 0 && pan.y === 0} title="Reset map view"><RotateCcw/></button>
        </div>
        </div>
    );
};

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
export const MatchPlayerRow = ({player, teamStyle, partyColor, rounds_played, rounds = [], selectedRound, onRoundSelect}: {
    player: PlayerStats;
    teamStyle: string;
    partyColor?: string;
    rounds_played: number;
    rounds?: MatchRound[];
    selectedRound?: number;
    onRoundSelect?: (round: number) => void;
}) => {
    const rp = player.rounds_played ?? rounds_played;
    return (
        <div
            className={`scoreboard-player-row ${partyColor ? "has-party" : ""}`}
            style={partyColor ? {"--party-color": partyColor} as CSSProperties : undefined}
        >
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
                    {player.puuid ? (
                        <a
                            className="scoreboard-player-link text-sm"
                            href={playerUuidPath(player.puuid)}
                            title={`View ${player.name}#${player.tag}'s profile`}
                        >
                            <span>{player.name}</span>{player.tag && <span>#{player.tag}</span>}
                        </a>
                    ) : <div className="text-white text-sm">{player.name}</div>}
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
                    const team = player.team?.trim().toLowerCase();
                    const winningTeam = round.winningTeam?.trim().toLowerCase();
                    const resultClass = team && winningTeam && winningTeam !== "unknown"
                        ? winningTeam === team ? "round-win" : "round-loss"
                        : "round-unknown";
                    const resultLabel = resultClass === "round-win" ? "win" : resultClass === "round-loss" ? "loss" : "result unavailable";
                    return <button
                        key={round.number}
                        className={`${resultClass} ${kills ? "has-kill" : ""} ${selectedRound === round.number ? "selected" : ""}`}
                        onClick={() => onRoundSelect?.(round.number)}
                        aria-label={`Round ${round.number}: ${resultLabel}, ${kills} ${kills === 1 ? "kill" : "kills"}`}
                        title={`Round ${round.number}: ${resultLabel} · ${kills} ${kills === 1 ? "kill" : "kills"}`}
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

export const MatchDetailsPanel = ({details, roundsPlayed, viewerPuuid, mapId}: { details: MatchDetails; roundsPlayed: number; viewerPuuid?: string | null; mapId?: string }) => {
    const [selectedRound, setSelectedRound] = useState(1);
    const [selectedEvent, setSelectedEvent] = useState(0);
    const [mobileMapOpen, setMobileMapOpen] = useState(false);
    const detailsElement = useRef<HTMLDivElement | null>(null);
    const synchronizingTimelines = useRef(false);
    const viewer = details.players.find((player) => player.puuid === viewerPuuid);
    const ownTeamName = viewer?.team?.toLowerCase() || details.players[0]?.team?.toLowerCase();
    const ownTeam = details.players.filter((player) => player.team?.toLowerCase() === ownTeamName).sort((a, b) => b.score - a.score);
    const enemyTeam = details.players.filter((player) => player.team?.toLowerCase() !== ownTeamName).sort((a, b) => b.score - a.score);
    const round = details.rounds.find((item) => item.number === selectedRound) ?? details.rounds[0];
    const isOwnTeamWin = (winningTeam: string) => winningTeam.toLowerCase() === ownTeamName;
    const playerTeam = (puuid?: string) => details.players.find((player) => player.puuid === puuid)?.team?.toLowerCase();
    const relationshipClass = (puuid?: string) => playerTeam(puuid) === ownTeamName ? "teammate" : "enemy";

    useEffect(() => setSelectedEvent(0), [selectedRound]);

    const synchronizeTimelineScroll = (event: ReactUIEvent<HTMLDivElement>) => {
        const source = event.target;
        if (!(source instanceof HTMLElement)
            || (!source.classList.contains("player-round-track")
                && !source.classList.contains("scoreboard-round-numbers"))
            || synchronizingTimelines.current) return;

        synchronizingTimelines.current = true;
        const scrollLeft = source.scrollLeft;
        detailsElement.current
            ?.querySelectorAll<HTMLElement>(".player-round-track, .scoreboard-round-numbers")
            .forEach((timeline) => {
                if (timeline !== source) timeline.scrollLeft = scrollLeft;
            });
        window.requestAnimationFrame(() => {
            synchronizingTimelines.current = false;
        });
    };

    return (
        <div ref={detailsElement} onScrollCapture={synchronizeTimelineScroll} className="rounded-b-lg bg-[#0b0f15] overflow-hidden">
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
                        <div className="round-analysis">
                        <div className="kill-feed">
                            {round.kills.length ? round.kills.map((kill, index) => (
                                <button className={`kill-event ${selectedEvent === index ? "selected" : ""}`} key={`${round.number}-${index}`} onClick={() => setSelectedEvent(index)}>
                                    <time dateTime={`PT${Math.floor(kill.time / 1000)}S`}>{formatRoundTime(kill.time)}</time>
                                    <span className={relationshipClass(kill.killerPuuid)}>{kill.killerName}</span>
                                    <span className={kill.headshot ? "headshot" : ""}>
                                        {kill.weaponIcon ? <img src={kill.weaponIcon} alt={kill.weaponName}/> : <Crosshair/>}
                                        {kill.headshot && <b>HS</b>}
                                    </span>
                                    <span className={relationshipClass(kill.victimPuuid)}>{kill.victimName}</span>
                                </button>
                            )) : <p className="empty-round">No kill events recorded for this round.</p>}
                        </div>
                        <button
                            type="button"
                            className="mobile-map-toggle"
                            aria-expanded={mobileMapOpen}
                            onClick={() => setMobileMapOpen((open) => !open)}
                        >
                            <MapIcon/>
                            <span>{mobileMapOpen ? "Hide minimap" : "View minimap"}</span>
                            <ChevronDown className={mobileMapOpen ? "open" : ""}/>
                        </button>
                        <div className={`mobile-map-shell ${mobileMapOpen ? "open" : ""}`}>
                            <TacticalMap mapId={mapId} event={round.kills[selectedEvent]} players={details.players} ownTeam={ownTeamName}/>
                        </div>
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
    const groupedParties = players.reduce((counts, player) => {
        if (player.partyId) counts.set(player.partyId, (counts.get(player.partyId) ?? 0) + 1);
        return counts;
    }, new Map<string, number>());
    const partyIds = [...groupedParties.entries()].filter(([, count]) => count > 1).map(([id]) => id);
    const partyColors = ['#a78bfa', '#38bdf8', '#fbbf24', '#f472b6', '#2dd4bf'];

    return (
        <div
            className={`p-3 border-l border-r ${isBottom ? "border-b rounded-b-lg" : ""} ${bgColor} ${borderColor}`}>
            <div className="scoreboard-team-header">
                <h4>{label}<small className="timeline-scroll-hint" aria-hidden="true">Swipe rounds <span>↔</span></small></h4>
                <div className="scoreboard-round-numbers" aria-label="Round numbers">
                    {rounds.map((round) => <span key={round.number}>{round.number}</span>)}
                </div>
                <span aria-hidden="true" />
            </div>
            <div className="scoreboard-player-list">
                {players.map((player, idx) => (
                    <MatchPlayerRow
                        key={idx}
                        player={player}
                        teamStyle={teamStyle}
                        partyColor={player.partyId && partyIds.includes(player.partyId) ? partyColors[partyIds.indexOf(player.partyId) % partyColors.length] : undefined}
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
