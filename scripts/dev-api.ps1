#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Access', 'Detection')][string]$Scenario = 'Access',
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$TokenFile = (Join-Path $PSScriptRoot '../.local-dev/tokens.json')
)
$ErrorActionPreference = 'Stop'
$tokens = Get-Content -LiteralPath $TokenFile -Raw | ConvertFrom-Json
$a1 = '30000000-0000-4000-8000-000000000001'
$a2 = '30000000-0000-4000-8000-000000000002'
$b1 = '30000000-0000-4000-8000-000000000003'
$partnerY = '20000000-0000-4000-8000-000000000002'

function Send([string]$Actor, [string]$Method, [string]$Path, [int]$Expected, $Body = $null) {
    $options = @{
        Uri = $BaseUrl.TrimEnd('/') + '/api/v1' + $Path
        Method = $Method
        Headers = @{ Authorization = 'Bearer ' + $tokens.$Actor }
        SkipHttpErrorCheck = $true
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json'
        $options.Body = $Body | ConvertTo-Json -Compress
    }
    $response = Invoke-WebRequest @options
    Write-Host "$Actor $Method $Path -> $([int]$response.StatusCode) (expected $Expected)"
    Write-Host ('requestId=' + ($response.Headers['X-Request-Id'] -join ''))
    if ($response.Content) {
        Write-Host $response.Content
    }
    if ([int]$response.StatusCode -ne $Expected) {
        throw '예상 응답과 다릅니다. 토큰 만료·개발 Fixture·현재 권한을 확인하세요.'
    }
    if ($response.Content) {
        return $response.Content | ConvertFrom-Json
    }
}

if ($Scenario -eq 'Detection') {
    foreach ($account in @($a1, $a2, $b1, $a1, $a2)) {
        $null = Send partner-y GET "/accounts/$account" 404
    }
    Write-Host '세 계좌에 5회 요청했습니다. DB에서 감사·알림·억제 상태를 확인하세요.'
    return
}

$null = Send customer-a GET "/accounts/$a1" 200
$null = Send customer-b GET "/accounts/$a1" 404
$null = Send partner-x GET "/accounts/$a1" 200
$null = Send partner-x GET "/accounts/$a1/transactions" 404
$null = Send partner-y GET "/accounts/$a2/transactions" 404
$grant = Send customer-a POST "/accounts/$a2/grants" 201 @{ partnerId = $partnerY; permission = 'TRANSACTION_READ' }
$grantId = ([guid]$grant.grantId).ToString()
try {
    $null = Send partner-y GET "/accounts/$a2/transactions" 200
} finally {
    $null = Send customer-a DELETE "/accounts/$a2/grants/$grantId" 204
}
$null = Send partner-y GET "/accounts/$a2/transactions" 404
$null = Send customer-a DELETE "/accounts/$a2/grants/$grantId" 204
