$mavenVersion = "3.9.8"
$mavenDir = Join-Path $PSScriptRoot ".maven"
$mavenZip = Join-Path $PSScriptRoot "apache-maven.zip"
$mvnCmd = Join-Path $mavenDir "apache-maven-$mavenVersion\bin\mvn.cmd"

if (!(Test-Path $mvnCmd)) {
    Write-Host "Downloading Apache Maven $mavenVersion..."
    $url = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
    Invoke-WebRequest -Uri $url -OutFile $mavenZip -UseBasicParsing
    
    Write-Host "Extracting Maven..."
    if (!(Test-Path $mavenDir)) {
        New-Item -ItemType Directory -Path $mavenDir -Force | Out-Null
    }
    Expand-Archive -Path $mavenZip -DestinationPath $mavenDir -Force
    Remove-Item $mavenZip -Force
    Write-Host "Maven setup complete!"
} else {
    Write-Host "Maven already installed at $mvnCmd"
}

# Run maven command passed as arguments
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
& $mvnCmd @args
