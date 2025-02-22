FROM eclipse-temurin:21

LABEL org.opencontainers.image.source=https://github.com/xtdb/xtdb
LABEL org.opencontainers.image.description="XTDB Google Cloud Benchmark 2.x"
LABEL org.opencontainers.image.licenses="MPL-2.0"

WORKDIR /usr/local/lib/xtdb

ENTRYPOINT ["java", \
    "-Dclojure.main.report=stderr", \
    "-Dlogback.configurationFile=logback.xml", \
    "-Xmx1500m", "-Xms1500m", \
    "-XX:MaxDirectMemorySize=2000m", \
    "-XX:MaxMetaspaceSize=500m", \
    "--add-opens=java.base/java.nio=ALL-UNNAMED", \
    "-Dio.netty.tryReflectionSetAccessible=true", \
    "-cp","xtdb-google-cloud-bench.jar", \
    "clojure.main", "-m", "xtdb.run-auctionmark.main"]

HEALTHCHECK --start-period=15s --timeout=3s \
    CMD curl -f http://localhost:8080/healthz/alive || exit 1

RUN mkdir -p /var/lib/xtdb
VOLUME /var/lib/xtdb

ARG GIT_SHA
ARG XTDB_VERSION
ENV GIT_SHA=${GIT_SHA}
ENV XTDB_VERSION=${XTDB_VERSION}

LABEL org.opencontainers.image.version=${XTDB_VERSION}
LABEL org.opencontainers.image.revision=${GIT_SHA}

ADD google-cloud-config.yaml node-config.yaml
ADD build/libs/xtdb-google-cloud-bench.jar xtdb-google-cloud-bench.jar
