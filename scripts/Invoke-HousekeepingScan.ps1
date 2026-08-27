<#
.SYNOPSIS
    Read-only housekeeping audit for the Backlogium repository.

.DESCRIPTION
    Deterministic companion scanner for the 'housekeeping' skill. Reports workspace
    clutter (root-level logs, scratch dirs), .gitignore coverage gaps, untracked
    non-ignored files, locally merged branches safe to prune, and stale worktrees.
    Performs NO mutations whatsoever - safe to run at any time.

.PARAMETER RepoRoot
    Path to the repository. Defaults to the current location.

.PARAMETER MainBranch
    The integration branch merged-ness is measured against. Defaults to 'master'.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Get-Location).Path,
    [string]$MainBranch = 'master'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path (Join-Path $RepoRoot '.git'))) {
    Write-Error "Not a git repository: $RepoRoot"
    exit 1
}

function Format-Size([long]$Bytes) {
    if ($Bytes -ge 1MB) { return '{0:N1} MB' -f ($Bytes / 1MB) }
    if ($Bytes -ge 1KB) { return '{0:N1} KB' -f ($Bytes / 1KB) }
    return "$Bytes B"
}

# Runs git expecting a possible non-zero exit without triggering
# $ErrorActionPreference = 'Stop' on native stderr noise.
function Test-GitOk {
    param([string[]]$GitArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & git @GitArgs 2>$null | Out-Null } catch { }
    finally { $ErrorActionPreference = $prev }
    return ($LASTEXITCODE -eq 0)
}

function Get-Size([string]$Path) {
    if (Test-Path $Path -PathType Container) {
        $files = Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue
        return ($files | Measure-Object Length -Sum).Sum
    }
    if (Test-Path $Path) {
        return (Get-Item -LiteralPath $Path -Force).Length
    }
    return 0
}

Push-Location $RepoRoot
try {
    Write-Host "=== Housekeeping scan: $RepoRoot ==="
    Write-Host ""

    # --- 1. Workspace clutter -------------------------------------------------
    Write-Host '[Workspace clutter]'
    $clutterPaths = @()
    $clutterPaths += Get-ChildItem -Path $RepoRoot -File -Filter '*.log' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
    foreach ($dirName in @('.scratch_screens') + (Get-ChildItem -Path $RepoRoot -Directory -Filter '.gradle-project-cache*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)) {
        $p = Join-Path $RepoRoot $dirName
        if (Test-Path $p) { $clutterPaths += $p }
    }

    $trackedDeletedTotal = 0
    if (-not $clutterPaths) {
        Write-Host '  none found'
    } else {
        foreach ($item in $clutterPaths) {
            $rel = $item.Substring($RepoRoot.Length).TrimStart('\', '/')
            $ignored = Test-GitOk @('check-ignore', '-q', $rel)
            $isTracked = Test-GitOk @('ls-files', '--error-unmatch', $rel)
            $tag = if ($isTracked) { 'TRACKED (never delete)' } elseif ($ignored) { 'ignored-artifact' } else { 'GITIGNORE GAP' }
            if (-not $ignored) { $script:gapFound = $true }
            Write-Host ('  {0,-8} {1,10}  {2}' -f "[$tag]", (Format-Size (Get-Size $item)), $rel)
        }
    }
    Write-Host ''

    # --- 2. Untracked non-ignored files --------------------------------------
    Write-Host '[Untracked non-ignored files]'
    $untracked = & git status --porcelain | Where-Object { $_ -match '^\?\?' }
    if ($untracked) {
        $untracked | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host '  none'
    }
    Write-Host ''

    # --- 3. Gitignore coverage ------------------------------------------------
    Write-Host '[.gitignore coverage]'
    $probes = @('.scratch_screens/', '.gradle-project-cache*/', '*.log')
    foreach ($probe in $probes) {
        $hit = Select-String -Path (Join-Path $RepoRoot '.gitignore') -Pattern ([regex]::Escape($probe.TrimEnd('/'))) -Quiet -ErrorAction SilentlyContinue
        $state = if ($probe -eq '*.log') { if ($hit) { 'covered' } else { 'GAP' } } else { if ($hit) { 'covered' } else { 'GAP (propose adding)' } }
        Write-Host ('  {0,-30} {1}' -f $probe, $state)
    }
    Write-Host ''

    # --- 4. Merged local branches ---------------------------------------------
    Write-Host "[Local branches merged into '$MainBranch' (candidates, confirm before delete)]"
    $protected = '^(master|main)$|^(release|hotfix|prod)/'
    $current = (& git rev-parse --abbrev-ref HEAD)
    $merged = & git branch --merged $MainBranch --format '%(refname:short)' |
        Where-Object { $_ -ne $current -and $_ -notmatch $protected }
    if ($merged) {
        $merged | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host '  none'
    }
    Write-Host ''

    # --- 5. Worktrees ----------------------------------------------------------
    Write-Host '[Worktrees]'
    $wtOutput = & git worktree list --porcelain
    $entries = @{}
    foreach ($line in $wtOutput) {
        if ($line -like 'worktree *') { $wtPath = $line.Substring(9); $entries[$wtPath] = @{ branch = '?'; exists = $false } }
        elseif ($line -like 'branch *') { $entries[$wtPath].branch = $line.Substring(7) }
    }
    if (-not $entries.Count) {
        Write-Host '  none'
    } else {
        foreach ($wt in $entries.Keys) {
            $exists = Test-Path $wt
            $status = if ($exists) { 'ok' } else { 'MISSING DIRECTORY (stale)' }
            Write-Host ('  {0,-10} {1}  -> {2}' -f "[$status]", $wt, $entries[$wt].branch)
        }
    }
    Write-Host ''
    Write-Host 'Scan complete (no changes made).' -ForegroundColor Green
} finally {
    Pop-Location
}
