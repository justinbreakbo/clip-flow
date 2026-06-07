import { spawn } from "node:child_process";

function runCommand(command, args = [], input = "") {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"]
    });

    const stdout = [];
    const stderr = [];

    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(Buffer.concat(stderr).toString("utf8") || `${command} exited ${code}`));
        return;
      }

      resolve(Buffer.concat(stdout).toString("utf8"));
    });

    child.stdin.end(input);
  });
}

export async function getAndroidClipboardText() {
  const value = await runCommand("termux-clipboard-get");
  return value.replace(/\r?\n$/, "");
}

export async function setAndroidClipboardText(value) {
  await runCommand("termux-clipboard-set", [], value);
}
