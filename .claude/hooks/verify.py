"""Stop hook: format + test the project when Kotlin/Gradle sources changed since the last green run.

Exit 0 lets Claude finish; exit 2 blocks it and feeds stderr back so it fixes the failure first.
"""

import glob
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, ".claude", ".state", "last-green")
SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "legacy", "tools", "node_modules", ".claude"}
WATCHED = (".kt", ".kts", ".toml", ".properties")


def fingerprint() -> str:
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.endswith(WATCHED):
                path = os.path.join(dirpath, name)
                h.update(os.path.relpath(path, ROOT).encode())
                with open(path, "rb") as f:
                    h.update(f.read())
    return h.hexdigest()


def java_env() -> dict:
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        jdks = sorted(glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "jdk-21*")))
        if jdks:
            env["JAVA_HOME"] = jdks[-1]
    return env


def failed_tests() -> list:
    out = []
    for path in glob.glob(os.path.join(ROOT, "**", "build", "test-results", "**", "*.xml"), recursive=True):
        try:
            for case in ET.parse(path).getroot().iter("testcase"):
                for failure in case.findall("failure"):
                    msg = (failure.get("message") or "").splitlines()[0][:300] if failure.get("message") else ""
                    out.append(f"{case.get('classname')} > {case.get('name')}: {msg}")
        except ET.ParseError:
            pass
    return out


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        payload = {}
    # Already blocked once this turn: don't loop forever, just report.
    retrying = payload.get("stop_hook_active", False)

    if not os.path.exists(os.path.join(ROOT, "settings.gradle.kts")):
        return 0
    current = fingerprint()
    if os.path.exists(STATE) and open(STATE).read().strip() == current:
        return 0

    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    result = subprocess.run(
        [gradlew, "spotlessApply", "check", "--console=plain", "-q"],
        cwd=ROOT,
        env=java_env(),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode == 0:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        with open(STATE, "w") as f:
            f.write(fingerprint())  # spotlessApply may have reformatted files
        return 0

    lines = [line for line in (result.stdout + result.stderr).splitlines() if line.strip()]
    report = ["Verification failed (gradlew spotlessApply check). Fix before finishing:"]
    tests = failed_tests()
    if tests:
        report.append("Failed tests:")
        report += [f"  - {t}" for t in tests[:20]]
    report.append("Gradle output (tail):")
    report += lines[-60:]
    print("\n".join(report), file=sys.stderr)
    return 0 if retrying else 2


if __name__ == "__main__":
    sys.exit(main())
