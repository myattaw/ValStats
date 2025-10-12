import {Crosshair, Skull, Target, TrendingDown, TrendingUp, Trophy} from 'lucide-react';

export function StatsOverview() {
    const stats = [
        {
            label: 'K/D Ratio',
            value: '1.32',
            change: '+0.08',
            isPositive: true,
            icon: Target,
        },
        {
            label: 'Headshot %',
            value: '28.4%',
            change: '+2.1%',
            isPositive: true,
            icon: Crosshair,
        },
        {
            label: 'Avg Combat Score',
            value: '267',
            change: '-12',
            isPositive: false,
            icon: Trophy,
        },
        {
            label: 'Kills per Round',
            value: '0.89',
            change: '+0.04',
            isPositive: true,
            icon: Skull,
        },
    ];

    return (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {stats.map((stat) => (
                <div
                    key={stat.label}
                    className="bg-[#0f0f0f] border border-[#1a1a1a] rounded-lg p-3 hover:border-[#2a2a2a] transition-colors"
                >
                    <div className="flex items-start justify-between mb-1.5">
                        <stat.icon className="w-3.5 h-3.5 text-[#4a7cff]"/>
                        <div
                            className={`flex items-center gap-0.5 ${stat.isPositive ? 'text-[#4ade80]' : 'text-[#f87171]'}`}>
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
