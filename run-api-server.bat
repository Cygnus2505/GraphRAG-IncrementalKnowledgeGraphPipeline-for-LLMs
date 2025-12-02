@echo off
REM Run the GraphRAG REST API Server
REM This starts the API server on http://localhost:8080

echo ======================================
echo Starting GraphRAG REST API Server
echo ======================================
echo.
echo Make sure Neo4j is running first!
echo.
echo Server will be available at:
echo   http://localhost:8080
echo.
echo API Documentation:
echo   http://localhost:8080/
echo.
echo Press Ctrl+C to stop the server
echo ======================================
echo.

cd %~dp0
sbt "runMain graphrag.api.ApiServer"





