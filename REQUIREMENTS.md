Plugin functional requirements
==============================

- The idea is that players have time quotas which is burnt while they are online.
- Time quotas can be extended multiple ways, see later how.
- Every player, when they first join, start with a 8 hours quota.
- Every week, players get additional 8 hours added to their quota.
  - This allotment is received when a player logs in and they have not received it for at least a week. This prevents
    players, who never log in getting quotas.
- If a player kills another player, 1 hour quota is transferred from the victim to the victor.
- You need to periodically persist state (player quotas, when they last received their weekly allotment) in a JSON file,
  so the state survives server restarts.
- You need to show every player their current quota in a scoreboard.
