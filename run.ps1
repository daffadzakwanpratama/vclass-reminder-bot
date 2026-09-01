# Script untuk menjalankan VClass Reminder Bot
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
$mvnCmd = Join-Path $PSScriptRoot ".maven\apache-maven-3.9.8\bin\mvn.cmd"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "      VClass Reminder Bot Starting       " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

& $mvnCmd spring-boot:run
