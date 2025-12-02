ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.18"

lazy val root = (project in file("."))
  .settings(
    name := "phase1-delta-export",
    
    // Enable forking to use JVM options
    Compile / run / fork := true,
    
    // JVM options
    Compile / run / javaOptions ++= Seq(
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
    ),
    
    libraryDependencies ++= Seq(
      // Spark dependencies for reading Delta tables
      "org.apache.spark" %% "spark-core" % "3.5.3" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
      ),
      "org.apache.spark" %% "spark-sql" % "3.5.3" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
      ),
      
      // Delta Lake for reading Delta tables from HW2
      "io.delta" %% "delta-spark" % "3.3.0",
      
      // Hadoop dependencies for file system access
      "org.apache.hadoop" % "hadoop-common" % "3.3.6" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12"),
        ExclusionRule(organization = "log4j", name = "log4j")
      ),
      "org.apache.hadoop" % "hadoop-client" % "3.3.6" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12"),
        ExclusionRule(organization = "log4j", name = "log4j")
      ),
      
      // AWS S3 support (optional, for S3 output)
      "org.apache.hadoop" % "hadoop-aws" % "3.3.6" excludeAll(
        ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
      ),
      
      // Configuration
      "com.typesafe" % "config" % "1.4.3",
      
      // Command-line parsing
      "com.github.scopt" %% "scopt" % "4.1.0",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.5.6",
      "org.slf4j" % "slf4j-api" % "2.0.16",
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    
    // Assembly settings for creating fat JAR
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf") => MergeStrategy.concat
      case "application.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    }
  )

resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"

