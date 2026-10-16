// labelled results (label.mjs output) -> the committed test resource matcher/corpus-v1.json
import { readFileSync, writeFileSync } from "node:fs";
const [inPath, out] = process.argv.slice(2);
const results = JSON.parse(readFileSync(inPath, "utf8"));
const items = results.filter((r) => r.labels).map((r) => ({
  qid: r.query.qid,
  source: r.query.source,
  query: { artist: r.query.artist, title: r.query.title, album: r.query.album, durationMs: r.query.durationMs, isrc: r.query.isrc },
  candidates: r.candidates.map((c, i) => ({
    cid: c.cid, title: c.title, version: c.version, artist: c.artist, album: c.album, durationSec: c.durationSec,
    verdict: r.labels[i].verdict, reason: r.labels[i].reason,
  })),
}));
const corpus = {
  version: 1,
  policy: "Labelled 2026-10 by claude-opus-5-5 (effort high). match = same song, artist and performance (remasters, " +
    "same-recording mixes, radio/clean edits, compilations count); wrong_version = live/remix/acoustic/instrumental/" +
    "sped-up/re-recorded/extended etc. the library song is not; wrong_song = other song, cover, or unrelated artist. " +
    "Queries: owner's library (Spotify + YouTube Music rows, raw title/artist as the app sends them). Candidates: Deezer " +
    "public search top 5 for 'artist title' (title_short as title, title_version as version), a stand-in for Qobuz/Tidal results.",
  items,
};
writeFileSync(out, JSON.stringify(corpus, null, 1) + "\n");
const v = items.flatMap((i) => i.candidates.map((c) => c.verdict));
console.log(`${items.length} songs, ${v.length} pairs:`, Object.fromEntries(["match", "wrong_version", "wrong_song", "unsure"].map((k) => [k, v.filter((x) => x === k).length])), `; ${results.length - items.length} dropped (errors)`);
