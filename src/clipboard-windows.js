import { spawn } from "node:child_process";

function runPowerShell(command, input = "") {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-Command", command], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true
    });

    const stdout = [];
    const stderr = [];

    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(Buffer.concat(stderr).toString("utf8") || `PowerShell exited ${code}`));
        return;
      }

      resolve(Buffer.concat(stdout).toString("utf8"));
    });

    child.stdin.end(input);
  });
}

export async function getClipboardText() {
  const encoded = await runPowerShell(`
    $text = Get-Clipboard -Raw -Format Text 2>$null
    if ($null -eq $text) { $text = "" }
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
  `);

  return Buffer.from(encoded.trim(), "base64").toString("utf8").replace(/\r?\n$/, "");
}

export async function setClipboardText(value) {
  if (!value) {
    return;
  }

  const encoded = Buffer.from(value, "utf8").toString("base64");
  await runPowerShell(`
    $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("${encoded}"))
    Set-Clipboard -Value $text
  `);
}
