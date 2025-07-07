# CLAUDE.md

## Project Overview

XTDB is a general-purpose database with graph query, SQL, and document capabilities. It's designed with an "inside-out" architecture where queries are computed by "joining" against time itself, providing immutable and bitemporal data management.

Key features:
- SQL query interface
- Immutable data storage
- Bitemporal capabilities (valid-time and transaction-time)
- Log-centric architecture
- Multi-language support

## Project Structure

This is a multi-module Gradle project with polyglot support (Clojure, Kotlin, Java):

### Core Modules
- `api/` - Core API definitions
- `core/` - Main database engine
- `http-server/` - HTTP server implementation

### Cloud Provider Modules
- `aws/` - AWS integrations
- `azure/` - Azure integrations
- `google-cloud/` - Google Cloud integrations

### Additional Modules
- `flight-sql/` - Arrow Flight SQL support
- `bench/` - Benchmarking tools
- `datasets/` - Test datasets
- `modules/` - Various specialized modules including the kafka transaction log

## Build & Test Commands

### REPL-Driven Development

XTDB uses REPL-driven development for interactive development and testing. The REPL is automatically managed through Claude Code hooks.

#### Automatic REPL Management
- Port is dynamically assigned and saved to `.repl-port`
- Process ID saved to `.repl-pid` for clean shutdown

#### Manual REPL Control
```bash
# Check REPL status
./bin/repl-manager status

# Restart REPL (useful for fresh environment)
./bin/repl-manager restart

# Manual start/stop if needed
./bin/repl-manager start
./bin/repl-manager stop
```

#### Important Notes
- **The REPL needs to be restarted when Kotlin or Java files are changed** - The REPL only automatically reloads Clojure files. After modifying Java or Kotlin code, run `./bin/repl-manager restart` to ensure the changes are reflected in the REPL environment.

### Git Worktree Management
```bash
# Create a new worktree in trees/ directory
./bin/worktree-checkout <branch-name>

# List and manage existing worktrees
./bin/worktree-status
```

The worktree tools help manage multiple branches simultaneously by creating separate working directories under `trees/`.

### Requirements
- JDK 21 (required)
- Gradle wrapper (included)

### Testing

#### Clojure Tests
Run Clojure tests directly via clojure-mcp using the REPL:

```clojure
;; Run a specific test
(clojure.test/run-tests 'xtdb.sql-test)

;; Run a specific test function
(clojure.test/test-var #'xtdb.sql-test/insert-records-system-from-should-error-issue-4550)

;; Run all tests in the current namespace
(clojure.test/run-tests)

;; Run all tests in project
(clojure.test/run-all-tests)
```

#### Gradle Tests
All other tests (Kotlin/Java) need to be run via `./gradlew`

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew integration-test

# Cloud provider tests (nightly)
./gradlew nightly-test

# SQL Logic Tests
./gradlew slt-test

# Run specific tests
./gradlew test --tests "xtdb.ExampleTest"                 # Specific test class
./gradlew test --tests "xtdb.ExampleTest.specificMethod"  # Specific test method
./gradlew test --tests "*ExampleTest*"                    # Pattern matching
./gradlew test --tests ":core:test --tests "xtdb.ExampleTest"          # Module-specific test
```


## Tool Selection Guidelines

### Clojure MCP Tools vs Claude Code Tools

When working with Claude Code on this project, prefer **Clojure MCP tools** for:
- Clojure-related tasks (reading, editing, evaluating Clojure code)
- File operations on Clojure files (.clj, .cljs, .cljc, .edn)
- Project exploration and analysis
- REPL interactions and code evaluation
- Clojure-specific searches and code inspection

Use **built-in Claude Code tools** for:
- Non-Clojure tasks (Java, Kotlin, YAML, etc.)
- General file operations outside of Clojure context
- Git operations and repository management
- Build and test commands (Gradle)
- General bash commands not related to Clojure development

The Clojure MCP tools provide enhanced syntax awareness, better code formatting, and deeper integration with the Clojure ecosystem.

### Tool Usage Guidelines
- Try to use the standard bash tool over clojure-mcp:bash.

## Core Development Philosophy

**TEST-DRIVEN DEVELOPMENT IS FUNDAMENTAL.** Try to write features, components and addtions in response to a failing test.
If you can't follow this principle, please state so explicitly or only not follow it if asked to explicitly.

We follow Test-Driven Development (TDD) with a strong emphasis on behavior-driven testing and functional programming principles. Try to do all work in small, incremental changes that maintain a working state throughout development. This might not always be possible if you are doing larger blocks, but a commit should always leave the system in a working state.


## Code Quality & Standards

### Linting
- Uses clj-kondo for Clojure code analysis
- Pre-commit hooks enforce code quality
- Look for `<<no-commit>>` markers that prevent commits

### Code Style
- Follow existing Clojure conventions and idiomatic style
- Reference the [Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide) for best practices
- Use kebab-case for function names
- Keep functions small and focused
- Write descriptive docstrings
- Follow namespace organization patterns

```clojure
;; Avoid: Unnecessary let bindings for simple conditional operations
(defn init-component [config]
  (let [component (create-component config)]
    (when (:enabled? config)
      (.start component))
    component))

;; Good: Use cond-> for conditional method calls
(defn init-component [config]
  (cond-> (create-component config)
    (:enabled? config) (.start)))

;; Avoid: Multiple let bindings when cond-> suffices
(defn configure-service [options]
  (let [service (Service.)]
    (when (:debug? options)
      (.setDebugMode service true))
    (when (:timeout options)
      (.setTimeout service (:timeout options)))
    service))

;; Good: Chain conditional operations with cond->
(defn configure-service [options]
  (cond-> (Service.)
    (:debug? options) (.setDebugMode true)
    (:timeout options) (.setTimeout (:timeout options))))
```

### Testing
- Comprehensive test coverage expected
- Unit tests in `test/` directories
- Integration tests use real components
- Use existing test utilities and patterns

### Bug Fix Testing
When fixing bugs from GitHub issues, follow these naming conventions for tests:
- **Clojure**: `(deftest test-name-issue-123 ...)`
- **Kotlin**: `fun testNameIssue123() { ... }`
- **Java**: `void testNameIssue123() { ... }`

The test name should end with the GitHub issue number (e.g., `issue-123` or `issue123`) to maintain traceability between bug reports and their corresponding test cases. This applies to all test types (unit, integration, regression) across all languages in the project.

## Common Development Workflows

2. Load development environment with `(dev)`
3. Start system with `(go)`
4. Make changes and test interactively
5. Reset system with `(reset)` when needed

### Testing Workflow
1. Run unit tests frequently: `./gradlew test`
2. Run integration tests for broader changes: `./gradlew integration-test`
3. Use Docker Compose for external dependencies
4. Check specific module tests: `./gradlew :module-name:test`

### Adding New Features
1. Understand the module structure
2. Follow existing patterns and conventions
3. Add comprehensive tests
4. Update documentation if needed
5. Run full test suite before submitting

## Configuration

- Uses YAML-based configuration
- Configuration files typically in `resources/` directories
- Environment-specific configs supported
- Docker images available for various environments

## Deployment

- Docker images built for different environments
- Kubernetes/Helm charts available
- Monitoring and metrics setup included
- Multiple deployment targets (cloud providers)

## Troubleshooting

### Common Issues
- JDK version compatibility (ensure JDK 21)
- External dependencies not running (check docker-compose)
- REPL connection issues (restart with `(reset)`)
- Test failures due to missing dependencies

### Debugging
- Use REPL for interactive debugging
- Check logs in development environment
- Verify external services are running
- Review test output for specific failures

## CI/CD

- GitHub Actions for continuous integration
- Multiple test suites run automatically
- Pre-commit hooks prevent common issues
- Automated builds and deployments

## Development Language Guidelines

- Always prefer Kotlin code over Java code
- If building a component with a lot of state, try to use Kotlin
- For rather pure components, you can stay in Clojure

## Notes

- This is primarily a Clojure project with polyglot support
- Uses Gradle for build management (not Leiningen)
- Extensive test coverage is expected
- REPL-driven development is the norm
- External dependencies often required for testing
- Cloud provider integrations require specific setup
