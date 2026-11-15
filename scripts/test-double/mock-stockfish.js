#!/usr/bin/env node

const readline = require('readline');
const fs = require('fs');
const path = require('path');

const fixturesPath = path.join(__dirname, 'stockfish-fixtures.json');
let fixtures = { positions: {} };
if (fs.existsSync(fixturesPath)) {
  try {
    fixtures = JSON.parse(fs.readFileSync(fixturesPath, 'utf8'));
  } catch (e) {
    console.error('Failed to parse stockfish fixtures:', e);
  }
}

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false,
});

let currentFen = '';
let currentMove = null;
let multiPvLimit = 1;

rl.on('line', (raw) => {
  const line = raw.trim();
  if (!line) return;

  if (line === 'uci') {
    console.log('id name Stockfish 16 (Deterministic Mock Double)');
    console.log('id author ChessEcho Test Infrastructure');
    console.log('option name MultiPV type spin default 1 min 1 max 500');
    console.log('uciok');
  } else if (line === 'isready') {
    console.log('readyok');
  } else if (line.startsWith('setoption name MultiPV value ')) {
    const val = parseInt(line.split('value ')[1], 10);
    if (!isNaN(val)) multiPvLimit = val;
  } else if (line.startsWith('position fen ')) {
    const rest = line.substring('position fen '.length);
    if (rest.includes(' moves ')) {
      const parts = rest.split(' moves ');
      currentFen = parts[0].trim();
      currentMove = parts[1].trim();
    } else {
      currentFen = rest.trim();
      currentMove = null;
    }
  } else if (line.startsWith('go depth') || line.startsWith('go')) {
    handleGo();
  } else if (line === 'stop') {
    console.log('bestmove (none)');
  } else if (line === 'quit') {
    process.exit(0);
  }
});

function handleGo() {
  const posFixture = fixtures.positions && fixtures.positions[currentFen];

  if (currentMove && posFixture && posFixture.moves && posFixture.moves[currentMove]) {
    const moveData = posFixture.moves[currentMove];
    console.log(`info depth ${moveData.depth || 16} seldepth 22 multipv 1 score cp ${moveData.scoreCp} nodes 1500 nps 150000 tbhits 0 time 10 pv ${moveData.pv}`);
    console.log(`bestmove ${moveData.bestMove}`);
    return;
  }

  if (posFixture && posFixture.multiPv && posFixture.multiPv.length > 0) {
    const linesToEmit = posFixture.multiPv.slice(0, multiPvLimit);
    for (const pvEntry of linesToEmit) {
      console.log(`info depth ${pvEntry.depth || 16} seldepth 22 multipv ${pvEntry.rank} score cp ${pvEntry.scoreCp} nodes 1500 nps 150000 tbhits 0 time 10 pv ${pvEntry.pv}`);
    }
    console.log(`bestmove ${posFixture.bestMove || linesToEmit[0].pv.split(' ')[0]}`);
    return;
  }

  // Fallback default output for any other FEN
  console.log('info depth 16 seldepth 20 multipv 1 score cp 0 nodes 1000 nps 100000 tbhits 0 time 10 pv e2e4');
  console.log('bestmove e2e4');
}
