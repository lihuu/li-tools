#!/usr/bin/env node

const path = require('path');
const { ensureBinary, runBinary } = require('../lib/binary');
const pkg = require('../package.json');

(async () => {
  try {
    const binaryPath = await ensureBinary('gpgkit', pkg.version, path.join(__dirname, '..'));
    runBinary(binaryPath, process.argv.slice(2));
  } catch (err) {
    console.error(`Error: ${err.message}`);
    process.exit(1);
  }
})();
