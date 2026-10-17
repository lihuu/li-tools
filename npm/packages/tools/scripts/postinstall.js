#!/usr/bin/env node

const path = require('path');
const { ensureBinary } = require('../lib/binary');
const pkg = require('../package.json');

(async () => {
  try {
    await Promise.allSettled([
      ensureBinary('gpgkit', pkg.version, path.join(__dirname, '..')),
      ensureBinary('certkit', pkg.version, path.join(__dirname, '..')),
    ]);
  } catch (err) {
    process.stderr.write(`Note: Postinstall binary download deferred (${err.message}). Will download on first run.\n`);
  }
})();
