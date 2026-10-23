#!/usr/bin/env python3
"""Merges Gradle's JUnit XML files into one results.xml and writes summary.md.

    scripts/junit-report.py <run directory> <xml file or directory>...

Tests are classified by their name: s* security, f* feature, z* check, the
rest unit. A "skipped" note for the UI suite is read from <run>/ui-skipped.txt.
"""

import datetime
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET


def kind(classname, name):
    if "e2e" in classname:
        if name.startswith("s"):
            return "e2e-security"
        if name.startswith("f"):
            return "e2e"
        return "check"
    if classname.endswith("Test") and "androidTest" in classname.lower():
        return "UI"
    return "unit"


def main():
    run = pathlib.Path(sys.argv[1])
    files = []
    for arg in sys.argv[2:]:
        p = pathlib.Path(arg)
        files += sorted(p.glob("*.xml")) if p.is_dir() else [p]
    suites = ET.Element("testsuites")
    rows = []
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for f in files:
        root = ET.parse(f).getroot()
        for suite in [root] if root.tag == "testsuite" else root.iter("testsuite"):
            suites.append(suite)
            for case in suite.iter("testcase"):
                name, cls = case.get("name", ""), case.get("classname", "")
                if case.find("failure") is not None:
                    result = "failed"
                elif case.find("error") is not None:
                    result = "error"
                elif case.find("skipped") is not None:
                    result = "skipped"
                else:
                    result = "passed"
                totals["tests"] += 1
                if result == "failed":
                    totals["failures"] += 1
                if result == "error":
                    totals["errors"] += 1
                if result == "skipped":
                    totals["skipped"] += 1
                rows.append((kind(cls, name), cls.rsplit(".", 1)[-1], name, result, float(case.get("time") or 0)))
    for k, v in totals.items():
        suites.set(k, str(v))
    ET.ElementTree(suites).write(run / "results.xml", encoding="unicode", xml_declaration=True)

    bad = totals["failures"] + totals["errors"]
    verdict = "passed" if bad == 0 else "failed"
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    order = {"e2e-security": 0, "e2e": 1, "check": 2, "unit": 3, "UI": 4}
    rows.sort(key=lambda r: (order.get(r[0], 9), r[1], r[2]))
    out = [f"# Run {run.name}", "",
           f"**{verdict}**: {totals['tests']} tests, {bad} failed, {totals['skipped']} skipped · commit `{commit}` · {now}", ""]
    env = run / "environment.txt"
    if env.exists():
        out += ["## Environment", "", "```", env.read_text().strip(), "```", ""]
    ui = run / "ui-skipped.txt"
    if ui.exists():
        out += [f"UI tests: {ui.read_text().strip()}", ""]
    check = run / "secrets-check.txt"
    if check.exists():
        out += [f"Secrets: {check.read_text().strip()}", ""]
    out += ["## Results", "", "| Kind | Class | Test | Result | s |", "|---|---|---|---|---|"]
    for k, cls, name, result, t in rows:
        mark = {"passed": "ok", "skipped": "skipped"}.get(result, f"**{result}**")
        out.append(f"| {k} | {cls} | {name} | {mark} | {t:.1f} |")
    (run / "summary.md").write_text("\n".join(out) + "\n")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
