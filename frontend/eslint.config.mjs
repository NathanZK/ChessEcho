import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    ".stryker-tmp/**",
    "reports/**",
  ]),

  // Group 1: General production rules across all src files
  {
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "max-depth": ["error", 4],
      "complexity": ["error", { max: 15 }],
      "max-lines": ["error", { max: 500, skipBlankLines: true, skipComments: true }],
      "max-lines-per-function": [
        "error",
        { max: 60, skipBlankLines: true, skipComments: true, IIFEs: true },
      ],
      "no-mixed-operators": "error",
      "@typescript-eslint/no-non-null-assertion": "error",
      "no-empty": ["error", { allowEmptyCatch: false }],
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          args: "all",
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrors: "all",
          caughtErrorsIgnorePattern: "^(_|expected|ignored)$",
        },
      ],
    },
  },

  // Group 2: Component function size accommodation for React components & App Router views
  {
    files: ["src/components/**/*.{ts,tsx}", "src/app/**/*.{ts,tsx}"],
    rules: {
      "max-lines-per-function": [
        "error",
        { max: 120, skipBlankLines: true, skipComments: true, IIFEs: true },
      ],
    },
  },

  // Group 3: Test suite accommodations
  {
    files: ["src/__tests__/**/*.{ts,tsx}", "**/*.test.{ts,tsx}"],
    rules: {
      "max-depth": "off",
      "complexity": "off",
      "max-lines": "off",
      "max-lines-per-function": "off",
      "no-mixed-operators": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },

  // Group 4: Pre-existing legacy debt exemptions (tracked for remediation)
  // Monolithic tab coordinator: decomposed in #678
  {
    files: ["src/app/page.tsx"],
    rules: {
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Extracted practice puzzles view: tracked in #678
  {
    files: ["src/components/PracticePuzzlesView.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },
  // Extracted account session hook: tracked in #678
  {
    files: ["src/hooks/useAccountSession.ts"],
    rules: {
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Extracted line exploration hook: tracked in #678
  {
    files: ["src/hooks/useLineExploration.ts"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
      "@typescript-eslint/no-unused-vars": "off",
    },
  },
  // Puzzle feedback & line exploration panel: tracked in #685
  {
    files: ["src/components/PuzzleFeedbackPanel.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Weaknesses listing & categorization table: tracked in #685
  {
    files: ["src/components/WeaknessesList.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Interactive chess board area: tracked in #685
  {
    files: ["src/components/ChessBoardArea.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
      "max-depth": "off",
    },
  },
  // Game import view & account connection flow: tracked in #685
  {
    files: ["src/components/ImportGamesView.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },
  // Header and user connection status banner: tracked in #685
  {
    files: ["src/components/Header.tsx"],
    rules: {
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Long decisions analysis view: tracked in #685
  {
    files: ["src/components/LongDecisionsView.tsx"],
    rules: {
      "max-lines": "off",
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Position progress & review view: tracked in #685
  {
    files: ["src/components/PositionProgressView.tsx"],
    rules: {
      "max-lines-per-function": "off",
      "complexity": "off",
      "no-mixed-operators": "off",
    },
  },
  // Similar positions view: tracked in #685
  {
    files: ["src/components/SimilarPositionsView.tsx"],
    rules: {
      "max-lines-per-function": "off",
      "complexity": "off",
    },
  },
  // Explore position modal: tracked in #685
  {
    files: ["src/components/ExplorePositionModal.tsx"],
    rules: {
      "max-lines-per-function": "off",
    },
  },
  // Month picker calendar modal: tracked in #685
  {
    files: ["src/components/MonthPicker.tsx"],
    rules: {
      "max-lines-per-function": "off",
    },
  },
  // Notation exchange terminal UI: tracked in #685
  {
    files: ["src/components/NotationExchangeUI.tsx"],
    rules: {
      "complexity": "off",
    },
  },
  // Evaluation bar visual component: tracked in #685
  {
    files: ["src/components/EvalBar.tsx"],
    rules: {
      "no-mixed-operators": "off",
    },
  },
  // REST API client module: tracked in #685
  {
    files: ["src/services/api.ts"],
    rules: {
      "max-lines": "off",
      "complexity": "off",
    },
  },
  // Blindfold session custom hook: tracked in #685
  {
    files: ["src/hooks/useBlindfoldSession.ts"],
    rules: {
      "max-lines-per-function": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },
  // Puzzle attempt count custom hook: tracked in #685
  {
    files: ["src/hooks/usePuzzleAttemptCount.ts"],
    rules: {
      "max-lines-per-function": "off",
    },
  },
  // Puzzle continuation custom hook: relocated in #672, tracked in #685
  {
    files: ["src/hooks/usePuzzleContinuation.ts"],
    rules: {
      "max-lines-per-function": "off",
    },
  },
  // PGN parsing utility: tracked in #685
  {
    files: ["src/utils/positionImport.ts"],
    rules: {
      "complexity": "off",
    },
  },
]);

export default eslintConfig;
