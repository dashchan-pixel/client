#!/usr/bin/env bash

# clean_worktrees.sh
# Checks all git worktrees in the current repository for uncommitted changes,
# untracked files, and commits not merged into the current branch.
# Also checks for any git stashes.
# Usage: ./clean_worktrees.sh [--remove]

REMOVE_CLEAN=false
if [ "$1" = "--remove" ]; then
    REMOVE_CLEAN=true
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: Not in a git repository."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
CURRENT_HEAD=$(git rev-parse HEAD)

if [ "$CURRENT_BRANCH" = "HEAD" ]; then
    # Detached HEAD
    REF_NAME="current HEAD (${CURRENT_HEAD:0:7})"
else
    REF_NAME="branch '$CURRENT_BRANCH'"
fi

echo "Checking git worktrees for lost work (against $REF_NAME)..."

git worktree list --porcelain | awk '/^worktree / {print substr($0, 10)}' | while read -r wt; do
    echo "--------------------------------------------------"
    echo "Worktree: $wt"
    
    if [ ! -d "$wt" ]; then
        echo "  [!] Directory is missing. Consider running 'git worktree prune'."
        continue
    fi
    
    (
        cd "$wt" || exit 1
        
        changes=$(git status --porcelain)
        
        has_lost_work=false
        
        if [ -n "$changes" ]; then
            echo "  [!] Uncommitted changes or untracked files:"
            echo "$changes" | awk '{print "      " $0}'
            has_lost_work=true
        fi
        
        # Check if the worktree HEAD has commits not in the reference branch
        ahead_count=$(git rev-list --count "$CURRENT_HEAD"..HEAD 2>/dev/null || echo 0)
        
        if [ "$ahead_count" -gt 0 ]; then
            echo "  [!] $ahead_count commit(s) not in $REF_NAME:"
            git log --oneline "$CURRENT_HEAD"..HEAD | head -n 5 | awk '{print "      " $0}'
            if [ "$ahead_count" -gt 5 ]; then
                echo "      ... and $((ahead_count - 5)) more"
            fi
            has_lost_work=true
        fi
        
        if [ "$has_lost_work" = false ]; then
            echo "  [✓] Worktree is clean and all commits are in $REF_NAME."
            exit 0
        else
            exit 2
        fi
    )
    
    is_clean=$?
    
    if [ "$is_clean" -eq 0 ] && [ "$REMOVE_CLEAN" = true ]; then
        # .git is a directory in the main repo, but a file in added worktrees
        if [ -f "$wt/.git" ]; then
            echo "  [*] Removing clean worktree..."
            git worktree remove --force --force "$wt"
        else
            echo "  [i] Skipping removal of the main repository worktree."
        fi
    fi
done
echo "--------------------------------------------------"

# Check for stashes (stashes are repository-wide)
stash_count=$(git stash list | grep -c "^")
if [ "$stash_count" -gt 0 ]; then
    echo "[!] The repository has $stash_count stashed item(s) (stashes are repo-wide):"
    git stash list | head -n 5 | awk '{print "    " $0}'
    if [ "$stash_count" -gt 5 ]; then
        echo "    ... and $((stash_count - 5)) more"
    fi
    echo "--------------------------------------------------"
fi
