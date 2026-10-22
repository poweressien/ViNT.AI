import json, sqlite3, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ai-training"))
from pidgin_pipeline import ingest, quality, dedupe, split, stats, export, slangdb, cli
from pidgin_pipeline.schema import ExpressionRecord, normalize

SEED = Path(__file__).resolve().parents[1] / "datasets/sample/seed_expressions.jsonl"


def R(expr, meaning="", **kw):
    kw.setdefault("source", "t"); kw.setdefault("license", "t"); kw.setdefault("provenance", "t")
    return ExpressionRecord(expression=expr, meaning_in_pidgin=meaning, **kw).finalize()


def test_seed_loads_and_is_labeled_synthetic_unverified():
    recs = list(ingest.read_jsonl(SEED))
    assert len(recs) == 6 and all(isinstance(r, ExpressionRecord) for r in recs)
    assert all(r.synthetic and not r.human_verified for r in recs)


def test_malformed_lines_reported(tmp_path):
    p = tmp_path / "x.jsonl"; p.write_text('{"expression":"a"}\nnot json\n[1,2]\n')
    out = list(ingest.read_jsonl(p))
    assert sum(isinstance(o, dict) for o in out) == 2


def test_csv_and_txt(tmp_path):
    c = tmp_path / "a.csv"; c.write_text("expression,meaning_in_pidgin,alternative_spellings,confidence\nAbeg,please,abegi|abeg o,0.9\n")
    r = list(ingest.read_csv(c))[0]
    assert r.alternative_spellings == ["abegi", "abeg o"] and r.confidence == 0.9
    t = tmp_path / "a.txt"; t.write_text("Wetin dey sup?\n\nNo wahala\n")
    rs = list(ingest.read_txt(t, source="s", license="l"))
    assert len(rs) == 2 and rs[0].confidence == 0.0 and not rs[0].human_verified


def test_normalize_keeps_spelling_variants_separate():
    assert normalize("  Wetin   dey sup?! ") == "wetin dey sup"
    assert normalize("wahala") != normalize("wahalla")


def test_exact_dedupe_merges_variants():
    a = R("No wahala", "no problem", confidence=0.5, alternative_spellings=["no wahalla"])
    b = R("no wahala!", "no problem o", confidence=0.9, human_verified=True, usage_examples=["x"])
    out = dedupe.dedupe_exact([a, b])
    assert len(out) == 1 and out[0].human_verified and "no wahalla" in out[0].alternative_spellings


def test_near_duplicates():
    texts = ["Abeg make you help me carry this heavy bag come house", "Abeg make you help me carry this heavy bag come house o",
             "Wetin dey happen for market today"]
    pairs = dedupe.near_duplicates(texts, threshold=0.8)
    assert [(i, j) for i, j, _ in pairs] == [(0, 1)]


def test_validation_flags():
    assert "missing_license" in quality.validate_record(ExpressionRecord(expression="a", source="s", provenance="p").finalize())
    assert "confidence_out_of_range" in quality.validate_record(R("a", confidence=1.5))
    assert "empty_expression" in quality.validate_record(R("  "))


def test_english_only_detection():
    assert quality.looks_english_only("The committee has been reviewing this proposal because of the budget")
    assert not quality.looks_english_only("Abeg make you wait small, wahala dey")
    assert not quality.looks_english_only("How are you")  # too short to judge


def test_conflict_detection():
    a = R("Chop", "to eat food"); b = R("chop", "to receive money corruptly from government")
    assert len(quality.find_conflicts([a, b])) == 1
    assert quality.find_conflicts([R("x", "to eat food"), R("x", "to eat food now")]) == []


def test_split_has_no_leakage_and_is_deterministic():
    recs = [R(f"expr number {i}", alternative_spellings=[f"expr no {i}"]) for i in range(500)]
    s1 = split.split_records(recs); s2 = split.split_records(recs)
    assert {k: [r.id for r in v] for k, v in s1.items()} == {k: [r.id for r in v] for k, v in s2.items()}
    assert split.check_leakage(s1) == [] and sum(map(len, s1.values())) == 500
    leaky = {"train": [R("abeg")], "test": [R("Abeg!")]}
    assert split.check_leakage(leaky) == ["abeg"]


def test_stats_are_separate_numbers():
    recs = [R("No wahala", "no problem", usage_examples=["No wahala o"])]
    s = stats.corpus_stats(recs, conversations=[{"messages": [1, 2, 3]}])
    assert s["unique_expressions"] == 1 and s["sentence_examples"] == 1 and s["conversation_turns"] == 3
    assert "total" not in s and "dataset_size" not in s


def test_export_excludes_unverified_by_default():
    recs = list(ingest.read_jsonl(SEED))
    assert list(export.to_instruction_examples(recs)) == []
    ex = list(export.to_instruction_examples(recs, include_unverified=True))
    assert len(ex) == 12 and all(e["meta"]["synthetic"] for e in ex)
    verified = R("Abeg", "please", human_verified=True, confidence=0.9)
    assert len(list(export.to_instruction_examples([verified]))) == 2


def test_slang_db_build_and_search(tmp_path):
    db = str(tmp_path / "s.db")
    assert slangdb.build_db(ingest.read_jsonl(SEED), db) == 6
    hits = slangdb.search(db, "wahala")
    assert any(h["expression"] == "No wahala" for h in hits)
    assert any(h["expression"] == "No wahala" for h in slangdb.search(db, "wahalla"))  # alt spelling
    assert slangdb.search(db, "zzzznotreal") == []


def test_slang_db_works_offline_file_only(tmp_path):
    # The DB is a plain local SQLite file; no network libs are imported by the module.
    import pidgin_pipeline.slangdb as m
    src = Path(m.__file__).read_text()
    assert "urllib" not in src and "requests" not in src and "socket" not in src


def test_cli_end_to_end(tmp_path, capsys):
    out = tmp_path / "sp"
    assert cli.main(["split", str(SEED), "-o", str(out)]) == 0
    assert cli.main(["build-db", str(SEED), "-o", str(tmp_path / "d.db")]) == 0
    assert cli.main(["search", str(tmp_path / "d.db"), "abeg"]) == 0
    assert "Abeg" in capsys.readouterr().out
