#!/usr/bin/env bash
# Rank nullable declarations by how many '!!' they force at their use sites.
#
# Why this is a script and not a detekt rule:
#
# detekt's CanBeNonNullable is the rule for this job, and it is structurally blind to the
# dominant case. It only fires on a property it can prove is never null -- but the J2K
# converter emitted `var x: T? = null`, and such a field genuinely IS null at construction.
# So the rule stays silent on ~850 fields while reporting 98. The config was never wrong;
# the rule simply cannot see this shape.
#
# What it can't see is that the '!!' are not independent decisions. They cluster on a few
# hundred identifiers: one `var adapter: PostsAdapter? = null` produced 54 `adapter!!` in a
# single file. Fixing the DECLARATION retires every one of them at once, and with
# allWarningsAsErrors the compiler proves each removal.
#
# This ranks the declarations by that blast radius, so the work goes in payoff order.
#
# Usage:
#   scripts/hot-nullables.sh            top 40 identifiers repo-wide
#   scripts/hot-nullables.sh 100        top 100
#   scripts/hot-nullables.sh 20 gallery only paths matching 'gallery'
#
# Reading the output: a HIGH count is GOOD NEWS -- it is one declaration to fix, not N.
#
# Before converting a field to `lateinit`, check the column that matters:
#   null-checked  -- if anything does `if (x != null)`, `x?.`, or `x ?: y`, the field is an
#                    OPTIONAL. Leave it nullable. Making it lateinit changes behaviour.
#   set-to-null   -- if it is nulled on teardown, same conclusion.
# Only a field used EXCLUSIVELY through '!!' is a lateinit candidate.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

limit="${1:-40}"
filter="${2:-}"

if [ -n "$filter" ]; then
	files=$(grep -rl '!!' src --include=*.kt | grep -- "$filter")
else
	files=$(grep -rl '!!' src --include=*.kt)
fi

if [ -z "$files" ]; then
	echo "no Kotlin sources with '!!' matched" >&2
	exit 1
fi

total=$(grep -hoE '!!' $files | wc -l | tr -d ' ')
printf '%d double-bangs across %d files\n\n' "$total" "$(echo "$files" | wc -l | tr -d ' ')"
printf '%6s  %-28s %-11s %-12s %s\n' "!!" "IDENTIFIER" "SET-TO-NULL" "NULL-CHECKED" "VERDICT"
printf '%6s  %-28s %-11s %-12s %s\n' "----" "----------------------------" "-----------" "------------" "-------"

grep -hoE '\b[a-zA-Z_][a-zA-Z0-9_]*!!' $files |
	sed 's/!!$//' |
	sort | uniq -c | sort -rn | head -"$limit" |
	while read -r count name; do
		# A field that is ever assigned null, or ever null-checked, is being used as an
		# optional -- it must stay nullable regardless of how many '!!' it carries.
		nulled=$(grep -rhoE "(^|[^a-zA-Z0-9_.])${name}[[:space:]]*=[[:space:]]*null" src --include=*.kt | wc -l | tr -d ' ')
		checked=$(grep -rhoE "${name}[[:space:]]*(!=|==)[[:space:]]*null|${name}\?\.|${name}[[:space:]]*\?:" src --include=*.kt | wc -l | tr -d ' ')

		if [ "$nulled" -gt 0 ] || [ "$checked" -gt 0 ]; then
			verdict='keep nullable; hoist `val x = this.x ?: return`'
		else
			verdict='lateinit/val candidate'
		fi
		printf '%6s  %-28s %-11s %-12s %s\n' "$count" "$name" "$nulled" "$checked" "$verdict"
	done
