[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ViewerUrl,
    [Parameter(Mandatory=$true)][AllowEmptyString()][string]$Text
)
$ErrorActionPreference = 'Stop'
$hearlensUri = [Uri]$ViewerUrl
$hearlensAddress = $null
if ($hearlensUri.Scheme -ne 'http' -or $hearlensUri.Port -ne 18766 -or $hearlensUri.UserInfo -or
    $hearlensUri.AbsolutePath -ne '/' -or $hearlensUri.Query -or $hearlensUri.Fragment -notmatch '^#[a-f0-9]{32}$' -or
    -not [Net.IPAddress]::TryParse($hearlensUri.Host, [ref]$hearlensAddress)) { throw 'Use the complete viewer URL from the phone.' }
$hearlensBytes = $hearlensAddress.GetAddressBytes()
if ($hearlensBytes.Length -ne 4 -or -not ($hearlensBytes[0] -eq 10 -or
    ($hearlensBytes[0] -eq 192 -and $hearlensBytes[1] -eq 168) -or
    ($hearlensBytes[0] -eq 172 -and $hearlensBytes[1] -ge 16 -and $hearlensBytes[1] -le 31))) { throw 'Only a private local-network phone address is accepted.' }
if ($Text.Length -gt 120) { throw 'Subtitle is limited to 120 UTF-16 code units.' }
$hearlensBody = @{text=$Text} | ConvertTo-Json -Compress
Invoke-RestMethod -Uri ($hearlensUri.GetLeftPart([UriPartial]::Authority) + '/subtitle') -Method Post `
    -Headers @{'X-HearLens-Key'=$hearlensUri.Fragment.Substring(1)} -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($hearlensBody)) -TimeoutSec 5
