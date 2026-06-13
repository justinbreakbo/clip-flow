$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$StateDir = Join-Path $Root ".clip-flow"
$ConfigPath = Join-Path $StateDir "desktop-config.json"
$RelayLogPath = Join-Path $StateDir "desktop-relay.log"
$AgentLogPath = Join-Path $StateDir "desktop-agent.log"

New-Item -ItemType Directory -Force -Path $StateDir | Out-Null

function Get-DefaultConfig {
    [ordered]@{
        port = 42821
        relayUrl = "http://localhost:42821"
        secret = "change-this-local-secret"
        deviceName = "Windows PC"
        autoAcceptOffers = $true
        startSyncOnLaunch = $true
        clipboardPollMs = 1000
        relayPollTimeoutMs = 25000
    }
}

function Save-Config($Config) {
    $Config | ConvertTo-Json -Depth 8 | Set-Content -Path $ConfigPath -Encoding UTF8
}

function Read-Config {
    if (-not (Test-Path $ConfigPath)) {
        $config = Get-DefaultConfig
        Save-Config $config
        return [pscustomobject]$config
    }

    $loaded = Get-Content -Raw -Path $ConfigPath | ConvertFrom-Json
    $defaults = Get-DefaultConfig

    foreach ($key in $defaults.Keys) {
        if (-not ($loaded.PSObject.Properties.Name -contains $key)) {
            Add-Member -InputObject $loaded -NotePropertyName $key -NotePropertyValue $defaults[$key]
        }
    }

    return $loaded
}

function Resolve-Node {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        throw "Node.js was not found on PATH. Install Node.js 24+ or start this from a shell that has node."
    }

    return $node.Source
}

function Write-ProcessLog($Path, $Line) {
    if ($null -ne $Line -and $Line.Length -gt 0) {
        Add-Content -Path $Path -Encoding UTF8 -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Line)
    }
}

function Convert-ToEnvValue($Value) {
    if ($Value -is [bool]) {
        return $Value.ToString().ToLowerInvariant()
    }

    return [string]$Value
}

function Start-NodeProcess($Name, $ScriptPath, $LogPath, $Environment) {
    $node = Resolve-Node
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $node
    $info.Arguments = "`"$ScriptPath`""
    $info.WorkingDirectory = $Root
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true

    foreach ($key in $Environment.Keys) {
        $info.Environment[$key] = Convert-ToEnvValue $Environment[$key]
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    $null = $process.Start()

    Register-ObjectEvent -InputObject $process -EventName OutputDataReceived -Action {
        Write-ProcessLog $Event.MessageData.LogPath $Event.SourceEventArgs.Data
    } -MessageData @{ LogPath = $LogPath } | Out-Null

    Register-ObjectEvent -InputObject $process -EventName ErrorDataReceived -Action {
        Write-ProcessLog $Event.MessageData.LogPath $Event.SourceEventArgs.Data
    } -MessageData @{ LogPath = $LogPath } | Out-Null

    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()
    Write-ProcessLog $LogPath ("{0} started, pid={1}" -f $Name, $process.Id)

    return $process
}

function Stop-ManagedProcess($Process, $Name, $LogPath) {
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    Write-ProcessLog $LogPath ("Stopping {0}, pid={1}" -f $Name, $Process.Id)
    $Process.Kill()
    $Process.WaitForExit(3000) | Out-Null
}

function Test-Alive($Process) {
    return $null -ne $Process -and -not $Process.HasExited
}

function Read-Health($RelayUrl) {
    try {
        return Invoke-RestMethod -Uri "$RelayUrl/health" -TimeoutSec 2
    } catch {
        return $null
    }
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$script:Config = Read-Config
$script:RelayProcess = $null
$script:AgentProcess = $null
$script:SettingsForm = $null

function Start-ClipFlow {
    $script:Config = Read-Config

    if (-not (Test-Alive $script:RelayProcess)) {
        $script:RelayProcess = Start-NodeProcess `
            -Name "relay" `
            -ScriptPath (Join-Path $Root "src\relay-server.js") `
            -LogPath $RelayLogPath `
            -Environment @{ PORT = $script:Config.port }
    }

    if (-not (Test-Alive $script:AgentProcess)) {
        $script:AgentProcess = Start-NodeProcess `
            -Name "agent" `
            -ScriptPath (Join-Path $Root "src\agent.js") `
            -LogPath $AgentLogPath `
            -Environment @{
                CLIP_FLOW_RELAY = $script:Config.relayUrl
                CLIP_FLOW_SECRET = $script:Config.secret
                CLIP_FLOW_DEVICE_NAME = $script:Config.deviceName
                CLIP_FLOW_AUTO_ACCEPT_OFFERS = $script:Config.autoAcceptOffers
                CLIP_FLOW_CLIPBOARD_POLL_MS = $script:Config.clipboardPollMs
                CLIP_FLOW_RELAY_POLL_TIMEOUT_MS = $script:Config.relayPollTimeoutMs
                CLIP_FLOW_DATA_DIR = $StateDir
            }
    }
}

function Stop-ClipFlow {
    Stop-ManagedProcess $script:AgentProcess "agent" $AgentLogPath
    Stop-ManagedProcess $script:RelayProcess "relay" $RelayLogPath
}

function Restart-ClipFlow {
    Stop-ClipFlow
    Start-ClipFlow
}

function New-Label($Text, $Top) {
    $label = [System.Windows.Forms.Label]::new()
    $label.Text = $Text
    $label.Left = 18
    $label.Top = $Top
    $label.Width = 150
    $label.Height = 24
    $label.TextAlign = [System.Drawing.ContentAlignment]::MiddleLeft
    return $label
}

function New-TextBox($Value, $Top, [bool]$UsePasswordChar = $false) {
    $box = [System.Windows.Forms.TextBox]::new()
    $box.Left = 176
    $box.Top = $Top
    $box.Width = 315
    $box.Height = 24
    $box.Text = [string]$Value
    $box.UseSystemPasswordChar = $UsePasswordChar
    return $box
}

function New-NumberBox($Value, $Top, $Minimum, $Maximum, $Increment) {
    $box = [System.Windows.Forms.NumericUpDown]::new()
    $box.Left = 176
    $box.Top = $Top
    $box.Width = 160
    $box.Height = 24
    $box.Minimum = $Minimum
    $box.Maximum = $Maximum
    $box.Increment = $Increment
    $box.Value = [decimal]$Value
    return $box
}

function Save-SettingsFromForm($Fields, [bool]$RestartAfterSave) {
    $relayUrl = $Fields.RelayUrl.Text.Trim()
    $deviceName = $Fields.DeviceName.Text.Trim()
    $secret = $Fields.Secret.Text

    if ([string]::IsNullOrWhiteSpace($relayUrl)) {
        [System.Windows.Forms.MessageBox]::Show("Relay URL is required.", "Clip Flow", "OK", "Warning") | Out-Null
        return $false
    }

    if ([string]::IsNullOrWhiteSpace($deviceName)) {
        [System.Windows.Forms.MessageBox]::Show("Device name is required.", "Clip Flow", "OK", "Warning") | Out-Null
        return $false
    }

    if ([string]::IsNullOrWhiteSpace($secret)) {
        [System.Windows.Forms.MessageBox]::Show("Secret is required and must match the Android app.", "Clip Flow", "OK", "Warning") | Out-Null
        return $false
    }

    $config = [ordered]@{
        port = [int]$Fields.Port.Value
        relayUrl = $relayUrl
        secret = $secret
        deviceName = $deviceName
        autoAcceptOffers = [bool]$Fields.AutoAccept.Checked
        startSyncOnLaunch = [bool]$Fields.StartOnLaunch.Checked
        clipboardPollMs = [int]$Fields.ClipboardPollMs.Value
        relayPollTimeoutMs = [int]$Fields.RelayPollTimeoutMs.Value
    }

    Save-Config $config
    $script:Config = Read-Config

    if ($RestartAfterSave) {
        Restart-ClipFlow
    }

    return $true
}

function Show-SettingsWindow {
    if ($null -ne $script:SettingsForm -and -not $script:SettingsForm.IsDisposed) {
        $script:SettingsForm.Activate()
        return
    }

    $script:Config = Read-Config

    $form = [System.Windows.Forms.Form]::new()
    $form.Text = "Clip Flow Settings"
    $form.StartPosition = "CenterScreen"
    $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedDialog
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.ClientSize = [System.Drawing.Size]::new(510, 395)

    $title = [System.Windows.Forms.Label]::new()
    $title.Text = "Clip Flow"
    $title.Left = 18
    $title.Top = 16
    $title.Width = 470
    $title.Height = 28
    $title.Font = [System.Drawing.Font]::new($title.Font.FontFamily, 14, [System.Drawing.FontStyle]::Bold)
    $form.Controls.Add($title)

    $subtitle = [System.Windows.Forms.Label]::new()
    $subtitle.Text = "Configure the desktop sync service. Changes are applied after save."
    $subtitle.Left = 18
    $subtitle.Top = 45
    $subtitle.Width = 470
    $subtitle.Height = 22
    $form.Controls.Add($subtitle)

    $port = New-NumberBox $script:Config.port 86 1024 65535 1
    $relayUrl = New-TextBox $script:Config.relayUrl 122
    $secret = New-TextBox $script:Config.secret 158 $true
    $deviceName = New-TextBox $script:Config.deviceName 194
    $clipboardPollMs = New-NumberBox $script:Config.clipboardPollMs 230 250 60000 250
    $relayPollTimeoutMs = New-NumberBox $script:Config.relayPollTimeoutMs 266 1000 30000 1000

    $autoAccept = [System.Windows.Forms.CheckBox]::new()
    $autoAccept.Text = "Auto-accept sensitive offers for local testing"
    $autoAccept.Left = 176
    $autoAccept.Top = 302
    $autoAccept.Width = 315
    $autoAccept.Height = 24
    $autoAccept.Checked = [bool]$script:Config.autoAcceptOffers

    $startOnLaunch = [System.Windows.Forms.CheckBox]::new()
    $startOnLaunch.Text = "Start clipboard sync when Clip Flow opens"
    $startOnLaunch.Left = 176
    $startOnLaunch.Top = 328
    $startOnLaunch.Width = 315
    $startOnLaunch.Height = 24
    $startOnLaunch.Checked = [bool]$script:Config.startSyncOnLaunch

    $form.Controls.Add((New-Label "Local relay port" 84))
    $form.Controls.Add($port)
    $form.Controls.Add((New-Label "Relay URL" 120))
    $form.Controls.Add($relayUrl)
    $form.Controls.Add((New-Label "Shared secret" 156))
    $form.Controls.Add($secret)
    $form.Controls.Add((New-Label "Device name" 192))
    $form.Controls.Add($deviceName)
    $form.Controls.Add((New-Label "Clipboard poll ms" 228))
    $form.Controls.Add($clipboardPollMs)
    $form.Controls.Add((New-Label "Relay long-poll ms" 264))
    $form.Controls.Add($relayPollTimeoutMs)
    $form.Controls.Add($autoAccept)
    $form.Controls.Add($startOnLaunch)

    $fields = @{
        Port = $port
        RelayUrl = $relayUrl
        Secret = $secret
        DeviceName = $deviceName
        ClipboardPollMs = $clipboardPollMs
        RelayPollTimeoutMs = $relayPollTimeoutMs
        AutoAccept = $autoAccept
        StartOnLaunch = $startOnLaunch
    }

    $saveButton = [System.Windows.Forms.Button]::new()
    $saveButton.Text = "Save and Restart"
    $saveButton.Left = 250
    $saveButton.Top = 360
    $saveButton.Width = 120
    $saveButton.Height = 28
    $saveButton.Add_Click({
        if (Save-SettingsFromForm $fields $true) {
            $form.Close()
        }
    }.GetNewClosure())

    $cancelButton = [System.Windows.Forms.Button]::new()
    $cancelButton.Text = "Cancel"
    $cancelButton.Left = 380
    $cancelButton.Top = 360
    $cancelButton.Width = 110
    $cancelButton.Height = 28
    $cancelButton.Add_Click({ $form.Close() }.GetNewClosure())

    $showSecret = [System.Windows.Forms.CheckBox]::new()
    $showSecret.Text = "Show"
    $showSecret.Left = 408
    $showSecret.Top = 184
    $showSecret.Width = 82
    $showSecret.Height = 20
    $showSecret.Add_CheckedChanged({ $secret.UseSystemPasswordChar = -not $showSecret.Checked }.GetNewClosure())

    $form.Controls.Add($showSecret)
    $form.Controls.Add($saveButton)
    $form.Controls.Add($cancelButton)
    $form.AcceptButton = $saveButton
    $form.CancelButton = $cancelButton

    $script:SettingsForm = $form
    $form.Show()
}

function New-MenuItem($Text, $Handler, [bool]$Enabled = $true) {
    $item = [System.Windows.Forms.ToolStripMenuItem]::new($Text)
    $item.Enabled = $Enabled
    if ($null -ne $Handler) {
        $item.Add_Click($Handler)
    }
    return $item
}

$notify = [System.Windows.Forms.NotifyIcon]::new()
$notify.Icon = [System.Drawing.SystemIcons]::Application
$notify.Text = "Clip Flow"
$notify.Visible = $true

$menu = [System.Windows.Forms.ContextMenuStrip]::new()
$statusItem = New-MenuItem "Starting..." $null $false
$startItem = New-MenuItem "Start Sync" { Start-ClipFlow }
$stopItem = New-MenuItem "Stop Sync" { Stop-ClipFlow }
$restartItem = New-MenuItem "Restart" { Restart-ClipFlow }
$settingsItem = New-MenuItem "Settings..." { Show-SettingsWindow }
$configItem = New-MenuItem "Open Config File" { Start-Process notepad.exe -ArgumentList "`"$ConfigPath`"" }
$logsItem = New-MenuItem "Open Logs Folder" { Start-Process explorer.exe -ArgumentList "`"$StateDir`"" }
$readmeItem = New-MenuItem "Open README" { Start-Process notepad.exe -ArgumentList "`"$(Join-Path $Root 'README.md')`"" }
$exitItem = New-MenuItem "Exit" {
    Stop-ClipFlow
    $notify.Visible = $false
    [System.Windows.Forms.Application]::Exit()
}

[void]$menu.Items.Add($statusItem)
[void]$menu.Items.Add([System.Windows.Forms.ToolStripSeparator]::new())
[void]$menu.Items.Add($startItem)
[void]$menu.Items.Add($stopItem)
[void]$menu.Items.Add($restartItem)
[void]$menu.Items.Add([System.Windows.Forms.ToolStripSeparator]::new())
[void]$menu.Items.Add($settingsItem)
[void]$menu.Items.Add($configItem)
[void]$menu.Items.Add($logsItem)
[void]$menu.Items.Add($readmeItem)
[void]$menu.Items.Add([System.Windows.Forms.ToolStripSeparator]::new())
[void]$menu.Items.Add($exitItem)
$notify.ContextMenuStrip = $menu
$notify.Add_DoubleClick({ Show-SettingsWindow })

$timer = [System.Windows.Forms.Timer]::new()
$timer.Interval = 2000
$timer.Add_Tick({
    $relayAlive = Test-Alive $script:RelayProcess
    $agentAlive = Test-Alive $script:AgentProcess
    $health = if ($relayAlive) { Read-Health $script:Config.relayUrl } else { $null }

    if ($relayAlive -and $agentAlive -and $null -ne $health) {
        $text = "Running: $($health.activeDevices) active / $($health.devices) registered"
    } elseif ($relayAlive -and $agentAlive) {
        $text = "Starting: relay and agent are running"
    } elseif ($relayAlive) {
        $text = "Partial: relay running, agent stopped"
    } elseif ($agentAlive) {
        $text = "Partial: agent running, relay stopped"
    } else {
        $text = "Stopped"
    }

    $statusItem.Text = $text
    $notify.Text = if ($text.Length -gt 63) { $text.Substring(0, 63) } else { $text }
})

try {
    if ($script:Config.startSyncOnLaunch) {
        Start-ClipFlow
        $notify.ShowBalloonTip(1500, "Clip Flow", "Clipboard sync started. Right-click the tray icon for controls.", [System.Windows.Forms.ToolTipIcon]::Info)
    } else {
        $notify.ShowBalloonTip(1500, "Clip Flow", "Clip Flow is open. Start sync from the tray menu.", [System.Windows.Forms.ToolTipIcon]::Info)
    }

    $timer.Start()
    [System.Windows.Forms.Application]::Run()
} finally {
    $timer.Stop()
    Stop-ClipFlow
    $notify.Dispose()
}
