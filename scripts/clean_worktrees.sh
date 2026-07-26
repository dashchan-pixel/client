#!/usr/bin/env bash

# clean_worktrees.sh
# Audits git worktrees for work that would be lost if they were removed:
# uncommitted changes, untracked files, and commits not merged into a reference
# branch. Also reports repo-wide stashes, leftover `worktree-*` branches with no
# worktree, and stale directories in the worktree container that git no longer
# tracks.
#
# Usage: ./clean_worktrees.sh [--remove] [--ref <rev>] [--dir <path>]
#   --remove     delete anything reported as clean (worktrees, merged branches,
#                stale directories). Without it the script only reports.
#   --ref <rev>  compare worktree commits against <rev>. Defaults to the branch
#                checked out in the main worktree.
#   --dir <path> worktree container to scan for stale directories.
#                Defaults to <repo>/.claude/worktrees.

set -uo pipefail

REMOVE_CLEAN=false
REF_ARG=""
CONTAINER_ARG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --remove) REMOVE_CLEAN=true; shift ;;
        --ref) REF_ARG="${2:-}"; shift 2 ;;
        --dir) CONTAINER_ARG="${2:-}"; shift 2 ;;
        -h|--help) sed -n '3,17p' "$0" | cut -c3-; exit 0 ;;
        *) echo "Error: unknown argument '$1'"; exit 1 ;;
    esac
done

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: Not in a git repository."
    exit 1
fi

# The main worktree is always the first entry of `git worktree list`.
MAIN_WT=$(git worktree list --porcelain | awk '/^worktree /{print substr($0, 10); exit}')

# Resolve the reference to compare against. Running this from inside a worktree
# must not make that worktree its own baseline, so default to the main worktree's
# branch rather than the current HEAD.
if [ -n "$REF_ARG" ]; then
    REF_NAME="$REF_ARG"
else
    REF_NAME=$(git -C "$MAIN_WT" rev-parse --abbrev-ref HEAD)
    [ "$REF_NAME" = "HEAD" ] && REF_NAME=$(git -C "$MAIN_WT" rev-parse HEAD)
fi

if ! REF_HEAD=$(git rev-parse --verify --quiet "$REF_NAME^{commit}"); then
    echo "Error: reference '$REF_NAME' does not resolve to a commit."
    exit 1
fi

# Never remove the worktree the script is being run from.
SELF_DIR=$(pwd -P)

# Container holding the per-agent worktrees.
if [ -n "$CONTAINER_ARG" ]; then
    CONTAINER="$CONTAINER_ARG"
else
    CONTAINER="$MAIN_WT/.claude/worktrees"
fi

echo "Checking git worktrees for lost work (against '$REF_NAME' = ${REF_HEAD:0:7})..."

# Collect the registered worktree paths up front so the loop body runs in this
# shell and can accumulate state.
mapfile -t WORKTREES < <(git worktree list --porcelain | awk '/^worktree /{print substr($0, 10)}')

removed=0
kept=0

for wt in "${WORKTREES[@]}"; do
    echo "--------------------------------------------------"
    echo "Worktree: $wt"

    if [ ! -d "$wt" ]; then
        echo "  [!] Directory is missing; will be pruned."
        continue
    fi

    has_lost_work=false

    changes=$(git -C "$wt" status --porcelain)
    if [ -n "$changes" ]; then
        echo "  [!] Uncommitted changes or untracked files:"
        echo "$changes" | head -n 20 | awk '{print "      " $0}'
        total=$(echo "$changes" | grep -c "^")
        [ "$total" -gt 20 ] && echo "      ... and $((total - 20)) more"
        has_lost_work=true
    fi

    ahead_count=$(git -C "$wt" rev-list --count "$REF_HEAD"..HEAD 2>/dev/null || echo 0)
    if [ "$ahead_count" -gt 0 ]; then
        echo "  [!] $ahead_count commit(s) not in '$REF_NAME':"
        git -C "$wt" log --oneline "$REF_HEAD"..HEAD | head -n 5 | awk '{print "      " $0}'
        [ "$ahead_count" -gt 5 ] && echo "      ... and $((ahead_count - 5)) more"
        has_lost_work=true
    fi

    if [ "$has_lost_work" = true ]; then
        kept=$((kept + 1))
        continue
    fi

    echo "  [✓] Clean; all commits are in '$REF_NAME'."

    [ "$REMOVE_CLEAN" = false ] && continue

    # .git is a directory in the main worktree, a file in linked ones.
    if [ ! -f "$wt/.git" ]; then
        echo "  [i] Skipping the main repository worktree."
        continue
    fi

    if [ "$(cd "$wt" && pwd -P)" = "$SELF_DIR" ]; then
        echo "  [i] Skipping: this is the worktree the script is running in."
        continue
    fi

    branch=$(git -C "$wt" symbolic-ref --quiet --short HEAD || true)
    echo "  [*] Removing clean worktree..."
    # Two --force flags are required to remove a locked worktree.
    if git worktree remove --force --force "$wt"; then
        removed=$((removed + 1))
        if [ -n "$branch" ] && [ "$branch" != "$REF_NAME" ]; then
            # -d (not -D) so an unexpectedly unmerged branch survives.
            git branch -d "$branch" >/dev/null 2>&1 \
                && echo "  [*] Deleted merged branch '$branch'." \
                || echo "  [!] Kept branch '$branch' (not fully merged)."
        fi
    fi
done

echo "--------------------------------------------------"

if [ "$REMOVE_CLEAN" = true ]; then
    git worktree prune
fi

# Branches left behind by worktrees that are already gone.
#
# A plain ancestor test (`rev-list REF..branch`) is too strict here: agent
# branches routinely get rebased or replayed onto the reference, so their
# commits live on under different hashes. `git cherry` compares patch-ids and
# marks a commit '-' when an identical diff already exists upstream, so a branch
# whose commits are all '-' carries no work even though it is not an ancestor.
mapfile -t CHECKED_OUT < <(git worktree list --porcelain | awk '/^branch /{sub("refs/heads/", "", $2); print $2}')
orphan_spent=()
orphan_unmerged=()

while read -r b; do
    [ -z "$b" ] && continue
    skip=false
    for c in ${CHECKED_OUT[@]+"${CHECKED_OUT[@]}"}; do
        [ "$b" = "$c" ] && skip=true && break
    done
    [ "$skip" = true ] && continue

    unique=$(git cherry "$REF_HEAD" "$b" 2>/dev/null | grep -c '^+') || unique=0
    if [ "$unique" -eq 0 ]; then
        orphan_spent+=("$b")
    else
        orphan_unmerged+=("$b")
    fi
done < <(git branch --list 'worktree-*' --format='%(refname:short)')

if [ "${#orphan_unmerged[@]}" -gt 0 ]; then
    echo "[!] ${#orphan_unmerged[@]} orphan 'worktree-*' branch(es) carrying work not in '$REF_NAME' (kept):"
    for b in "${orphan_unmerged[@]}"; do
        echo "    $b (+$(git cherry "$REF_HEAD" "$b" | grep -c '^+') unique commit(s))"
    done
fi

if [ "${#orphan_spent[@]}" -gt 0 ]; then
    if [ "$REMOVE_CLEAN" = true ]; then
        echo "[*] Deleting ${#orphan_spent[@]} spent orphan 'worktree-*' branch(es)..."
        # -D, not -d: a rebased branch is not an ancestor of the reference, so -d
        # refuses it. The `git cherry` check above already proved every commit
        # has a patch-identical twin upstream. `git reflog` can still recover.
        git branch -D "${orphan_spent[@]}" >/dev/null && echo "    done."
    else
        echo "[i] ${#orphan_spent[@]} orphan 'worktree-*' branch(es) whose commits are all already in '$REF_NAME' (--remove deletes them):"
        printf '    %s\n' "${orphan_spent[@]}"
    fi
fi

# Directories in the container that git no longer tracks: build output and other
# leftovers from removed worktrees. These are invisible to `git worktree list`
# but can hold hundreds of MB.
if [ -d "$CONTAINER" ]; then
    stale=()
    unexpected=()
    for d in "$CONTAINER"/*; do
        [ -d "$d" ] || continue
        real=$(cd "$d" && pwd -P)

        registered=false
        for wt in "${WORKTREES[@]}"; do
            [ -d "$wt" ] && [ "$(cd "$wt" && pwd -P)" = "$real" ] && registered=true && break
        done
        [ "$registered" = true ] && continue

        # Don't delete the directory the script is running in, or an ancestor.
        case "$SELF_DIR" in "$real"|"$real"/*) continue ;; esac

        # A leftover with a .git entry is a worktree git lost track of; that
        # needs a human, not an rm -rf.
        if [ -e "$d/.git" ]; then
            unexpected+=("$d")
        else
            stale+=("$d")
        fi
    done

    if [ "${#unexpected[@]}" -gt 0 ]; then
        echo "[!] ${#unexpected[@]} unregistered director(ies) still containing .git (kept, inspect manually):"
        printf '    %s\n' "${unexpected[@]}"
    fi

    if [ "${#stale[@]}" -gt 0 ]; then
        size=$(du -sh "${stale[@]}" 2>/dev/null | awk '{print $1}' | paste -sd' ' -)
        if [ "$REMOVE_CLEAN" = true ]; then
            echo "[*] Removing ${#stale[@]} stale director(ies) ($size)..."
            rm -rf "${stale[@]}" && echo "    done."
        else
            echo "[i] ${#stale[@]} stale director(ies) with no git worktree ($size) (--remove deletes them):"
            printf '    %s\n' "${stale[@]}"
        fi
    fi
fi

# Stashes are repository-wide, so they are reported once rather than per worktree.
stash_count=$(git stash list | grep -c "^")
if [ "$stash_count" -gt 0 ]; then
    echo "[!] The repository has $stash_count stashed item(s) (stashes are repo-wide):"
    git stash list | head -n 5 | awk '{print "    " $0}'
    [ "$stash_count" -gt 5 ] && echo "    ... and $((stash_count - 5)) more"
fi

echo "--------------------------------------------------"
if [ "$REMOVE_CLEAN" = true ]; then
    echo "Removed $removed worktree(s); kept $kept with unsaved work."
else
    echo "$kept worktree(s) hold unsaved work. Re-run with --remove to clean the rest."
fi
