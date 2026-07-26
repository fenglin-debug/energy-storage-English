#!/usr/bin/env python3
"""Generate FSRS v6 golden vectors from py-fsrs 6.3.1 (MIT).

This script is the single source of truth for android/core/model/src/test/resources/fsrs_golden.json.
Re-run with the system Python 3.13 to regenerate:

    C:/Users/fengl/AppData/Local/Programs/Python/Python313/python.exe android/tools/fsrs_golden_gen.py

Determinism: fuzzing is disabled. All review datetimes are fixed to UTC noon so
`due_iso` values are reproducible regardless of when the script runs.
"""
import json
from datetime import datetime, timedelta, timezone
from fsrs import Card, Rating, Scheduler

BASE = datetime(2024, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
OUT = "android/core/model/src/test/resources/fsrs_golden.json"

scheduler = Scheduler(enable_fuzzing=False)

SEQUENCES = {
    # (rating, elapsed_days_since_previous_review)
    "first_rating_good_then_good": [("Good", 0), ("Good", 3)],
    "first_rating_easy": [("Easy", 0)],
    "first_rating_hard": [("Hard", 0)],
    "first_rating_again": [("Again", 0)],
    "mixed_sequence": [
        ("Good", 0), ("Good", 3), ("Hard", 5),
        ("Again", 1), ("Good", 2), ("Easy", 7),
    ],
    "same_day_repeat": [("Good", 0), ("Good", 0), ("Good", 0)],
    "learning_to_review": [("Again", 0), ("Hard", 0), ("Good", 1), ("Good", 3)],
}


def main() -> None:
    data = {
        "meta": {
            "fsrs_package": "py-fsrs",
            "fsrs_version": "6.3.1",
            "desired_retention": scheduler.desired_retention,
            "parameters": list(scheduler.parameters),
            "base_time": BASE.isoformat(),
        }
    }
    for name, steps in SEQUENCES.items():
        card = Card(due=BASE)
        review_time = BASE
        rows = []
        for rating_name, elapsed_days in steps:
            review_time = review_time + timedelta(days=elapsed_days)
            card, _ = scheduler.review_card(
                card, getattr(Rating, rating_name), review_time
            )
            rows.append(
                {
                    "rating": rating_name,
                    "elapsed_days": elapsed_days,
                    "state": card.state.name,
                    "stability": round(card.stability, 8),
                    "difficulty": round(card.difficulty, 8),
                    "due_iso": card.due.isoformat(),
                    "step": card.step,
                }
            )
        data[name] = rows

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
