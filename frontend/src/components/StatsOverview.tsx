import { Crosshair, Skull, Target, TrendingDown, TrendingUp, Trophy } from 'lucide-react';

interface PlayerStats {
    kd_ratio: number;
    headshot_percent: number;
    avg_combat_score: number;
    kills_per_round: number;
}

interface StatsOverviewProps {
    stats: PlayerStats | null;
}

export function StatsOverview({ stats }: StatsOverviewProps) {

    if (!stats) {
        return (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {[1,2,3,4].map(i => (
                    <div key={i} className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-3">
                        <div className="h-6 bg-[#2a2a2a] rounded animate-pulse"/>
                    </div>
                ))}
            </div>
        );
    }

    const statCards = [
        {
            label: 'K/D Ratio',
            value: stats.kd_ratio.toFixed(2),
            change: '',
            isPositive: true,
            icon: Target,
        },
        {
            label: 'Headshot %',
            value: `${stats.headshot_percent.toFixed(1)}%`,
            change: '',
            isPositive: true,
            icon: Crosshair,
        },
        {
            label: 'Avg Combat Score',
            value: stats.avg_combat_score.toFixed(0),
            change: '',
            isPositive: true,
            icon: Trophy,
        },
        {
            label: 'Kills per Round',
            value: stats.kills_per_round.toFixed(2),
            change: '',
            isPositive: true,
            icon: Skull,
        },
    ];

    return (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {statCards.map((stat) => (
                <div
                    key={stat.label}
                    className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-3 hover:border-[#2a2a2a] transition-colors"
                >
                    <div className="flex items-start justify-between mb-1.5">
                        <stat.icon className="w-3.5 h-3.5 text-[#4a7cff]" />
                        <div className={`flex items-center gap-0.5 ${stat.isPositive ? 'text-[#4ade80]' : 'text-[#f87171]'}`}>
                            {stat.isPositive ? (
                                <TrendingUp className="w-3 h-3"/>
                            ) : (
                                <TrendingDown className="w-3 h-3"/>
                            )}
                            <span className="text-xs">{stat.change}</span>
                        </div>
                    </div>

                    <div className="text-lg text-white mb-0.5">{stat.value}</div>
                    <div className="text-xs text-gray-400">{stat.label}</div>
                </div>
            ))}
        </div>
    );
}