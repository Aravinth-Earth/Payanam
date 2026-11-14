#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Resolves a working Python 3 interpreter for build preflight checks.
# `python3` is tried before `python`: on Windows `python` may be the Store app-execution alias.
function Get-PayanamPython
{
    foreach ($candidate in @("python3", "python"))
    {
        $commands = @(Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue)
        if ($commands.Count -eq 0) { continue }
        $executable = $commands[0].Path
        & $executable -c "import sys; raise SystemExit(0 if sys.version_info.major == 3 else 1)" 2>$null
        if ($LASTEXITCODE -eq 0) { return $executable }
    }
    throw "Python 3 is required for build preflight checks; python3 or python must be on PATH."
}
