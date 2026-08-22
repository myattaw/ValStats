import { Activity, Crosshair, Gauge, Skull, Swords, Target, Trophy } from 'lucide-react';
import type { PlayerStats } from '../types/player';

interface StatsOverviewProps {
    stats: PlayerStats | null;
    loading?: boolean;
}

export function StatsOverview({ stats, loading = false }: StatsOverviewProps) {

    if (!stats || loading) {
        return (
            <div className="stats-grid">
                {[1,2,3,4,5,6,7].map(i => (
                    <div key={i} className="stat-card stat-loading">
                        <div className="animate-pulse"/>
                    </div>
                ))}
            </div>
        );
    }

    const statCards = [
        {
            label: 'Win Rate',
            value: `${(stats.win_rate ?? 0).toFixed(1)}%`,
            detail: `${stats.wins ?? 0}W / ${stats.losses ?? 0}L${stats.draws ? ` / ${stats.draws}D` : ''}`,
            icon: Swords,
        },
        {
            label: 'K/D Ratio',
            value: stats.kd_ratio.toFixed(2),
            icon: Target,
        },
        {
            label: 'Headshot %',
            value: `${stats.headshot_percent.toFixed(1)}%`,
            icon: Crosshair,
        },
        {
            label: 'Avg Combat Score',
            value: stats.avg_combat_score.toFixed(0),
            icon: Trophy,
        },
        {
            label: 'Damage per Round',
            value: (stats.adr ?? 0).toFixed(1),
            icon: Activity,
        },
        {
            label: 'Kills per Round',
            value: stats.kills_per_round.toFixed(2),
            icon: Skull,
        },
        {
            label: 'Matches',
            value: String(stats.matches_played ?? 0),
            icon: Gauge,
        },
    ];

    return (
        <div className="stats-grid">
            {statCards.map((stat) => (
                <div
                    key={stat.label}
                    className="stat-card"
                >
                    <div className="stat-icon">
                        <stat.icon />
                    </div>
                    <div className="stat-copy">
                        <div className="stat-value">{stat.value}</div>
                        <div className="stat-label">{stat.label}</div>
                        {stat.detail && <div className="stat-detail">{stat.detail}</div>}
                    </div>
                </div>
            ))}
        </div>
    );
}
