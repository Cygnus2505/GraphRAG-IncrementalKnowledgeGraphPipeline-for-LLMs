# Multi-stage build for GraphRAG REST API
# Stage 1: Build the application
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Install SBT using official method
RUN apt-get update && \
    apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt=1.9.9 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Verify SBT installation
RUN sbt sbtVersion

# Copy build files
COPY build.sbt project/ ./
COPY project/ ./project/

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src/ ./src/

# Build the application
RUN sbt assembly

# Stage 2: Runtime image
# Using Eclipse Temurin (successor to OpenJDK Docker images)
FROM eclipse-temurin:17-jre

WORKDIR /app

# Install required system dependencies
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the assembled JAR from builder
COPY --from=builder /app/target/scala-2.12/cs441-hw3-graphrag-assembly-0.1.0-SNAPSHOT.jar /app/graphrag-api.jar

# Copy application configuration
COPY src/main/resources/application.conf /app/application.conf

# Expose API port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Run the API server
# Using -cp to override the default main class (assembly sets GraphRagJob as main)
ENTRYPOINT ["java", \
  "-Xmx512m", \
  "-Xms256m", \
  "-Dconfig.file=/app/application.conf", \
  "-cp", \
  "/app/graphrag-api.jar", \
  "graphrag.api.ApiServer"]

