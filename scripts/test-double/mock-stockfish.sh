#!/bin/sh
multiPv=1
currentFen=""
currentMove=""

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    uci)
      echo "id name Stockfish 16 (Deterministic Mock Double)"
      echo "id author ChessEcho Test Infrastructure"
      echo "option name MultiPV type spin default 1 min 1 max 500"
      echo "uciok"
      ;;
    isready)
      echo "readyok"
      ;;
    setoption\ name\ MultiPV*)
      multiPv=$(echo "$line" | sed 's/.*value //')
      ;;
    position\ fen*)
      if echo "$line" | grep -q " moves "; then
        currentFen=$(echo "$line" | sed 's/position fen //' | sed 's/ moves .*//')
        currentMove=$(echo "$line" | sed 's/.* moves //')
      else
        currentFen=$(echo "$line" | sed 's/position fen //')
        currentMove=""
      fi
      ;;
    go*)
      if [ -n "$currentMove" ] && [ "$currentMove" = "f7f6" ]; then
        echo "info depth 16 seldepth 22 multipv 1 score cp 450 nodes 1500 nps 150000 tbhits 0 time 10 pv f3e5 f6e5 d1h5 e8e7 h5e5 e7f7 f1c4 d7d5 c4d5 f7g6"
        echo "bestmove f3e5"
      elif echo "$currentFen" | grep -q "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"; then
        echo "info depth 16 seldepth 22 multipv 1 score cp 20 nodes 1500 nps 150000 tbhits 0 time 10 pv b8c6 f1c4 g8f6 d2d3 f8c5 c2c3 d7d6"
        if [ "$multiPv" -ge 2 ]; then
          echo "info depth 16 seldepth 22 multipv 2 score cp 40 nodes 1500 nps 150000 tbhits 0 time 10 pv d7d6 d2d4 e5d4 f3d4 g8f6 b1c3 f8e7"
        fi
        if [ "$multiPv" -ge 3 ]; then
          echo "info depth 16 seldepth 22 multipv 3 score cp 50 nodes 1500 nps 150000 tbhits 0 time 10 pv g8f6 f3e5 d7d6 e5f3 f6e4 d2d4 d6d5"
        fi
        if [ "$multiPv" -ge 4 ]; then
          echo "info depth 16 seldepth 22 multipv 4 score cp 120 nodes 1500 nps 150000 tbhits 0 time 10 pv d8e7 f1c4 b8c6 e1g1 d7d6 b1c3 c8e6"
        fi
        if [ "$multiPv" -ge 5 ]; then
          echo "info depth 16 seldepth 22 multipv 5 score cp -450 nodes 1500 nps 150000 tbhits 0 time 10 pv f7f6 f3e5 f6e5 d1h5 e8e7 h5e5 e7f7 f1c4"
        fi
        echo "bestmove b8c6"
      else
        echo "info depth 16 seldepth 20 multipv 1 score cp 0 nodes 1000 nps 100000 tbhits 0 time 10 pv e2e4"
        echo "bestmove e2e4"
      fi
      ;;
    stop)
      echo "bestmove (none)"
      ;;
    quit)
      exit 0
      ;;
  esac
done
