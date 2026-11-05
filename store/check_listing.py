"""Check the Play listing text in listing.md against Play's length limits."""
import re, pathlib
s = pathlib.Path(__file__).with_name("listing.md").read_text()
blocks = re.findall(r"```\n(.*?)\n```", s, re.S)
for (name, limit), text in zip([("App name", 30), ("Short description", 80), ("Full description", 4000)], blocks):
    print(f"{name:18} {len(text):5} / {limit}  {'OK' if len(text) <= limit else 'TOO LONG'}")
