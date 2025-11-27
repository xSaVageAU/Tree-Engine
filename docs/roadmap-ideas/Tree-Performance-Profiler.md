# Feature Design Doc: Tree Performance Profiler

## 1. Executive Summary
The **Tree Performance Profiler** is a diagnostic tool designed to measure the computational cost ("weight") of generating a custom tree. 

By benchmarking the custom tree against a standardized "Vanilla Oak" baseline on the user's specific hardware, the tool provides a normalized **Performance Score**. This allows developers to identify "laggy" generation features before deploying to live servers.

## 2. Core Logic: "The Oak Standard" (Normalization)
To ensure scores are consistent across different CPUs, the system uses a relative cost model rather than raw operations per second.

1.  **Calibration (Startup):** The server benchmarks `minecraft:oak` for 1 second.
    *   *Example Result:* 2,500 trees/sec (Baseline).
2.  **Profiling (Runtime):** The server benchmarks the `custom_tree` for 1 second.
    *   *Example Result:* 500 trees/sec.
3.  **Scoring:** The Baseline is divided by the Custom Result.
    *   *Calculation:* `2500 / 500 = 5.0`
    *   *Meaning:* "This tree requires **5x more processing power** than a Vanilla Oak."

## 3. Backend Specification (Java)

### 3.1 Startup Calibration
*   **Trigger:** Executed once when `WebEditorServer` starts.
*   **Action:** Run the benchmark loop on the internal `minecraft:oak` feature.
*   **Storage:** Save result to `public static int BASELINE_OPS`.

### 3.2 Endpoint: `POST /api/benchmark`
*   **Input:** Raw ConfiguredFeature JSON.
*   **Execution Flow:**
    1.  Parse JSON into `ConfiguredFeature`.
    2.  Isolate execution to the main server thread (to ensure thread-safe RNG/WorldGen).
    3.  Execute `feature.generate()` in a tight loop for exactly 1,000ms (1 second).
    4.  Count successful placements.
*   **Output JSON:**
    ```json
    {
      "ops": 500,               // Total trees generated in test
      "baseline_ops": 2500,     // The cached Oak result
      "cost_multiplier": 5.0,   // (baseline / ops)
      "ms_per_tree": 2.0        // Average generation time in milliseconds
    }
    ```

## 4. Frontend Specification (UI)

### 4.1 UI Elements
*   **Location:** "Performance" section in the Sidebar.
*   **Controls:** Button `[Run Stress Test]` (Disabled while running).
*   **Display:**
    *   **The Score:** Large badge displaying the Grade (S-F).
    *   **The Context:** "This tree is **5.0x heavier** than a standard Oak."
    *   **The Raw Data:** "500 ops (Vanilla: 2500 ops)."

### 4.2 Grading Scale
The badge color and letter grade depend on the `cost_multiplier`:

| Grade | Cost Multiplier | Color  | Description | Usage Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| **S** | 0.0x - 1.5x | **Green** | **Excellent** | Safe for dense forests (Birch/Oak style). |
| **A** | 1.5x - 5.0x | **Blue**  | **Good** | Standard complexity. Safe for normal generation. |
| **B** | 5.0x - 15.0x | **Yellow** | **Heavy** | Use for large trees (Jungle/Spruce). Avoid extreme density. |
| **C** | 15.0x - 50.0x | **Orange** | **Very Heavy** | Use sparingly (Rare structures/Big trees). |
| **F** | 50.0x + | **Red** | **Lag Risk** | **WARNING:** Will cause TPS drops during chunk gen. |

## 5. Implementation Notes
*   **World Reset:** The `PhantomWorld` must be re-instantiated (or efficiently cleared) inside the loop to prevent memory leaks during the stress test.
*   **RNG:** Use a new `Random` instance (or reset the seed) for every iteration to ensure the generator isn't caching a "easy" seed.
*   **UX:** Show a loading spinner during the 1-second freeze so the user knows the app hasn't crashed.