const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.TEST_DOUBLE_PORT || '8085', 10);
const HOST = process.env.TEST_DOUBLE_HOST || '0.0.0.0';
const DOCKER_HOST = process.env.DOCKER_CALLBACK_HOST || 'host.docker.internal';

const fixturePath = path.join(__dirname, 'e2e-archive-v1.json');
const fixtureData = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));

console.log(`[TEST-DOUBLE] Loaded fixture v${fixtureData.version} with ${fixtureData.games.length} games for account: ${fixtureData.account}`);

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;
  const timestamp = new Date().toISOString();

  console.log(`[TEST-DOUBLE] [${timestamp}] ${req.method} ${pathname}`);

  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (pathname === '/health') {
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'UP', fixtureVersion: fixtureData.version, gamesCount: fixtureData.games.length }));
    return;
  }

  // Matches /pub/player/{username}/games/archives
  const archivesMatch = pathname.match(/^\/pub\/player\/([^/]+)\/games\/archives$/i);
  if (archivesMatch) {
    const username = archivesMatch[1];
    if (username.toLowerCase() === 'provider_fail_player') {
      console.log(`[TEST-DOUBLE] Simulating upstream 500 error for archives request: ${username}`);
      res.writeHead(500);
      res.end(JSON.stringify({ message: 'Internal Server Error: simulated upstream outage' }));
      return;
    }

    const archiveUrl = `http://${DOCKER_HOST}:${PORT}/pub/player/${username}/games/2024/01`;
    console.log(`[TEST-DOUBLE] Serving archives for ${username}: ${archiveUrl}`);
    res.writeHead(200);
    res.end(JSON.stringify({
      archives: [archiveUrl]
    }));
    return;
  }

  // Matches /pub/player/{username}/games/2024/01 (or any year/month)
  const monthlyMatch = pathname.match(/^\/pub\/player\/([^/]+)\/games\/\d{4}\/\d{2}$/i);
  if (monthlyMatch) {
    const username = monthlyMatch[1];
    if (username.toLowerCase() === 'provider_fail_player') {
      console.log(`[TEST-DOUBLE] Simulating upstream 500 error for monthly archive request: ${username}`);
      res.writeHead(500);
      res.end(JSON.stringify({ message: 'Internal Server Error: simulated monthly games outage' }));
      return;
    }

    if (username.toLowerCase() === 'malformed_pgn_player') {
      console.log(`[TEST-DOUBLE] Serving archive with malformed PGN for: ${username}`);
      res.writeHead(200);
      res.end(JSON.stringify({
        games: [
          {
            url: "https://www.chess.com/game/live/99999999999",
            pgn: "[Event \"Malformed Game\"]\n[Site \"Chess.com\"]\n[Date \"2024.01.10\"]\n[White \"GrandmasterW\"]\n[Black \"malformed_pgn_player\"]\n[Result \"*\"]\n\n1. e4 e5 2. INVALID_CORRUPTED_TOKEN_#$@! 1-0",
            time_control: "180",
            end_time: 1704880000,
            rated: true,
            fen: "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
            time_class: "blitz",
            rules: "chess",
            white: {
              rating: 1500,
              result: "win",
              username: "GrandmasterW",
              uuid: "00000000-0000-0000-0000-000000000001"
            },
            black: {
              rating: 1450,
              result: "checkmated",
              username: "malformed_pgn_player",
              uuid: "00000000-0000-0000-0000-000000000002"
            }
          }
        ]
      }));
      return;
    }

    console.log(`[TEST-DOUBLE] Serving ${fixtureData.games.length} monthly games for ${username}`);
    res.writeHead(200);
    res.end(JSON.stringify({
      games: fixtureData.games
    }));
    return;
  }

  console.warn(`[TEST-DOUBLE] Unmatched path: ${pathname}`);
  res.writeHead(404);
  res.end(JSON.stringify({ error: 'Not found in test double' }));
});

server.listen(PORT, HOST, () => {
  console.log(`[TEST-DOUBLE] Server listening on http://${HOST}:${PORT} (Docker callback host: ${DOCKER_HOST})`);
});

process.on('SIGINT', () => {
  console.log('[TEST-DOUBLE] Shutting down server...');
  server.close(() => process.exit(0));
});

process.on('SIGTERM', () => {
  console.log('[TEST-DOUBLE] Shutting down server...');
  server.close(() => process.exit(0));
});
