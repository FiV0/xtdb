# Claude Development Utilities

This document describes local development utilities created to assist with XTDB development workflow.

## Git Worktree Management

### bin/worktree-checkout

A utility script for managing git worktrees to work on multiple branches simultaneously.

#### Usage

```bash
./bin/worktree-checkout <branch-name>
```

#### What it does

1. **Creates trees directory**: Creates a `trees/` directory in the project root if it doesn't exist (only once)
2. **Updates main branch**: Switches to main branch and pulls latest changes from origin
3. **Handles different branch scenarios**:
   - If branch exists locally: Creates worktree from local branch
   - If branch exists on remote: Creates worktree from remote branch
   - If branch doesn't exist: Creates new branch from main
4. **Creates worktree**: Sets up the worktree at `trees/<branch-name>/`

#### Examples

```bash
# Create worktree for existing branch
./bin/worktree-checkout feature-xyz

# Create worktree for new branch (will be created from main)
./bin/worktree-checkout new-feature

# Work in the worktree
cd trees/feature-xyz
# ... make changes, commit, etc.
```

#### Benefits

- Work on multiple branches simultaneously without switching contexts
- Keeps different feature branches isolated in separate directories
- Automatic main branch updates before creating new branches
- Handles both new and existing branches intelligently

#### File Organization

- Main repository: `/home/user/xtdb/` (your main working directory)
- Worktrees: `/home/user/xtdb/trees/branch-name/` (isolated branch directories)

#### Note

This script is ignored locally via `.git/info/exclude` and won't be committed to the repository.

## REPL Management Setup

A complete REPL lifecycle management system for seamless development workflow.

### Components

#### bin/repl-manager
Central script for managing the Clojure REPL process with commands:
- `start`: Starts REPL on a free port
- `stop`: Stops running REPL
- `restart`: Stops and starts REPL
- `status`: Shows REPL status and port

#### MCP Integration
- `.mcp.json`: Configures clojure-mcp to connect to the managed REPL
- Reads port from `.repl-port` file automatically

### Usage

```bash
# Initial setup: Start REPL manually before using Claude Code
./bin/repl-manager start

# REPL is then managed by Claude Code
# Check REPL status during development
./bin/repl-manager status

# Restart REPL for fresh environment
./bin/repl-manager restart

# REPL stops automatically when Claude Code exits
```

### Benefits

- **Initial setup**: Start REPL once before using Claude Code
- **Port management**: Dynamic port allocation avoids conflicts
- **Clean shutdown**: Proper process cleanup on exit
- **MCP integration**: Seamless connection to Clojure tooling
- **Development continuity**: Persistent REPL session during Claude Code usage

### Files Created

- `bin/repl-manager` (executable script)
- `.mcp.json` (MCP configuration)
- `.repl-port` (generated at runtime, ignored)
- `.repl-pid` (generated at runtime, ignored)

All generated files and utility scripts are ignored locally via `.git/info/exclude`.