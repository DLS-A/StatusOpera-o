"""Static source checks only: this does not compile/run Android or establish TLS."""
import ast
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from tree_sitter import Language, Parser
import tree_sitter_kotlin

root = Path(__file__).resolve().parents[1]
counts = {"python_ast": 0, "kotlin_syntax": 0, "xml": 0, "json": 0}
parser = Parser(Language(tree_sitter_kotlin.language()))
for file in sorted(root.rglob("*")):
    if not file.is_file() or any(part in {"build", ".gradle", "__pycache__", ".git"} for part in file.parts):
        continue
    if file == root / "docs/static-checks.json":
        continue  # Generated output may be open/truncated during redirection.
    if file.suffix == ".py":
        ast.parse(file.read_text(encoding="utf-8"), filename=str(file.relative_to(root)))
        counts["python_ast"] += 1
    elif file.suffix in {".kt", ".kts"}:
        if parser.parse(file.read_bytes()).root_node.has_error:
            raise RuntimeError("Kotlin syntax parser error: " + str(file.relative_to(root)))
        counts["kotlin_syntax"] += 1
    elif file.suffix == ".xml":
        ET.parse(file)
        counts["xml"] += 1
    elif file.suffix == ".json":
        json.loads(file.read_text(encoding="utf-8"))
        counts["json"] += 1

subprocess.run(["node", "--check", str(root / "backend/app/oauth_consent.js")], check=True)
counts["javascript_syntax"] = 1
print(json.dumps({"checks": counts, "status": "passed", "android_compilation": "not_run",
                  "android_junit": "not_run", "device_runtime": "not_run", "real_tls": "not_tested"}, indent=2))