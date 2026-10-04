"""Golden values from scipy for the Java statistics tests.

Run once (python bench/golden/make_golden.py); the output is committed under
core/src/test/resources/golden/ so the tests do not need Python. scipy is an independent
implementation of the normal distribution and the Mann-Whitney test.
"""
import json
import pathlib

import numpy as np
import scipy
from scipy import stats

OUT = pathlib.Path(__file__).resolve().parents[2] / "core/src/test/resources/golden"
OUT.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(20261004)

# Normal distribution
xs = [float(x) for x in np.concatenate([np.linspace(-9, 9, 73), [-37.5, -20, -12.3, 0.001, 1e-8, 2.999, 3.0, 3.001, 15.5, 30]])]
ps = [float(p) for p in np.concatenate([[1e-300, 1e-100, 1e-20, 1e-10, 1e-5, 0.001, 0.01, 0.02425, 0.025],
                                        np.linspace(0.03, 0.97, 48), [0.975, 0.99, 0.999, 1 - 1e-5, 1 - 1e-10]])]
normal = {
    "scipy": scipy.__version__,
    "cdf": [[x, float(stats.norm.cdf(x))] for x in xs],
    "sf": [[x, float(stats.norm.sf(x))] for x in xs],
    "ppf": [[p, float(stats.norm.ppf(p))] for p in ps],
}
(OUT / "normal.json").write_text(json.dumps(normal, indent=1))

# Mann-Whitney
cases = []
for i in range(300):
    n = int(rng.integers(2, 45))
    m = int(rng.integers(2, 45))
    kind = i % 3
    shift = float(rng.choice([0.0, 0.2, 0.5, 1.0]))
    x = rng.lognormal(0, 0.6, n) * (1 + shift)
    y = rng.lognormal(0, 0.6, m)
    if kind == 1:  # heavy ties
        x = np.round(x * 2) / 2
        y = np.round(y * 2) / 2
    elif kind == 2:  # moderate ties
        x = np.round(x, 1)
        y = np.round(y, 1)
    x = [float(v) for v in x]
    y = [float(v) for v in y]
    if len(set(x + y)) == 1:
        continue
    case = {"x": x, "y": y}
    for alt in ["two-sided", "greater", "less"]:
        r = stats.mannwhitneyu(x, y, alternative=alt, method="asymptotic", use_continuity=True)
        case["asymptotic_" + alt] = [float(r.statistic), float(r.pvalue)]
        tied = len(set(x + y)) < len(x) + len(y)
        if not tied and n * m <= 900:
            r = stats.mannwhitneyu(x, y, alternative=alt, method="exact")
            case["exact_" + alt] = [float(r.statistic), float(r.pvalue)]
    d = np.subtract.outer(np.array(x), np.array(y)).ravel()
    case["hodges_lehmann"] = float(np.median(d))
    cases.append(case)
(OUT / "mannwhitney.json").write_text(json.dumps({"scipy": scipy.__version__, "cases": cases}))
print(f"wrote {len(normal['cdf'])} normal points and {len(cases)} Mann-Whitney cases to {OUT}")
