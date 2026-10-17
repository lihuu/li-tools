#!/usr/bin/env node

const path = require('path');
const { ensureBinary } = require('../lib/binary');
const pkg = require('../package.json');

(async () => {
  try {
    await ensureBinary('gpgkit', pkg.version, path.join(__dirname, '..'));
  } catch (err) {
    process.stderr.write(`Note: Postinstall binary download deferred (${err.message}). Will download on first run.\n`);
  }
})();
