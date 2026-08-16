python3 - <<'PY'
from pathlib import Path

p = Path("source/net/yacy/crawler/HostBalancer.java")
s = p.read_text()

old = """                if (this.roundRobinHostHashes.size() == 1) {
                    rhh = this.roundRobinHostHashes.iterator().next();
                    rhq = this.queues.get(rhh);
                }
"""

new = """                if (this.roundRobinHostHashes.size() == 1) {
                    final Iterator<String> iterator = this.roundRobinHostHashes.iterator();
                    if (iterator.hasNext()) {
                        rhh = iterator.next();
                        rhq = this.queues.get(rhh);
                    }
                }
"""

if old not in s:
    raise SystemExit("Target block not found; no changes made.")

p.write_text(s.replace(old, new, 1))
print("Patched HostBalancer.java")
PY
