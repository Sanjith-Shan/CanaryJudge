"""Generates the CanaryJudge Grafana dashboard (deploy/grafana/dashboards/canaryjudge.json).

One rollout lane at a time: what the canary and baseline do on latency, errors, CPU and heap, how much
traffic the controller gives the canary, how close the sequential judge is to failing it (1.0 = fail), and
how many users the canary has reached.
"""
import json
import pathlib

ITEMS = 'uri="/api/items/{id}"'
SEL = 'lane="$lane",role=~"baseline|canary"'


def ts(pid, title, exprs, unit, x, y, w=12, h=8, thresholds=None, legend=True, max_=None):
    targets = [{"refId": chr(65 + i), "expr": e, "legendFormat": l, "datasource": {"type": "prometheus", "uid": "cj-prom"}}
               for i, (e, l) in enumerate(exprs)]
    panel = {
        "id": pid, "type": "timeseries", "title": title,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "datasource": {"type": "prometheus", "uid": "cj-prom"},
        "targets": targets,
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {"lineWidth": 2, "fillOpacity": 0, "showPoints": "never", "spanNulls": True},
                "color": {"mode": "palette-classic"},
            },
            "overrides": [
                {"matcher": {"id": "byRegexp", "options": ".*canary.*"}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "#E8743B"}}]},
                {"matcher": {"id": "byRegexp", "options": ".*baseline.*"}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "#2A7AB0"}}]},
            ],
        },
        "options": {"legend": {"showLegend": legend, "displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }
    if max_ is not None:
        panel["fieldConfig"]["defaults"]["max"] = max_
    if thresholds:
        panel["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [{"color": "transparent", "value": None}, {"color": "red", "value": thresholds}]}
        panel["fieldConfig"]["defaults"]["custom"]["thresholdsStyle"] = {"mode": "dashed"}
    return panel


panels = [
    ts(1, "p99 latency, canary vs baseline",
       [(f'histogram_quantile(0.99, sum by (le, role) (rate(http_server_requests_seconds_bucket{{{SEL},{ITEMS}}}[30s])))', "{{role}}")],
       "s", 0, 0),
    ts(2, "Error rate", [(f'sum by (role) (rate(http_server_requests_seconds_count{{{SEL},{ITEMS},status=~"5.."}}[30s])) / sum by (role) (rate(http_server_requests_seconds_count{{{SEL},{ITEMS}}}[30s]))', "{{role}}")],
       "percentunit", 12, 0),
    ts(3, "CPU", [(f'avg by (role) (process_cpu_usage{{{SEL}}})', "{{role}}")], "percentunit", 0, 8, w=8),
    ts(4, "Heap after GC", [(f'max by (role) (cj_heap_after_gc_bytes{{{SEL}}})', "{{role}}")], "bytes", 8, 8, w=8),
    ts(5, "Requests per second", [(f'sum by (role) (rate(http_server_requests_seconds_count{{lane="$lane",{ITEMS}}}[30s]))', "{{role}}")], "reqps", 16, 8, w=8),
    ts(6, "Canary traffic share (rollout controller)", [('max(canaryjudge_rollout_canary_weight{lane="$lane"})', "canary weight")], "percentunit", 0, 16, w=8, max_=1),
    ts(7, "Sequential judge evidence (1.0 = canary fails)",
       [('max by (metric) (canaryjudge_evidence_share{canary="$canary"})', "{{metric}}")],
       "none", 8, 16, w=8, thresholds=1.0),
    ts(8, "Users who reached the canary", [('max(cj_splitter_users{lane="$lane",role="canary"}) / max(cj_splitter_users_all{lane="$lane"})', "share of active users")],
       "percentunit", 16, 16, w=8),
]

dashboard = {
    "uid": "canaryjudge",
    "title": "CanaryJudge rollout",
    "tags": ["canary"],
    "timezone": "browser",
    "schemaVersion": 39,
    "refresh": "10s",
    "time": {"from": "now-15m", "to": "now"},
    "templating": {"list": [
        {"name": "lane", "type": "custom", "query": "r1,r2", "current": {"text": "r1", "value": "r1"},
         "options": [{"text": "r1", "value": "r1", "selected": True}, {"text": "r2", "value": "r2", "selected": False}]},
        {"name": "canary", "type": "query", "datasource": {"type": "prometheus", "uid": "cj-prom"},
         "query": {"query": "label_values(canaryjudge_evidence_share, canary)", "refId": "canary"},
         "refresh": 2, "sort": 2, "label": "canary (sequential evidence)"},
    ]},
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "canaryjudge.json"
out.write_text(json.dumps(dashboard, indent=1))
print("wrote", out)
