# Architecture Quick Reference

## Data Flow Diagrams

### Match History Request
```
┌─────────────────┐
│   Client        │
└────────┬────────┘
         │ GET /matches/{region}/{name}/{tag}
         ▼
┌─────────────────────────────────────────┐
│  ValorantService                        │
│  - Resolve PUUID                        │
│  - Delegate to MatchDataService         │
└────────┬────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│  MatchDataService                       │
│  - Check if cached in DynamoDB          │
│  ├─ If YES: Return cached + MMR         │
│  └─ If NO: Call API                     │
└────────┬────────────────────────────────┘
         │
         ├─ Cache Hit? ────────────────┐
         │                              │
         ▼                              ▼
    NO: API Call             YES: DynamoDB Read
    └──────────┐                       │
               │                       │
               ▼                       │
    HenrikDev API                      │
    - getStoredMatches()               │
    - getMMRHistory()                  │
               │                       │
               ├──────────────────┬────┘
               │                  │
               ▼                  ▼
    Store in DynamoDB      MatchResponseFormatter
               │                  │
               └──────────────────┤
                                  │
                                  ▼
                            Formatted Response
                            {"status": 200,
                             "cached": bool,
                             "data": [...]}
```

### Player Stats Request
```
┌─────────────────┐
│   Client        │
└────────┬────────┘
         │ GET /stats/{region}/{name}/{tag}?season={id}
         ▼
┌─────────────────────────────────────────┐
│  ValorantService                        │
│  - Resolve PUUID                        │
│  - Delegate to PlayerStatsService       │
└────────┬────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│  PlayerStatsService                     │
│  - Check DynamoDB for aggregates        │
│  ├─ If YES: Return stats                │
│  └─ If NO: Load from API & process      │
└────────┬────────────────────────────────┘
         │
         ├─ Cache Hit? ────────────────┐
         │                              │
         ▼                              ▼
    NO: Load Matches          YES: DynamoDB Read
    - getStoredMatches()           │
    - Process each match            │
    - Aggregate stats               │
    - Store aggregates              │
               │                    │
               ├────────────────────┘
               │
               ▼
    Calculate Stats:
    - K/D Ratio
    - Headshot %
    - ACS (Avg Combat Score)
    - K/R (Kills per Round)
    - ADR (Avg Damage per Round)
               │
               ▼
    {"status": 200,
     "data": {
       "kd_ratio": 1.25,
       "headshot_percent": 22.5,
       "avg_combat_score": 245.3,
       ...
     }}
```

### Recent Matches Update (5-Min Cooldown)
```
┌─────────────────┐
│   Client        │
└────────┬────────┘
         │ GET /matches/{region}/{name}/{tag}/refresh
         ▼
┌─────────────────────────────────────────┐
│  ValorantService                        │
│  - Delegate to MatchDataService         │
└────────┬────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│  MatchDataService                       │
│  - Get last update timestamp            │
│  ├─ Check time delta                    │
│  ├─ If < 5 min: SKIP (cooldown active) │
│  └─ If ≥ 5 min: Call API               │
└────────┬────────────────────────────────┘
         │
         ├─ Cooldown? ──────────────┐
         │                          │
         ▼                          ▼
    YES: Return cached      NO: Call API
         │                  - getRecentMatches()
         │                  - Process new matches
         │                  - Update timestamp
         │                         │
         └─────────────────────────┘
                                   │
                                   ▼
                        {"status": 200}
```

## Service Responsibilities

### ValorantService (Facade)
**Responsibility**: Route requests to appropriate services

**Methods**:
- `getUnifiedMatches()` → MatchDataService
- `getAccountDetails()` → ValorantApiClient (direct)
- `getMatchById()` → MatchDataService
- `getPlayerStats()` → PlayerStatsService
- `getPlayerAdr()` → PlayerStatsService
- `updateRecentMatches()` → MatchDataService
- `resolvePuuid()` (private) → caches resolution

### MatchDataService (Cache Strategy)
**Responsibility**: Implement cache-first strategy for matches

**Key Logic**:
```
getPlayerMatches():
  1. Check DynamoDB cache
  2. If found → Format and return
  3. If not → Call API
  4. Cache API response
  5. Format and return
```

**Methods**:
- `getPlayerMatches()` - Get match history with pagination
- `getMatchDetails()` - Get full match details
- `updateRecentMatches()` - Enforce 5-min cooldown, fetch new matches
- `cacheMMRHistory()` (private) - Store MMR entries

### MatchResponseFormatter (Serialization)
**Responsibility**: Format various data sources into consistent API response

**Methods**:
- `formatCachedMatches()` - DynamoDB items → API response
- `formatApiMatches()` - HenrikDev API → API response
- `formatCachedMatchDetails()` - Match metadata + players → Details response
- Helper methods for type conversion and data extraction

### PlayerStatsService (Aggregation)
**Responsibility**: Calculate player statistics from match data

**Caching Logic**:
```
getPlayerStats(puuid, seasonId):
  1. Try DynamoDB aggregates
  2. If not found → Load matches from API
  3. Process matches (MatchProcessor)
  4. Aggregates auto-stored by MatchProcessor
  5. Read aggregates and format
```

**Methods**:
- `getPlayerStats()` - Get K/D, HS%, ACS, K/R, ADR
- `getPlayerAdr()` - Get only ADR
- `loadPlayerMatchesFromAPI()` (private) - Lazy-load if needed
- `formatStats()` (private) - Calculate final metrics

### DynamoDbService (Data Access)
**Responsibility**: All DynamoDB operations

**New Methods** (for recent match tracking):
- `getPlayerLastRecentMatchUpdate()` - Get last update timestamp
- `updatePlayerLastRecentMatchUpdate()` - Set last update timestamp

### MatchProcessor (Data Processing)
**Responsibility**: Process matches and update aggregates

**Unchanged** - Still does the heavy lifting of:
- Parsing match data
- Storing matches in DynamoDB
- Updating player aggregates

### PlayerCacheService (Profile Cache)
**Responsibility**: Cache player PUUID resolution

**Used by**: ValorantService for PUUID lookups

---

## API Response Examples

### Match History (Cache Hit)
```json
{
  "status": 200,
  "cached": true,
  "data": [
    {
      "id": "match-uuid",
      "map": "Ascent",
      "result": "Victory",
      "score": 13,
      "enemy_score": 9,
      "kda": "18/12/5",
      "agent": "Sova",
      "acs": 234,
      "rank": "Gold 1",
      "rrChange": 22,
      "rounds_played": 22,
      "teams": {
        "red": {"rounds_won": 13},
        "blue": {"rounds_won": 9}
      },
      "hasDetails": false
    }
  ]
}
```

### Player Stats
```json
{
  "status": 200,
  "data": {
    "kd_ratio": 1.25,
    "headshot_percent": 22.5,
    "avg_combat_score": 245.3,
    "kills_per_round": 0.82,
    "adr": 156.75
  }
}
```

### Match Details
```json
{
  "status": 200,
  "cached": true,
  "data": {
    "metadata": {
      "matchid": "match-uuid",
      "map": "Ascent",
      "game_start": 1641934366,
      "rounds_played": 23
    },
    "players": {
      "all_players": [
        {
          "puuid": "player-uuid",
          "name": "Player",
          "team": "Red",
          "character": "Sova",
          "stats": {
            "kills": 18,
            "deaths": 12,
            "assists": 5,
            "score": 4869,
            "headshots": 5,
            "bodyshots": 10,
            "legshots": 3
          },
          "damage_made": 3067
        }
      ]
    }
  }
}
```

---

## Performance Characteristics

### First Time Lookup (Cache Miss)
- Time: 2 API calls + processing (~500-1000ms)
- Network: 2 HenrikDev API requests
- Result: Cached in DynamoDB

### Subsequent Lookups (Cache Hit)
- Time: DynamoDB query (~50-100ms)
- Network: 0 HenrikDev API requests
- Reduction: ~10x faster, 0 external API calls

### 5-Minute Recent Match Update
- Enforced via DynamoDB timestamp tracking
- Prevents rate limiting
- Allows real-time data without overwhelming API

---

## How to Monitor/Debug

### Check if data is cached
```bash
# Request returns "cached": true
GET /api/valorant/matches/na/Player/TAG

# Check DynamoDB for PLAYER#{puuid} records
aws dynamodb query --table-name valstats \
  --key-condition-expression "PK = :pk" \
  --expression-attribute-values '{":pk":{"S":"PLAYER#puuid"}}'
```

### Check recent match update cooldown
```bash
# Check last update timestamp
aws dynamodb get-item --table-name valstats \
  --key '{"PK":{"S":"PLAYER_UPDATE#na#Player#TAG"},
          "SK":{"S":"RECENT_MATCHES"}}'
```

### Disable cache (for testing)
- Delete DynamoDB items with PK = `PLAYER#{puuid}`
- Next request will hit API and repopulate cache

---

## Maintenance & Scaling

### Adding New Endpoints
1. Create logic in appropriate service (MatchDataService, PlayerStatsService, etc.)
2. Add routing method in ValorantService
3. Test cache behavior

### Changing Cache Strategy
1. Modify MatchDataService.getPlayerMatches() logic
2. Update MatchResponseFormatter if response format changes
3. Add new DynamoDB tracking methods if needed

### Performance Tuning
1. Monitor DynamoDB read/write capacity
2. Adjust TTL on items if storage is concern
3. Implement query pagination if result sets grow large
4. Consider GSI (Global Secondary Index) for common queries


