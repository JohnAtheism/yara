# AGENTS.md

Instructions for AI agents contributing to this repository.

This is a **defensive malware-analysis** repository. It contains YARA rules,
static analysis notes, and reconstructed source for research and detection. It
must not become a malware distribution point.

## Non-negotiable rules

1. **Never commit live samples or binaries.** No `.jar`, `.exe`, `.dll`, `.so`,
   `.class`, `.zip`, `.apk`, `.dex`, or similar files. Hashes and behavior
   descriptions are enough. The root `.gitignore` enforces this.
2. **Never execute a sample.** Do not run malware, do not contact C2
   infrastructure, and do not detonate payloads. Analysis is static only unless
   the repository owner explicitly sets up an isolated lab.
3. **Do not add working build pipelines for malware.** Reconstructed source is
   for comparison and readability. Do not add scripts that assemble a runnable
   implant.
4. **Preserve evidence.** When quoting a C2 response or log, keep the relevant
   content intact. Remove only local paths, usernames, tokens, and other
   machine-specific data.
5. **No secrets.** Do not commit credentials, cookies, tokens, private keys, or
   victim data. Redact them before adding evidence.
6. **Ask before destructive git operations.** Do not rewrite history,
   force-push, delete analyses, or modify unrelated families without explicit
   approval.

## Repository layout

```
yara/
├── Papers/                     # long-form reports and writeups
│   └── <family>.md
├── Other/
│   └── <Family>/
│       ├── README.md           # analysis, IOCs, behavior, limitations
│       ├── src/                # optional reconstructed source
│       └── <evidence files>    # sanitized manifests, scripts, configs
├── yara/
│   └── <family>.yar            # detection rules
├── README.md
├── SECURITY.md
├── AGENTS.md
└── .gitignore
```

Use the existing family name casing consistently:

- Directory: `Other/FunRat/`, `Other/SilentNet/`, `Other/FyreRAT/`
- Report: `Papers/FunRat.md`
- Rule file: lowercase `yara/funrat.yar`, `yara/fyrerat.yar`

## Adding a new analysis

1. Pick a stable family name. Prefer the name used by the operator or the
   security community; do not invent a new name if one exists.
2. Create `Other/<Family>/README.md` using the template below.
3. Put reconstructed Java/JS/config files under `Other/<Family>/src/` or next
   to the README when they are evidence rather than source.
4. Add `yara/<family>.yar` with:
   - an exact-hash rule for analyzed samples,
   - a raw-archive rule using ZIP/JAR structural markers,
   - extracted-class/file rules,
   - a low-confidence IOC rule for memory or unpacked scans,
   - a `scope_note` when a rule also matches reports or documentation.
5. Test the rules against the real samples and against the full extracted tree.
   Only intended malicious classes should match.
6. Commit the README, source/evidence, and YARA rule together. Never stage
   binaries.

## README template

Copy this structure for `Other/<Family>/README.md`:

```markdown
# <Family> — <short description>

One-paragraph summary: what it is, how it is delivered, what it does, and
whether the sample was executed (normally: "static analysis only").

## Samples

| Property | Value |
|---|---|
| File | `<name>` |
| SHA-256 | `<hash>` |
| Size | `<bytes>` |
| Format | JAR / EXE / JS / etc. |
| Classes / entries | `<count>` |
| Build metadata | timestamps, manifest, compiler, signer |

## Entries / classes

| Role | Entry | SHA-256 |
|---|---|---|
| Loader | `path/to/Class.class` | `<hash>` |
| Payload | `path/to/Other.class` | `<hash>` |

Short note on which entries are malicious and which are benign dependencies.

## Execution chain

```text
entrypoint
  └─ behavior
       ├─ network
       └─ filesystem
```

## Stage 1 — <name>

What triggers it, what it downloads, where it writes, how it persists. Include
sanitized excerpts only when they clarify behavior.

## Stage 2 — <name>

Protocol, commands, encryption, persistence, and operator capabilities.

## Indicators

```text
domain / URL / path / file
```

Host behavior:

- process relationships
- filesystem changes
- network patterns
- registry / service / scheduled-task changes

## Detection

Link to `../../yara/<family>.yar`.

Explain which rules cover raw archives, extracted classes, network strings, and
exact hashes. Include a safe test command.

## Analysis files

| File | Original class | Purpose |
|---|---|---|
| `src/Foo.java` | `a.b.c` | reconstructed loader |
| `manifest.txt` | — | sanitized C2 response |

## Verification

- [ ] YARA rules compile.
- [ ] Rules match the analyzed samples.
- [ ] Full-tree scans only match intended entries.
- [ ] Hashes and sizes are correct.
- [ ] No binaries or secrets are staged.
- [ ] Local paths and usernames are removed.

## Limitations

- Static analysis only; C2 not contacted.
- Inferred behavior vs. observed behavior.
- Version/metadata uncertainty.
- Infrastructure may rotate.
```

## YARA conventions

Use lowercase rule names with family and stage/role:

```yara
rule family_stage1_jar { ... }
rule family_stage1_class { ... }
rule family_stage2_rat_class { ... }
rule family_c2_indicators { ... }
```

Metadata fields used in this repo:

```yara
meta:
    family      = "Family"
    stage       = "stage1-jar"
    confidence  = "high" | "medium" | "low"
    description = "What this matches"
    reference   = "path or URL"
    scope_note  = "Known false-positive scope, if any"
```

Guidelines:

- Use `uint16(0) == 0x4B50` for ZIP/JAR containers and
  `uint32be(0) == 0xCAFEBABE` for Java class files.
- Class strings are compressed inside JARs. Add a separate raw-JAR rule based
  on entry names, and a class rule for extracted files.
- Exact-hash rules must include `filesize` to keep `hash.sha256()` cheap.
- Do not write a rule that matches every `workers.dev`, `github.com`, or
  `gitlab.com` URL. Pin the specific tenant/path.
- If a rule matches this repository's own Markdown, add a `scope_note` and tell
  users to exclude reports from production scans.

## Evidence handling

- Quote JSON/HTTP responses exactly; use a comment header for the endpoint.
- Remove shell prompts and absolute paths from captured terminal output.
- Do not include full `id`/`pass` values, tokens, or victim identifiers. Describe
  the field instead.
- Name evidence files descriptively: `manifest.txt`, `script.js`, `config.json`,
  `console.log`.
- If a file is itself executable content (e.g. a JavaScript logger), keep it
  under the family directory, clearly mark it as captured evidence, and never
  wire it into a build.

## Verification checklist

Before committing:

```bash
# YARA syntax
yara -w yara/<family>.yar /dev/null

# Raw samples
yara yara/<family>.yar /path/to/sample.jar

# Extracted tree (only intended entries should match)
unzip -q /path/to/sample.jar -d extracted
yara -r yara/<family>.yar extracted

# Ensure no binaries are staged
git diff --cached --name-only | grep -Ei '\.(exe|jar|class|zip|dll|so|dylib|bin|apk|dex)$' && echo "STOP"
```

Also check:

- `git status` contains only intended files.
- No `/home/...`, `/tmp/...`, `C:\\...`, or username leaks in added text.
- Commit author/remote are correct for the current GitHub account.

## Style

- Match the existing repository tone: direct, technical, evidence-first.
- Prefer tables for samples, hashes, classes, and IOCs.
- State what is observed versus inferred.
- Avoid marketing language and avoid giving the operator credit.
- Keep long writeups in `Papers/`; keep per-family behavior and IOCs in
  `Other/<Family>/README.md`.

## Out of scope

- Running samples or contacting C2 infrastructure.
- Committing malware binaries or full working builds.
- Victim data, credentials, or personal information.
- Attacking, scanning, or accessing third-party systems.
