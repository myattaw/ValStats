import { Crosshair, Skull, Target, Trophy } from 'lucide-react';

interface PlayerStats {
    kd_ratio: number;
    headshot_percent: number;
    avg_combat_score: number;
    kills_per_round: number;
}

interface StatsOverviewProps {
    stats: PlayerStats | null;
    loading?: boolean;
}

export function StatsOverview({ stats }: StatsOverviewProps) {

    if (!stats) {
        return (
            <div className="stats-grid">
                {[1,2,3,4].map(i => (
                    <div key={i} className="stat-card stat-loading">
                        <div className="animate-pulse"/>
                    </div>
                ))}
            </div>
        );
    }

    const statCards = [
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
            label: 'Kills per Round',
            value: stats.kills_per_round.toFixed(2),
            icon: Skull,
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
                    </div>
                </div>
            ))}
        </div>
    );
}
