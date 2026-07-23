<#
.SYNOPSIS
    Tags the current commit with the next semver version and pushes the tag to origin.

.DESCRIPTION
    Finds the latest vX.Y.Z tag reachable in the repo, bumps it (patch by default),
    creates an annotated tag on HEAD, and pushes it to origin.

.PARAMETER Major
    Bump the major version (resets minor and patch to 0).

.PARAMETER Minor
    Bump the minor version (resets patch to 0).

.PARAMETER Patch
    Bump the patch version. This is the default when no switch is given.

.PARAMETER Message
    Custom annotated tag message. Defaults to "Release <tag>".

.PARAMETER DryRun
    Compute and print the next tag without creating or pushing it.

.EXAMPLE
    ./scripts/bump-tag.ps1
    Bumps the patch version and pushes, e.g. v1.2.3 -> v1.2.4

.EXAMPLE
    ./scripts/bump-tag.ps1 -Minor
    e.g. v1.2.3 -> v1.3.0
#>

[CmdletBinding()]
param(
    [switch]$Major,
    [switch]$Minor,
    [switch]$Patch,
    [string]$Message,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Assert-GitRepo {
    git rev-parse --is-inside-work-tree *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Not inside a git repository."
    }
}

Assert-GitRepo

git fetch --tags --quiet
if ($LASTEXITCODE -ne 0) {
    throw "git fetch --tags failed."
}

$latestTag = git tag --list 'v*.*.*' --sort=-v:refname | Select-Object -First 1

if (-not $latestTag) {
    $currentMajor = 0
    $currentMinor = 0
    $currentPatch = 0
}
else {
    if ($latestTag -notmatch '^v(\d+)\.(\d+)\.(\d+)$') {
        throw "Latest tag '$latestTag' does not match expected vX.Y.Z format."
    }
    $currentMajor = [int]$Matches[1]
    $currentMinor = [int]$Matches[2]
    $currentPatch = [int]$Matches[3]
}

if ($Major) {
    $currentMajor++
    $currentMinor = 0
    $currentPatch = 0
}
elseif ($Minor) {
    $currentMinor++
    $currentPatch = 0
}
else {
    if (-not $latestTag) {
        $currentMinor = 1
    }
    else {
        $currentPatch++
    }
}

$newTag = "v$currentMajor.$currentMinor.$currentPatch"

$currentCommit = git rev-parse --short HEAD
$currentBranch = git rev-parse --abbrev-ref HEAD

Write-Host "Latest tag: $(if ($latestTag) { $latestTag } else { '(none)' })"
Write-Host "Next tag:   $newTag"
Write-Host "On commit:  $currentCommit ($currentBranch)"

if (git status --porcelain) {
    Write-Warning "Working tree has uncommitted changes. The tag will still be created on the current HEAD commit ($currentCommit), not on your uncommitted changes."
}

$existingTag = git tag --list $newTag
if ($existingTag) {
    $existingTagCommit = git rev-list -n 1 $newTag
    throw "Tag '$newTag' already exists (commit $existingTagCommit). Refusing to overwrite."
}

if ($DryRun) {
    Write-Host "Dry run: not creating or pushing '$newTag'."
    return
}

if (-not $Message) {
    $Message = "Release $newTag"
}

git tag -a $newTag -m $Message
if ($LASTEXITCODE -ne 0) {
    throw "git tag failed."
}

git push origin $newTag
if ($LASTEXITCODE -ne 0) {
    throw "git push origin $newTag failed. The local tag was created; delete it with 'git tag -d $newTag' if you need to retry."
}

Write-Host "Tagged and pushed $newTag."
