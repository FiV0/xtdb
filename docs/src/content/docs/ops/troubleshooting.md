---
title: Troubleshooting
---

JUXT provide [enterprise XTDB support](https://xtdb.com/support) for companies looking to adopt or extend their usage of XTDB, including training, consultancy and production support - email <hello@xtdb.com> for more information.

Given XTDB is a public, open-source project, you may also find useful information: - on [Discuss XTDB](https://discuss.xtdb.com) (public forum) - on the [GitHub repository](https://github.com/xtdb/xtdb) (where you can [check existing issues](https://github.com/xtdb/xtdb/issues) or [raise a new issue](https://github.com/xtdb/xtdb/issues/new).)

## Setting the logging level

The simplest way to modify XTDB's logging level is through environment variables:

- `XTDB_LOGGING_LEVEL`:: sets the logging level for all XTDB components.
- You can also set the logging level for individual components - for example:
- `XTDB_LOGGING_LEVEL_COMPACTOR`
- `XTDB_LOGGING_LEVEL_INDEXER`
- `XTDB_LOGGING_LEVEL_PGWIRE`

Valid levels are `TRACE`, `DEBUG`, `INFO` (default), `WARN`, and `ERROR`.

### Logback configuration

For more control, you can also supply a custom [logback.xml](https://logback.qos.ch/manual/configuration.html) file:

``` xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="WARN">
        <appender-ref ref="STDOUT" />
    </root>
    <logger name="xtdb" level="TRACE" />
</configuration>
```

You can then pass the `logback.xml` configuration file to the Docker container using the following command:

`docker run --volume .:/config --env JDK_JAVA_OPTIONS='-Dlogback.configurationFile=/config/logback.xml' …​`

## Ingestion stopped

If you encounter an 'ingestion stopped' error, it means that XTDB has encountered an unrecoverable error while processing a transaction, and has stopped further transaction processing in order to prevent corruption to your data.
At this point, the built-in health checks will flag that the node is unhealthy and, if you're running XTDB within a container orchestrator (e.g. Kubernetes), it will restart the problematic node.

XTDB is a deterministic system where the individual nodes run without inter-node communication or consensus - each node processes the same totally-ordered log and writes files to the object-store.
For deterministic errors - e.g. syntax errors, or divide by 0 - we can be sure that all nodes will reach the same conclusion, so we roll back the transaction in question and continue.
If a non-deterministic indexing error is raised on any individual node (e.g. it loses connection to the log or object-stores), that node cannot unilaterally make the decision to roll back the transaction and continue, as doing so would lead to data inconsistencies between nodes.

For temporary issues (e.g. connectivity), restarting the individual node will often resolve the issue without any manual intervention required.
However, in the rare event of an XTDB bug where we cannot guarantee the same error will occur on all nodes, we choose to err on the side of safety, but this may cause a restart loop.

In this case, XTDB will upload a crash log to your object-store containing the diagnostic information required to debug the issue - the exact location will be logged in your XTDB container.

Please do raise this with the XTDB team at <hello@xtdb.com>.

### Skipping Transactions

You *may* want to try skipping the errant transaction.

This action *must* be applied atomically: all nodes must be stopped, the configuration change applied, and then all nodes restarted. (Otherwise, the above risk applies - some nodes commit the transaction and others choose to roll it back.)

1. Verify that ingestion stops at the same transaction on all nodes, and identify the errant transaction ID in the logs.
2. Scale down all nodes within the XTDB cluster.
3. Set `XTDB_SKIP_TXS="<txId>,<txId2>…​"` as an environment variable on all of the nodes.
4. Restart all nodes within the XTDB cluster.

When the errant transaction has been skipped, you will see a log message: "skipping transaction offset 2109".
Once the next block has been written, you will then see another log message: "it is safe to remove the XTDB_SKIP_TXS environment variable".
This can be applied as a standard (green/blue) configuration change, at your convenience.
