const os = require('os');
const path = require('path');
const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const { spawn } = require('child_process');

const CHECKSUMS = {
  gpgkit: {
    'darwin-arm64': '5f54e68597470d9cddaa16ba58319a0423018dda60f0086a39fe38bc344c10af',
    'linux-x64': 'b685a185e3a4fe7af9c0357c66e14d126234e39a0a4a9979a0a11dd8813d6775',
    'win32-x64': '426ba380d849d60e75761b328e41534c8257db9cda919495d1f45a213bb821f1',
  },
  certkit: {
    'darwin-arm64': 'afb81acf5ac70095153c3e249b430ac6bfca580c0050a17fee0fc0ca672b771b',
    'linux-x64': '7ba07365e6a96bc49da636d5a5bda587903b8da5232c3c724aeaecaac9fb561c',
    'win32-x64': '34afa38685f5753dbf40594fc1de78f9cca7d70362a57b693a82f7aa747812c9',
  }
};

const ASSET_NAMES = {
  'darwin-arm64': (tool) => `${tool}-darwin-arm64`,
  'linux-x64': (tool) => `${tool}-linux-amd64`,
  'win32-x64': (tool) => `${tool}-windows-amd64.exe`,
};

function getPlatformKey() {
  const platform = process.platform;
  const arch = process.arch;
  const key = `${platform}-${arch}`;
  if (!ASSET_NAMES[key]) {
    throw new Error(`Unsupported platform/architecture: ${key}. Supported: darwin-arm64, linux-x64, win32-x64.`);
  }
  return key;
}

function getDownloadUrl(tool, version, assetName) {
  return `https://github.com/lihuu/li-tools/releases/download/v${version}/${assetName}`;
}

function downloadFile(url, destPath) {
  return new Promise((resolve, reject) => {
    function get(currentUrl, redirectCount = 0) {
      if (redirectCount > 10) {
        return reject(new Error('Too many redirects downloading binary'));
      }
      https.get(currentUrl, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return get(res.headers.location, redirectCount + 1);
        }
        if (res.statusCode !== 200) {
          return reject(new Error(`Failed to download binary from ${currentUrl}: HTTP ${res.statusCode}`));
        }
        const fileStream = fs.createWriteStream(destPath);
        res.pipe(fileStream);
        fileStream.on('finish', () => {
          fileStream.close(resolve);
        });
        fileStream.on('error', (err) => {
          fs.unlink(destPath, () => reject(err));
        });
      }).on('error', reject);
    }
    get(url);
  });
}

function verifyChecksum(filePath, expectedSha256) {
  const hash = crypto.createHash('sha256');
  const data = fs.readFileSync(filePath);
  hash.update(data);
  const actualSha256 = hash.digest('hex');
  if (actualSha256 !== expectedSha256) {
    throw new Error(`Checksum mismatch for ${filePath}!\nExpected: ${expectedSha256}\nActual:   ${actualSha256}`);
  }
}

function getBinaryStorageDir(packageDir) {
  const localDir = path.join(packageDir, 'bin-dist');
  try {
    fs.mkdirSync(localDir, { recursive: true });
    fs.accessSync(localDir, fs.constants.W_OK);
    return localDir;
  } catch {
    let cacheBase;
    if (process.platform === 'win32') {
      cacheBase = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
    } else {
      cacheBase = process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache');
    }
    const userDir = path.join(cacheBase, 'li-tools', 'bin');
    fs.mkdirSync(userDir, { recursive: true });
    return userDir;
  }
}

async function ensureBinary(tool, version, packageDir) {
  const key = getPlatformKey();
  const assetName = ASSET_NAMES[key](tool);
  const storageDir = getBinaryStorageDir(packageDir);
  const targetBinary = path.join(storageDir, process.platform === 'win32' ? `${tool}.exe` : tool);

  if (fs.existsSync(targetBinary)) {
    return targetBinary;
  }

  const expectedSha256 = CHECKSUMS[tool][key];
  const url = getDownloadUrl(tool, version, assetName);
  const tempPath = `${targetBinary}.tmp.${Date.now()}`;

  process.stderr.write(`[${tool}] Downloading native binary for ${key} from GitHub Releases...\n`);
  await downloadFile(url, tempPath);
  verifyChecksum(tempPath, expectedSha256);

  fs.chmodSync(tempPath, 0o755);
  fs.renameSync(tempPath, targetBinary);
  process.stderr.write(`[${tool}] Download complete and verified.\n`);

  return targetBinary;
}

function runBinary(targetBinary, args) {
  const child = spawn(targetBinary, args, {
    stdio: 'inherit',
    env: process.env,
  });

  const forwardSignal = (signal) => {
    try {
      child.kill(signal);
    } catch {}
  };

  process.on('SIGINT', () => forwardSignal('SIGINT'));
  process.on('SIGTERM', () => forwardSignal('SIGTERM'));

  child.on('exit', (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
    } else {
      process.exit(code !== null ? code : 1);
    }
  });

  child.on('error', (err) => {
    console.error(`Failed to start ${targetBinary}:`, err);
    process.exit(1);
  });
}

module.exports = {
  ensureBinary,
  runBinary,
};
