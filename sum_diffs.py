#!/usr/bin/env python3
"""Sum all diff_applied values in savings_diffs.txt and checking_diffs.txt."""
import re
import sys

PAT = re.compile(r"diff_applied=(-?\d+)")

initial_balance = 10000

def main(path="savings_diffs.txt"):
    total = 0
    count = 0
    with open(path) as f:
        for line in f:
            m = PAT.search(line)
            if m:
                total += int(m.group(1))
                count += 1
    print(f"path: {path}, sum: {total}")
    # print(f"lines matched: {count}")


if __name__ == "__main__":
    main("savings_diffs.txt")
    main("checking_diffs.txt")
