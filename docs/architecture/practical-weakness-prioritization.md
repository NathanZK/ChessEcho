# Practical Weakness Prioritization Specification

## Purpose
Specifies the mathematical formulas, confidence-interval derivations, and configuration parameters used by `PracticalEvidenceService` to compute `recommendationPriority`.

---

## 1. Overview & Separation of Concerns

ChessProbe separates objective engine evaluation loss from empirical practical performance:
- **Objective Priority (`priority`)**: Determined by historical mistake count, average centipawn evaluation loss, and recency decay. It is never overwritten by practical ranking.
- **Recommendation Priority (`recommendationPriority`)**: Confidence-gated adjustment applied to `priority` based on whether the player statistically underperforms or overperforms an empirical baseline across historical games reaching the position.

When practical ranking is disabled via `CHESS_WEAKNESS_PRACTICAL_RANKING_ENABLED=false`, `recommendationPriority` equals `priority`.

---

## 2. Statistical Derivations & Formulas

### 2.1 Eligible Game Filtering & Empirical Score Rate
For a given position and player account:
1. Only completed games with a known decisive or drawn result are eligible.
2. If the position occurred multiple times within the same game, the game is counted exactly once (deduplicated by game ID).
3. If distinct eligible games $N < \text{sampleFloor}$ (default 5), evidence is classified as **INCONCLUSIVE**, and priority is unchanged.

The empirical score rate $\hat{p}$ is defined as:
$$\hat{p} = \frac{\text{wins} + 0.5 \times \text{draws}}{N}$$

### 2.2 Conservative Wilson Score Confidence Interval
To account for uncertainty in small samples, `PracticalEvidenceService` computes a conservative Bernoulli Wilson score interval $(p^-, p^+)$ at confidence parameter $z$:

$$p^\pm = \frac{\hat{p} + \frac{z^2}{2N} \pm z \sqrt{\frac{\hat{p}(1 - \hat{p})}{N} + \frac{z^2}{4N^2}}}{1 + \frac{z^2}{N}}$$

Under the default policy `BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE`:
- Draws contribute 0.5 points to the numerator.
- $N$ is the count of distinct eligible games.

### 2.3 Evidence Classification & Comparator Policy
With the default comparator $p_0 = 0.50$ (`FIXED_SCORE_RATE`) and meaningful difference threshold $\delta = 0.10$:

- **POOR**: The upper confidence bound is below the lower threshold:
  $$p^+ \le p_0 - \delta \quad (p^+ \le 0.40)$$
- **SUCCESSFUL**: The lower confidence bound is above the upper threshold:
  $$p^- \ge p_0 + \delta \quad (p^- \ge 0.60)$$
- **INCONCLUSIVE**: Neither condition holds ($p^+ > 0.40$ and $p^- < 0.60$).

### 2.4 Recommendation Priority Multiplier
When evidence is conclusive, `recommendationPriority` is adjusted by a bounded multiplier:
$$\text{recommendationPriority} = \begin{cases}
\text{priority} \times (1 + \Delta) & \text{if POOR} \\
\text{priority} \times (1 - \Delta) & \text{if SUCCESSFUL} \\
\text{priority} & \text{if INCONCLUSIVE}
\end{cases}$$

With default maximum adjustment $\Delta = 0.25$:
- **POOR** multiplies priority by **1.25** (+25%).
- **SUCCESSFUL** multiplies priority by **0.75** (-25%).

---

## 3. Configuration Parameters

| Environment Variable | YAML Property Key | Default | Purpose |
|---|---|---:|---|
| `CHESS_WEAKNESS_PRACTICAL_RANKING_ENABLED` | `chess.weakness.practical.ranking-enabled` | `true` | Master toggle for practical priority adjustment. |
| `CHESS_WEAKNESS_PRACTICAL_SAMPLE_FLOOR` | `chess.weakness.practical.sample-floor` | `5` | Minimum distinct eligible games required for classification. |
| `CHESS_WEAKNESS_PRACTICAL_COMPARATOR_METHOD` | `chess.weakness.practical.comparator-method` | `FIXED_SCORE_RATE` | Method for baseline score rate (`FIXED_SCORE_RATE`). |
| `CHESS_WEAKNESS_PRACTICAL_COMPARATOR_SCORE_RATE` | `chess.weakness.practical.comparator-score-rate` | `0.50` | Baseline score rate comparator $p_0$. |
| `CHESS_WEAKNESS_PRACTICAL_CONFIDENCE_METHOD` | `chess.weakness.practical.confidence-method` | `BERNOULLI_WILSON...` | Confidence interval calculation algorithm. |
| `CHESS_WEAKNESS_PRACTICAL_WILSON_Z_SCORE` | `chess.weakness.practical.wilson-z-score` | `1.0` | Z-score controlling confidence interval width (1.0 ≈ 68.3% coverage). |
| `CHESS_WEAKNESS_PRACTICAL_MEANINGFUL_DIFFERENCE` | `chess.weakness.practical.meaningful-difference` | `0.10` | Dead-zone buffer $\delta$ around comparator. |
| `CHESS_WEAKNESS_PRACTICAL_MAX_PRIORITY_ADJUSTMENT` | `chess.weakness.practical.max-priority-adjustment` | `0.25` | Maximum proportional priority multiplier adjustment $\Delta$. |
| `CHESS_WEAKNESS_PRACTICAL_OBSERVATION_WINDOW_DAYS` | `chess.weakness.practical.observation-window-days` | `null` | Optional rolling window in days (null = all history). |
| `CHESS_WEAKNESS_PRACTICAL_POLICY_VERSION` | `chess.weakness.practical.policy-version` | `practical-score-rate-wilson-v1` | Policy version identifier tag. |

---

## 4. Rollback and Operational Safeguards
1. **Dynamic Disabling**: Setting `CHESS_WEAKNESS_PRACTICAL_RANKING_ENABLED=false` immediately disables practical score calculation without database modification or data loss.
2. **Fail-Closed Validation**: Invalid parameter values (e.g. sample floor < 1, z-score <= 0) throw `IllegalArgumentException` during Spring bean initialization.
