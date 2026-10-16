# Matcher test set

Builds `data/download/src/test/resources/matcher/corpus-v1.json`, the labelled set that
`MatcherCorpusTest` scores `QobuzCandidateMatcher` against.

1. **Library sample**: pull a read-only copy of a debug install's database (with its `-wal`):
   `adb -s <device> exec-out run-as com.stash.app.debug cat databases/stash.db > stash.db` (same for
   `stash.db-wal`, `stash.db-shm`), then `node sample.mjs stash.db queries.json 1000` (seeded, ~60% hard titles).
2. **Candidates**: `node candidates.mjs queries.json candidates.json` (Deezer public search, top 5 for
   "artist title"; title_short as title, title_version as version, like Qobuz's title + version).
3. **Labels** (Claude API; key in `%USERPROFILE%\.stash\anthropic_api_key.txt`, never in the repo):
   `npm install`, then `node label.mjs trial queries.json candidates.json trial.json 10` to check a few,
   `node label.mjs batch queries.json candidates.json batch-id.txt` for all (Batch API, half price), and
   `node label.mjs collect batch-id.txt queries.json candidates.json labelled.json` once it has ended.
4. **Corpus**: `node build-corpus.mjs labelled.json ../../data/download/src/test/resources/matcher/corpus-v1.json`.

The labelling rules are the `SYSTEM` prompt in `label.mjs`. An edit of clearly different length
(single, radio, extended, album cut ~20 s / 10% apart) is a wrong version: the owner wants the cut
they know or nothing. v1 (2026-10-16): 777 songs, 2,095 pairs, under $1 of API use.
