$ErrorActionPreference = "Stop"

function Build-NativeLambda {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Module,

        [Parameter(Mandatory = $true)]
        [string] $MainClass
    )

    $outputDirectory = Join-Path $PSScriptRoot "$Module/target/native"
    $artifactPath = Join-Path $outputDirectory "lambda-native.zip"
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    Remove-Item -LiteralPath $artifactPath -Force -ErrorAction SilentlyContinue

    docker build `
        --file (Join-Path $PSScriptRoot "Dockerfile.native-lambda") `
        --build-arg "MODULE=$Module" `
        --build-arg "MAIN_CLASS=$MainClass" `
        --target artifact `
        --output "type=local,dest=$outputDirectory" `
        $PSScriptRoot

    if ($LASTEXITCODE -ne 0) {
        throw "Native build failed for $Module"
    }

    Write-Host "Created $artifactPath"
}

Build-NativeLambda `
    -Module "api-lambda" `
    -MainClass "io.micronaut.function.aws.runtime.APIGatewayV2HTTPEventMicronautLambdaRuntime"

Build-NativeLambda `
    -Module "match-sync-lambda" `
    -MainClass "com.valstats.queue.RefreshQueueLambdaRuntime"
