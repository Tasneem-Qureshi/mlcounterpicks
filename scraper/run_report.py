#!/usr/bin/env python3
"""Builds the JSON body of the per-run report comment (see the 'Email run report' workflow step).
Env: STATUS (job.status), RUN_URL, TRIGGER.  Reads scraper/out/summary.md when present."""
import datetime, json, os

ok = os.environ.get("STATUS") == "success"
when = datetime.datetime.now(datetime.timezone.utc).strftime("%a %d %b %Y, %H:%M UTC")
if ok:
    head = f"✅ **Refresh succeeded** · {when}\n\nPhones pick this up on their next daily check."
else:
    head = (f"❌ **Refresh failed** · {when}\n\nNothing was published; phones keep the last good data. "
            "The next scheduled run (Monday or Thursday) retries automatically.")
try:
    summary = open("scraper/out/summary.md").read().strip()
except OSError:
    summary = ""
body = head + (f"\n\n{summary}" if summary and ok else "")
body += f"\n\nTrigger: {os.environ.get('TRIGGER', '?')} · [run log]({os.environ.get('RUN_URL', '')})"
print(json.dumps({"body": body}))
