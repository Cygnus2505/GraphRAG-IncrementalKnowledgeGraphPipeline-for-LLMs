ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.18"

import sbtassembly.MergeStrategy
import sbt.util.Level

lazy val root = (project in file("."))
  .settings(
    name := "cs441-hw3-graphrag",
    Compile / run / mainClass := Some("graphrag.GraphRagJob"),
    assembly / mainClass := Some("graphrag.GraphRagJob"),
    
    // Allow running API server directly
    Compile / runMain / mainClass := Some("graphrag.api.ApiServer"),
    
    // Enable forking to use JVM options
    Compile / run / fork := true,
    Test / fork := true,
    
    // JVM options
    Compile / run / javaOptions ++= Seq(
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
      "-Dorg.slf4j.simpleLogger.showDateTime=true",
      "-Dorg.slf4j.simpleLogger.showThreadName=false",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED"
    ),
    
    libraryDependencies ++= Seq(
      // Apache Flink dependencies (1.20.0)
      // Marked as "provided" for cluster deployment (Flink cluster provides these)
      // Remove "% provided" for local sbt run with embedded cluster
      "org.apache.flink" % "flink-clients" % "1.20.0" % "provided",
      "org.apache.flink" % "flink-streaming-java" % "1.20.0" % "provided",
      "org.apache.flink" %% "flink-streaming-scala" % "1.20.0" % "provided",
      "org.apache.flink" %% "flink-scala" % "1.20.0" % "provided",
      "org.apache.flink" % "flink-runtime-web" % "1.20.0" % "provided",
      "org.apache.flink" % "flink-connector-files" % "1.20.0" % "provided",
      // Note: S3 connectors will be added when needed for production
      // For now, unified source handles both local Delta tables and S3 (S3 implementation pending)
      
      // Neo4j driver
      "org.neo4j.driver" % "neo4j-java-driver" % "5.15.0",
      
      // REST API framework - Akka HTTP
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.3",
      "com.typesafe.akka" %% "akka-stream" % "2.8.5",
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
      
      // JSON processing (Circe for Ollama)
      "com.softwaremill.sttp.client3" %% "core" % "3.6.2",
      "com.softwaremill.sttp.client3" %% "circe" % "3.6.2",
      "io.circe" %% "circe-generic" % "0.12.3",
      "io.circe" %% "circe-parser" % "0.12.3",
      "io.circe" %% "circe-core" % "0.12.3",
      
      // Configuration management
      "com.typesafe" % "config" % "1.4.3",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "org.slf4j" % "slf4j-api" % "2.0.16",
      
      // Stanford CoreNLP for advanced NLP-based concept extraction
      "edu.stanford.nlp" % "stanford-corenlp" % "4.5.8",
      "edu.stanford.nlp" % "stanford-corenlp" % "4.5.8" classifier "models",
      
      // Spark (for reading Delta tables from HW2)
      // Temporarily not marked as "provided" for testing
      "org.apache.spark" %% "spark-core" % "3.5.3" excludeAll(
        ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-databind"),
        ExclusionRule(organization = "com.fasterxml.jackson.module", name = "jackson-module-scala_2.12")
      ),
      "org.apache.spark" %% "spark-sql" % "3.5.3" excludeAll(
        ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-databind"),
        ExclusionRule(organization = "com.fasterxml.jackson.module", name = "jackson-module-scala_2.12")
      ),
      "io.delta" %% "delta-spark" % "3.3.0" excludeAll(
        ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-databind"),
        ExclusionRule(organization = "com.fasterxml.jackson.module", name = "jackson-module-scala_2.12")
      ),
      
      // Force compatible Jackson version for Delta Lake (2.15.2 required by scala-module 2.15.2)
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.15.2" force(),
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2" force(),
      "com.fasterxml.jackson.core" % "jackson-core" % "2.15.2" force(),
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.15.2" force(),
      
      // Hadoop dependencies (required for Delta Lake to access local file system)
      // Delta Lake uses Hadoop's FileSystem API, so we need these classes available
      "org.apache.hadoop" % "hadoop-common" % "3.3.6" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12"),
        ExclusionRule(organization = "log4j", name = "log4j")
      ),
      "org.apache.hadoop" % "hadoop-client" % "3.3.6" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12"),
        ExclusionRule(organization = "log4j", name = "log4j")
      ),
      
      // Apache Parquet for direct Parquet file reading (bypasses Spark to avoid lambda serialization)
      "org.apache.parquet" % "parquet-avro" % "1.14.3",
      "org.apache.parquet" % "parquet-column" % "1.14.3",
      "org.apache.parquet" % "parquet-hadoop" % "1.14.3" exclude("com.github.luben", "zstd-jni"),
      
      // Stanford CoreNLP for concept extraction (NER, keyphrase extraction)
      "edu.stanford.nlp" % "stanford-corenlp" % "4.5.8",
      "edu.stanford.nlp" % "stanford-corenlp" % "4.5.8" classifier "models",
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "com.typesafe.akka" %% "akka-http-testkit" % "10.5.3" % Test
    ),
    
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat  // Merge service files (needed for Hadoop FileSystem discovery)
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("application.conf") => MergeStrategy.concat
      case x => MergeStrategy.first
    }
  )

resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"

// Handle dependency conflicts - allow eviction warnings instead of errors
ThisBuild / evictionErrorLevel := Level.Warn

