import {useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type UIEvent as ReactUIEvent} from "react";
import {ChevronDown, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Crosshair, Crown, Map as MapIcon, RotateCcw, ZoomIn, ZoomOut} from "lucide-react";
import {Skeleton} from "../ui/skeleton";
import {EventLocation, MatchDetails, MatchRound, PlayerStats, RoundKill} from './types/matchTypes';
import {playerUuidPath} from '../../lib/player';

const TIER_SET = "03621f52-342b-cf4e-4f86-9350a49c6d04";
const PARTY_COLORS = ['#d3a35f', '#70a0bd', '#aa7f9e', '#72a087', '#bf7770'];

const getPremadeColors = (players: PlayerStats[]) => {
    const counts = players.reduce((partyCounts, player) => {
        if (player.partyId) partyCounts.set(player.partyId, (partyCounts.get(player.partyId) ?? 0) + 1);
        return partyCounts;
    }, new Map<string, number>());
    const partyIds = [...counts.entries()].filter(([, count]) => count > 1).map(([id]) => id);
    return new Map(partyIds.map((id, index) => [id, PARTY_COLORS[index % PARTY_COLORS.length]]));
};
const LEVEL_BORDER_ASSETS = import.meta.glob('../../assets/level-borders/*.png', {
    eager: true,
    query: '?url',
    import: 'default',
}) as Record<string, string>;

const AccountLevelBadge = ({level}: {level: number}) => {
    const borderLevel = level < 20 ? 1 : Math.min(480, Math.floor(level / 20) * 20);
    const borderSrc = LEVEL_BORDER_ASSETS[`../../assets/level-borders/level-${borderLevel}.png`];

    return <span className="scoreboard-account-level" title={`Account level ${level}`} aria-label={`Account level ${level}`}>
        <img src={borderSrc} alt="" aria-hidden="true"/>
        <span>{level}</span>
    </span>;
};

interface MapData {
    displayIcon: string;
    xMultiplier: number;
    yMultiplier: number;
    xScalar: number;
    yScalar: number;
}

const TacticalMap = ({mapId, event, players, ownTeam, emphasizeEvent = false}: { mapId?: string; event?: RoundKill; players: PlayerStats[]; ownTeam?: string; emphasizeEvent?: boolean }) => {
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
                const role = emphasizeEvent ? entry.puuid === event.killerPuuid ? " killer" : entry.puuid === event.victimPuuid ? " victim dead" : " unrelated" : "";
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
                return markerPosition ? <span className={`map-player ${victimRelation} victim dead${emphasizeEvent ? '' : ' normal'}`} style={markerPosition} title={event.victimName}>
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
export const MatchPlayerRow = ({player, teamStyle, partyColor, rounds_played, rounds = [], selectedRound, onRoundSelect, isViewer = false}: {
    player: PlayerStats;
    teamStyle: string;
    partyColor?: string;
    rounds_played: number;
    rounds?: MatchRound[];
    selectedRound?: number;
    onRoundSelect?: (round: number) => void;
    isViewer?: boolean;
}) => {
    const rp = player.rounds_played ?? rounds_played;
    return (
        <div
            className={`scoreboard-player-row ${isViewer ? "is-viewer" : ""}`}
            style={partyColor ? {"--party-color": partyColor} as CSSProperties : undefined}
        >
            <div className="scoreboard-player-identity">
                <div className={`scoreboard-agent-avatar ${partyColor ? "has-party" : ""}`}>
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
                    {typeof player.level === "number" && <AccountLevelBadge level={player.level}/>}
                </div>
                <div>
                    <div className="scoreboard-player-name-line">
                        {player.puuid ? (
                            <a
                                className="scoreboard-player-link text-sm"
                                href={playerUuidPath(player.puuid)}
                                title={`View ${player.name}#${player.tag}'s profile`}
                            >
                                <span>{player.name}</span>{player.tag && <span>#{player.tag}</span>}
                            </a>
                        ) : <div className="text-white text-sm">{player.name}</div>}
                        {isViewer && <span className="you-pill">You</span>}
                    </div>
                    <div className="player-rank-line">
                        <img src={`https://media.valorant-api.com/competitivetiers/${TIER_SET}/${player.currentTier || 0}/smallicon.png`} alt=""/>
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

const LegacyMatchDetailsPanel = ({details, roundsPlayed, viewerPuuid, mapId}: { details: MatchDetails; roundsPlayed: number; viewerPuuid?: string | null; mapId?: string }) => {
    const [activeTab] = useState<'scoreboard' | 'timeline'>('scoreboard');
    const [selectedRound, setSelectedRound] = useState(1);
    const [selectedEvent, setSelectedEvent] = useState(0);
    const [mobileMapOpen, setMobileMapOpen] = useState(false);
    const [timelineOverflow, setTimelineOverflow] = useState(false);
    const detailsElement = useRef<HTMLDivElement | null>(null);
    const synchronizingTimelines = useRef(false);
    const viewer = details.players.find((player) => player.puuid === viewerPuuid);
    const ownTeamName = viewer?.team?.toLowerCase() || details.players[0]?.team?.toLowerCase();
    const ownTeam = details.players.filter((player) => player.team?.toLowerCase() === ownTeamName).sort((a, b) => b.score - a.score);
    const enemyTeam = details.players.filter((player) => player.team?.toLowerCase() !== ownTeamName).sort((a, b) => b.score - a.score);
    const premadeColors = getPremadeColors(details.players);
    const round = details.rounds.find((item) => item.number === selectedRound) ?? details.rounds[0];
    const isOwnTeamWin = (winningTeam: string) => winningTeam.toLowerCase() === ownTeamName;
    const playerTeam = (puuid?: string) => details.players.find((player) => player.puuid === puuid)?.team?.toLowerCase();
    const relationshipClass = (puuid?: string) => playerTeam(puuid) === ownTeamName ? "teammate" : "enemy";

    useEffect(() => setSelectedEvent(0), [selectedRound]);

    useEffect(() => {
        const root = detailsElement.current;
        if (!root) return;
        const updateOverflow = () => {
            const timeline = root.querySelector<HTMLElement>(".player-round-track");
            setTimelineOverflow(Boolean(timeline && timeline.scrollWidth > timeline.clientWidth + 1));
        };
        updateOverflow();
        const observer = new ResizeObserver(updateOverflow);
        observer.observe(root);
        return () => observer.disconnect();
    }, [details.rounds.length]);

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

    const moveTimelines = (action: 'start' | 'previous' | 'next' | 'end') => {
        const timelines = detailsElement.current?.querySelectorAll<HTMLElement>(".player-round-track, .scoreboard-round-numbers");
        if (!timelines?.length) return;
        const source = timelines[0];
        const step = 22;
        const target = action === 'start' ? 0 : action === 'end' ? source.scrollWidth : source.scrollLeft + (action === 'previous' ? -step : step);
        timelines.forEach((timeline) => timeline.scrollTo({left: target, behavior: 'smooth'}));
    };

    return (
        <div ref={detailsElement} onScrollCapture={synchronizeTimelineScroll} className="match-details-panel">
            <div className="scoreboard-layout combined-match-view">
                <div className={`compact-match-details scoreboard-only ${timelineOverflow ? "timeline-overflowing" : ""}`}>
                    <TeamDisplay label="Your team" players={ownTeam} premadeColors={premadeColors} isVictory={true} rounds_played={roundsPlayed} isBottom={false} viewerPuuid={viewerPuuid} rounds={details.rounds} selectedRound={round?.number} onRoundSelect={setSelectedRound} showTimelineControls={timelineOverflow} onTimelineMove={moveTimelines}/>
                    <section className={`combined-round-events ${mobileMapOpen ? "map-open" : ""}`} aria-label={`Round ${round?.number ?? 1} events`}>
                        <div className="mobile-round-heading"><span>Round timeline</span><small>Swipe to explore rounds →</small></div>
                        <div className="mobile-shared-rounds" aria-label="Select round">
                            {details.rounds.map((item) => <button type="button" key={item.number} className={`${isOwnTeamWin(item.winningTeam) ? 'ally' : 'enemy'} ${item.number === round?.number ? 'selected' : ''}`} onClick={() => setSelectedRound(item.number)}>{item.number}</button>)}
                        </div>
                        {round && <>
                            <button type="button" className="match-map-toggle" aria-expanded={mobileMapOpen} onClick={() => setMobileMapOpen((open) => !open)}><MapIcon/><span>{mobileMapOpen ? "Hide minimap" : "Show minimap"}</span><ChevronDown className={mobileMapOpen ? "open" : ""}/></button>
                            <div className="round-map-layout"><div className="round-events-list"><header><span className={`round-number ${isOwnTeamWin(round.winningTeam) ? "ally" : "enemy"}`}>{round.number}</span><div><strong>{isOwnTeamWin(round.winningTeam) ? "Your team" : "Enemy team"} won round {round.number}</strong><small>{round.endType.replaceAll("_", " ")}</small></div><span>{round.kills.length} kills</span></header><div className="kill-feed">{round.kills.length ? round.kills.map((kill, index) => <button className={`kill-event ${selectedEvent === index ? "selected" : ""}`} key={`${round.number}-${index}`} onClick={() => setSelectedEvent(index)}><time>{formatRoundTime(kill.time)}</time><span className={relationshipClass(kill.killerPuuid)}>{kill.killerName}</span><span className={kill.headshot ? "headshot" : ""}>{kill.weaponIcon ? <img src={kill.weaponIcon} alt={kill.weaponName}/> : <Crosshair/>}{kill.headshot && <b>HS</b>}</span><span className={relationshipClass(kill.victimPuuid)}>{kill.victimName}</span></button>) : <p className="empty-round">No kill events recorded for this round.</p>}</div></div><div className={`collapsible-match-map ${mobileMapOpen ? "open" : ""}`}><TacticalMap mapId={mapId} event={round.kills[selectedEvent]} players={details.players} ownTeam={ownTeamName}/></div></div>
                        </>}
                    </section>
                    <TeamDisplay label="Enemy team" players={enemyTeam} premadeColors={premadeColors} isVictory={false} rounds_played={roundsPlayed} isBottom={true} viewerPuuid={viewerPuuid} rounds={details.rounds} selectedRound={round?.number} onRoundSelect={setSelectedRound}/>
                </div>
            </div>
            {activeTab === 'timeline' && <div className="timeline-panel">
                <div className="round-selector" aria-label="Select round">{details.rounds.map((item) => <button type="button" key={item.number} className={`${isOwnTeamWin(item.winningTeam) ? 'ally' : 'enemy'} ${item.number === round?.number ? 'selected' : ''}`} onClick={() => setSelectedRound(item.number)}><span>{item.number}</span><small>{item.kills.length}</small></button>)}</div>
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
            </div>}
        </div>
    );
};

export const MatchDetailsPanel = ({details, roundsPlayed, viewerPuuid, mapId}: { details: MatchDetails; roundsPlayed: number; viewerPuuid?: string | null; mapId?: string }) => {
    const [activeTab, setActiveTab] = useState<'scoreboard' | 'timeline' | 'performance'>('scoreboard');
    const premadeColors = getPremadeColors(details.players);
    const [tabIndicator, setTabIndicator] = useState({left: 0, width: 0});
    const tabsRef = useRef<HTMLDivElement | null>(null);
    const firstRound = details.rounds[0]?.number ?? 1;
    const [selectedRound, setSelectedRound] = useState(firstRound);
    const [selectedEvent, setSelectedEvent] = useState<number | null>(null);
    const [hoveredEvent, setHoveredEvent] = useState<number | null>(null);
    const scrollDrag = useRef<{element: HTMLElement; pointerId: number; axis: 'x' | 'y'; x: number; y: number; left: number; top: number; moved: boolean} | null>(null);
    const dragged = useRef(false);
    const eventFeedRef = useRef<HTMLDivElement | null>(null);
    const roundStripRef = useRef<HTMLDivElement | null>(null);
    const [eventFeedOverflow, setEventFeedOverflow] = useState(false);
    const [eventFeedHasMore, setEventFeedHasMore] = useState(false);
    const [roundStripOverflow, setRoundStripOverflow] = useState(false);
    const viewer = details.players.find((player) => player.puuid === viewerPuuid);
    const ownTeamName = viewer?.team?.toLowerCase() || details.players[0]?.team?.toLowerCase();
    const ownTeam = details.players.filter((player) => player.team?.toLowerCase() === ownTeamName).sort((a, b) => b.score - a.score);
    const enemyTeam = details.players.filter((player) => player.team?.toLowerCase() !== ownTeamName).sort((a, b) => b.score - a.score);
    const round = details.rounds.find((item) => item.number === selectedRound) ?? details.rounds[0];
    const isOwnTeamWin = (winningTeam: string) => winningTeam.toLowerCase() === ownTeamName;
    const playerTeam = (puuid?: string) => details.players.find((player) => player.puuid === puuid)?.team?.toLowerCase();
    const eventPlayer = (puuid?: string) => details.players.find((player) => player.puuid === puuid);
    const relationshipClass = (puuid?: string) => playerTeam(puuid) === ownTeamName ? 'teammate' : 'enemy';
    const ownScore = details.rounds.filter((item) => isOwnTeamWin(item.winningTeam)).length;
    const enemyScore = details.rounds.filter((item) => item.winningTeam?.toLowerCase() !== ownTeamName && item.winningTeam?.toLowerCase() !== 'unknown').length;
    const firstHalf = details.rounds.slice(0, 12);
    const secondHalf = details.rounds.slice(12, 24);
    const halfScore = (rounds: MatchRound[]) => ({
        own: rounds.filter((item) => isOwnTeamWin(item.winningTeam)).length,
        enemy: rounds.filter((item) => item.winningTeam?.toLowerCase() !== ownTeamName && item.winningTeam?.toLowerCase() !== 'unknown').length,
    });
    const attackScore = halfScore(firstHalf);
    const defenseScore = halfScore(secondHalf);

    useEffect(() => { setSelectedEvent(null); setHoveredEvent(null); }, [selectedRound]);
    const updateEventOverflow = () => {
        const feed = eventFeedRef.current;
        if (!feed) return;
        const overflow = feed.scrollHeight > feed.clientHeight + 1;
        setEventFeedOverflow(overflow);
        setEventFeedHasMore(overflow && feed.scrollTop + feed.clientHeight < feed.scrollHeight - 2);
    };
    useEffect(() => {
        const feed = eventFeedRef.current;
        if (!feed || activeTab !== 'timeline') return;
        feed.scrollTop = 0;
        const frame = requestAnimationFrame(updateEventOverflow);
        const observer = new ResizeObserver(updateEventOverflow);
        observer.observe(feed);
        return () => { cancelAnimationFrame(frame); observer.disconnect(); };
    }, [activeTab, selectedRound, round?.kills.length]);
    useEffect(() => {
        const strip = roundStripRef.current;
        if (!strip || activeTab !== 'timeline') return;
        const update = () => setRoundStripOverflow(strip.scrollWidth > strip.clientWidth + 1);
        const frame = requestAnimationFrame(update);
        const observer = new ResizeObserver(update);
        observer.observe(strip);
        return () => { cancelAnimationFrame(frame); observer.disconnect(); };
    }, [activeTab, details.rounds.length]);

    useEffect(() => {
        const tabs = tabsRef.current;
        if (!tabs) return;
        const updateIndicator = () => {
            const selected = tabs.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]');
            if (selected) setTabIndicator({left: selected.offsetLeft, width: selected.offsetWidth});
        };
        updateIndicator();
        const observer = new ResizeObserver(updateIndicator);
        observer.observe(tabs);
        return () => observer.disconnect();
    }, [activeTab]);

    const selectTab = (tab: 'scoreboard' | 'timeline' | 'performance') => {
        setActiveTab(tab);
        if (tab === 'timeline') {
            setSelectedRound(firstRound);
            setSelectedEvent(null);
        }
    };

    const firstEngagements = new Map<string, {kills: number; deaths: number}>();
    details.rounds.forEach((matchRound) => {
        const firstKill = matchRound.kills.reduce<RoundKill | undefined>((earliest, kill) => !earliest || kill.time < earliest.time ? kill : earliest, undefined);
        if (!firstKill) return;
        if (firstKill.killerPuuid) {
            const entry = firstEngagements.get(firstKill.killerPuuid) ?? {kills: 0, deaths: 0};
            entry.kills += 1;
            firstEngagements.set(firstKill.killerPuuid, entry);
        }
        if (firstKill.victimPuuid) {
            const entry = firstEngagements.get(firstKill.victimPuuid) ?? {kills: 0, deaths: 0};
            entry.deaths += 1;
            firstEngagements.set(firstKill.victimPuuid, entry);
        }
    });

    const renderScoreboardTeam = (label: string, players: PlayerStats[], relation: 'ally' | 'enemy') => {
        const averageAcs = players.length ? Math.round(players.reduce((sum, player) => sum + (player.rounds_played ?? roundsPlayed ? player.score / (player.rounds_played ?? roundsPlayed) : 0), 0) / players.length) : 0;
        const averageAdr = players.length ? Math.round(players.reduce((sum, player) => sum + (player.rounds_played ?? roundsPlayed ? player.damage_made / (player.rounds_played ?? roundsPlayed) : 0), 0) / players.length) : 0;
        return <section className={`compact-team-table ${relation}`}>
        <h4><span>{label}</span><small>Avg ACS {averageAcs} · Avg ADR {averageAdr}</small></h4>
        <div className="compact-team-columns"><span>Player</span><span>Rank</span><span>KDA</span><span>ACS</span><span>ADR</span><span>HS%</span><span>FK</span><span>FD</span><span>+/-</span></div>
        <div className="compact-team-players">{players.map((player) => {
            const rp = player.rounds_played ?? roundsPlayed;
            const differential = player.kills - player.deaths;
            const firsts = firstEngagements.get(player.puuid) ?? {kills: 0, deaths: 0};
            const isViewer = Boolean(viewerPuuid && player.puuid === viewerPuuid);
            const partyColor = player.partyId ? premadeColors.get(player.partyId) : undefined;
            return <div className={`compact-score-row ${isViewer ? 'is-viewer' : ''}`} key={player.puuid || `${player.name}-${player.agent}`}>
                <div className="compact-score-player"><div className={`compact-score-agent-avatar ${partyColor ? 'has-party' : ''}`} style={partyColor ? {"--party-color": partyColor} as CSSProperties : undefined}>{player.agentIcon ? <img src={player.agentIcon} alt={player.agent}/> : <span>{player.agent[0]}</span>}{typeof player.level === "number" && <AccountLevelBadge level={player.level}/>}</div><div><div className="scoreboard-player-name-line"><a href={playerUuidPath(player.puuid)}>{player.name}{player.tag && <i>#{player.tag}</i>}</a>{isViewer && <b>You</b>}</div><div className="compact-player-agent-line"><small>{player.agent}</small></div></div></div>
                <div className="compact-score-rank"><img src={`https://media.valorant-api.com/competitivetiers/${TIER_SET}/${player.currentTier || 0}/smallicon.png`} alt={player.currentTierName || 'Unranked'}/><span>{player.currentTierName || 'Unranked'}</span></div>
                <span>{player.kills}/{player.deaths}/{player.assists}</span><span>{rp ? Math.round(player.score / rp) : 0}</span><span>{rp ? Math.round(player.damage_made / rp) : 0}</span><span>{getHeadshotPercentage(player)}</span><span>{firsts.kills}</span><span>{firsts.deaths}</span><strong className={differential >= 0 ? 'positive' : 'negative'}>{differential > 0 ? '+' : ''}{differential}</strong>
            </div>;
        })}</div>
    </section>;
    };

    const activeEventIndex = hoveredEvent ?? selectedEvent;
    const mapEvent = round?.kills[activeEventIndex ?? 0];
    const beginScrollDrag = (event: ReactPointerEvent<HTMLElement>, axis: 'x' | 'y') => {
        if (event.pointerType !== 'mouse' || event.button !== 0) return;
        dragged.current = false;
        const element = event.currentTarget;
        scrollDrag.current = {element, pointerId: event.pointerId, axis, x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop, moved: false};
    };
    const moveScrollDrag = (event: ReactPointerEvent<HTMLElement>) => {
        const dragState = scrollDrag.current;
        if (!dragState || dragState.pointerId !== event.pointerId) return;
        const dx = event.clientX - dragState.x;
        const dy = event.clientY - dragState.y;
        if (Math.abs(dragState.axis === 'x' ? dx : dy) > 4 && !dragState.moved) {
            dragState.moved = true;
            dragState.element.setPointerCapture(event.pointerId);
            dragState.element.classList.add('dragging');
        }
        if (dragState.axis === 'x') dragState.element.scrollLeft = dragState.left - dx;
        else dragState.element.scrollTop = dragState.top - dy;
    };
    const endScrollDrag = (event: ReactPointerEvent<HTMLElement>) => {
        const dragState = scrollDrag.current;
        if (!dragState || dragState.pointerId !== event.pointerId) return;
        dragged.current = dragState.moved;
        dragState.element.classList.remove('dragging');
        if (dragState.element.hasPointerCapture(event.pointerId)) dragState.element.releasePointerCapture(event.pointerId);
        scrollDrag.current = null;
    };
    const consumeDrag = () => {
        if (!dragged.current) return false;
        dragged.current = false;
        return true;
    };
    const renderRoundButton = (item: MatchRound) => {
        const kills = item.kills.filter((kill) => kill.killerPuuid === viewerPuuid).length;
        const died = item.kills.some((kill) => kill.victimPuuid === viewerPuuid);
        const visibleKillDots = Math.min(kills, died ? 5 : 6);
        const combatLabel = kills || died ? `${kills} ${kills === 1 ? 'kill' : 'kills'}${died ? ', died' : ', survived'}` : 'No kills or death recorded';
        return <button type="button" key={item.number} className={`${isOwnTeamWin(item.winningTeam) ? 'ally' : 'enemy'} ${item.number === round?.number ? 'selected' : ''}`} onClick={() => { if (!consumeDrag()) setSelectedRound(item.number); }} aria-label={`Round ${item.number}: ${combatLabel}`} title={`Round ${item.number} · ${combatLabel}`}><span>{item.number}</span>{kills || died ? <small className={`round-combat-marker ${kills && died ? 'split' : died ? 'death' : 'kills'}`}><img className="round-skull-image" src={kills && died ? '/icons/rounds/skull-kill-death.svg' : '/icons/rounds/skull.svg'} alt="" draggable={false}/><i className="round-combat-dots" aria-hidden="true">{Array.from({length: visibleKillDots}, (_, index) => <i className="kill-dot" key={`kill-${index}`}/>)}{died && <i className="death-dot"/>}</i></small> : <small className="round-combat-empty" aria-hidden="true"/>}</button>;
    };
    const moveRoundStrip = (action: 'start' | 'previous' | 'next' | 'end') => {
        const strip = roundStripRef.current;
        if (!strip) return;
        const target = action === 'start' ? 0 : action === 'end' ? strip.scrollWidth : strip.scrollLeft + (action === 'previous' ? -37 : 37);
        strip.scrollTo({left: target, behavior: 'smooth'});
    };

    return <div className="match-details-panel tabbed-match-details">
        <div ref={tabsRef} className="match-tabs" role="tablist" aria-label="Match details">
            <button type="button" role="tab" aria-selected={activeTab === 'scoreboard'} className={activeTab === 'scoreboard' ? 'active' : ''} onClick={() => selectTab('scoreboard')}>Scoreboard</button>
            <button type="button" role="tab" aria-selected={activeTab === 'timeline'} className={activeTab === 'timeline' ? 'active' : ''} onClick={() => selectTab('timeline')}>Round Timeline</button>
            <button type="button" role="tab" aria-selected={activeTab === 'performance'} className={activeTab === 'performance' ? 'active' : ''} onClick={() => selectTab('performance')}>Performance</button>
            <span className="match-tab-indicator" style={{left: tabIndicator.left, width: tabIndicator.width}} aria-hidden="true"/>
        </div>

        {activeTab === 'scoreboard' && <div className="tab-scoreboard">
            {renderScoreboardTeam('Your team', ownTeam, 'ally')}
            {renderScoreboardTeam('Enemy team', enemyTeam, 'enemy')}
        </div>}

        {activeTab === 'timeline' && <div className="tab-round-timeline">
            <div className="round-strip-header"><div className="round-strip-shell">{roundStripOverflow && <span className="round-strip-controls before"><button type="button" onClick={() => moveRoundStrip('start')} aria-label="First round"><ChevronsLeft/></button><button type="button" onClick={() => moveRoundStrip('previous')} aria-label="Previous round"><ChevronLeft/></button></span>}<div ref={roundStripRef} className="tab-round-strip" aria-label="Select round" onPointerDown={(event) => beginScrollDrag(event, 'x')} onPointerMove={moveScrollDrag} onPointerUp={endScrollDrag} onPointerCancel={endScrollDrag}>{firstHalf.length > 0 && <span className="half-context"><b>Attack</b><em><i>{attackScore.own}</i> – <i>{attackScore.enemy}</i></em></span>}{details.rounds.slice(0,12).map(renderRoundButton)}{details.rounds.length > 12 && <span className="round-phase-divider">Side switch</span>}{details.rounds.slice(12,24).map(renderRoundButton)}{secondHalf.length > 0 && <span className="half-context"><b>Defense</b><em><i>{defenseScore.own}</i> – <i>{defenseScore.enemy}</i></em></span>}{details.rounds.length > 24 && <span className="round-phase-divider overtime">Over time</span>}{details.rounds.slice(24).map(renderRoundButton)}</div>{roundStripOverflow && <span className="round-strip-controls after"><button type="button" onClick={() => moveRoundStrip('next')} aria-label="Next round"><ChevronRight/></button><button type="button" onClick={() => moveRoundStrip('end')} aria-label="Last round"><ChevronsRight/></button></span>}</div><div className="timeline-match-score"><small>Final</small><div><strong>{ownScore}</strong><span>Your Team</span></div><i>—</i><div><strong>{enemyScore}</strong><span>Enemy Team</span></div></div></div>
            {round ? <div className="tab-round-analysis">
                <section className="tab-round-events">
                    <header><span className="round-title">Round {round.number}</span><div><strong className={isOwnTeamWin(round.winningTeam) ? 'ally-result' : 'enemy-result'}>{isOwnTeamWin(round.winningTeam) ? 'Your Team Won' : 'Enemy Team Won'}</strong><small>{round.endType.replaceAll('_', ' ')}</small></div><span>{round.kills.length} Kills</span></header>
                    <div ref={eventFeedRef} className={`kill-feed ${eventFeedOverflow ? 'has-overflow' : ''}`} onScroll={updateEventOverflow} onPointerDown={(event) => beginScrollDrag(event, 'y')} onPointerMove={moveScrollDrag} onPointerUp={endScrollDrag} onPointerCancel={endScrollDrag}>{round.kills.length ? round.kills.map((kill, index) => { const killer = eventPlayer(kill.killerPuuid); const victim = eventPlayer(kill.victimPuuid); return <button className={`kill-event ${activeEventIndex === index ? 'selected' : ''}`} aria-pressed={selectedEvent === index} key={`${round.number}-${index}`} onMouseEnter={() => setHoveredEvent(index)} onMouseLeave={() => setHoveredEvent(null)} onClick={() => { if (!consumeDrag()) setSelectedEvent((current) => current === index ? null : index); }}><time>{formatRoundTime(kill.time)}</time><span className={`event-player ${relationshipClass(kill.killerPuuid)}`}>{killer?.agentIcon && <img src={killer.agentIcon} alt=""/>}<i>{kill.killerName}</i></span><span className={kill.headshot ? 'headshot' : ''}>{kill.weaponIcon ? <img src={kill.weaponIcon} alt={kill.weaponName}/> : <Crosshair/>}{kill.headshot && <b>HS</b>}</span><span className={`event-player ${relationshipClass(kill.victimPuuid)}`}>{victim?.agentIcon && <img src={victim.agentIcon} alt=""/>}<i>{kill.victimName}</i></span></button>; }) : <p className="empty-round">No kill events recorded for this round.</p>}</div>
                    {eventFeedHasMore && <div className="event-scroll-hint" aria-hidden="true"><span>Scroll or drag for more events</span><ChevronDown/></div>}
                </section>
                <aside className="tab-round-map" aria-label={`Round ${round.number} minimap`}><header><strong>Round {round.number} Map View</strong><small>{activeEventIndex === null || !mapEvent ? 'Round positions' : formatRoundTime(mapEvent.time)}</small></header><div className="round-map-body"><div className="round-map-stage"><TacticalMap mapId={mapId} event={mapEvent} players={details.players} ownTeam={ownTeamName} emphasizeEvent={activeEventIndex !== null}/></div><div className="round-map-legend"><span className="ally">● <i>Your Team</i></span><span className="enemy">● <i>Enemy Team</i></span><span>× <i>Kill</i></span></div></div></aside>
            </div> : <div className="empty-details">Round data was not supplied by the match provider.</div>}
        </div>}

        {activeTab === 'performance' && <div className="match-performance-grid">
            {viewer ? [['Kills', viewer.kills], ['Deaths', viewer.deaths], ['Assists', viewer.assists], ['ACS', roundsPlayed ? Math.round(viewer.score / roundsPlayed) : 0], ['ADR', roundsPlayed ? Math.round(viewer.damage_made / roundsPlayed) : 0], ['Headshot %', getHeadshotPercentage(viewer)], ['K/D', viewer.deaths ? (viewer.kills / viewer.deaths).toFixed(2) : viewer.kills.toFixed(2)]].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>) : <p className="empty-details">Performance data unavailable.</p>}
        </div>}
    </div>;
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
                                premadeColors,
                                isVictory,
                                rounds_played,
                                isBottom,
                                rounds = [],
                                selectedRound,
                                onRoundSelect,
                                viewerPuuid,
                                showTimelineControls = false,
                                onTimelineMove
                            }: {
    label: string;
    players: PlayerStats[];
    premadeColors: Map<string, string>;
    isVictory: boolean;
    rounds_played: number;
    isBottom: boolean;
    rounds?: MatchRound[];
    selectedRound?: number;
    onRoundSelect?: (round: number) => void;
    viewerPuuid?: string | null;
    showTimelineControls?: boolean;
    onTimelineMove?: (action: 'start' | 'previous' | 'next' | 'end') => void;
}) => {
    const borderColor = isVictory ? "border-[#4ade80]" : "border-[#f87171]";
    const bgColor = isVictory ? "bg-[#4ade80]/10" : "bg-[#f87171]/10";
    const teamStyle = isVictory
        ? "bg-gradient-to-br from-[#4ade80] to-[#15803d]"
        : "bg-gradient-to-br from-[#f87171] to-[#dc2626]";
    return (
        <div
            className={`p-3 border-l border-r ${isBottom ? "border-b rounded-b-lg" : ""} ${bgColor} ${borderColor}`}>
            <div className="scoreboard-team-header">
                <h4>{label}<small className="timeline-scroll-hint" aria-hidden="true">Swipe rounds <span>↔</span></small></h4>
                <div className="timeline-header-shell">
                    {showTimelineControls && <span className="scoreboard-timeline-controls before" aria-label="Previous timeline navigation"><button type="button" onClick={() => onTimelineMove?.('start')} aria-label="First round"><ChevronsLeft/></button><button type="button" onClick={() => onTimelineMove?.('previous')} aria-label="Previous round"><ChevronLeft/></button></span>}
                    <div className="scoreboard-round-numbers" aria-label="Round numbers">{rounds.map((round) => <span key={round.number}>{round.number}</span>)}</div>
                    {showTimelineControls && <span className="scoreboard-timeline-controls after" aria-label="Next timeline navigation"><button type="button" onClick={() => onTimelineMove?.('next')} aria-label="Next round"><ChevronRight/></button><button type="button" onClick={() => onTimelineMove?.('end')} aria-label="Last round"><ChevronsRight/></button></span>}
                </div>
                <span aria-hidden="true" />
            </div>
            <div className="scoreboard-player-list">
                {players.map((player, idx) => {
                    const partyColor = player.partyId ? premadeColors.get(player.partyId) : undefined;
                    return (
                    <MatchPlayerRow
                        key={idx}
                        player={player}
                        teamStyle={teamStyle}
                        partyColor={partyColor}
                        rounds_played={rounds_played}
                        rounds={rounds}
                        selectedRound={selectedRound}
                        onRoundSelect={onRoundSelect}
                        isViewer={Boolean(viewerPuuid && player.puuid === viewerPuuid)}
                    />
                );})}
            </div>
        </div>
    );
};

// Loading skeleton component
export const MatchSkeleton = () => (
    <div className="match-skeleton-card">
        <div className="match-skeleton-grid">
            <div className="match-skeleton-agent">
                <Skeleton className="match-skeleton-portrait"/>
                <div className="match-skeleton-agent-copy">
                    <Skeleton className="match-skeleton-agent-name"/>
                    <Skeleton className="match-skeleton-badge"/>
                </div>
            </div>

            <span className="match-skeleton-separator" aria-hidden="true"/>

            <div className="match-skeleton-main">
                <div className="match-skeleton-map-group">
                    <Skeleton className="match-skeleton-map"/>
                    <Skeleton className="match-skeleton-kda"/>
                </div>
                <div className="match-skeleton-section-separator" aria-hidden="true"/>
                <div className="match-skeleton-result-group">
                    <div><Skeleton/><Skeleton/></div>
                    <div><Skeleton/><span/><Skeleton/></div>
                </div>
                <div className="match-skeleton-section-separator" aria-hidden="true"/>
                <div className="match-skeleton-rank-group">
                    <div><Skeleton/><Skeleton/></div>
                    <Skeleton/>
                </div>
            </div>

            <span className="match-skeleton-meta-gap" aria-hidden="true"/>

            <div className="match-skeleton-meta">
                <Skeleton/>
                <Skeleton/>
            </div>

            <Skeleton className="match-skeleton-chevron"/>
        </div>
    </div>
);
