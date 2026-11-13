// File-based drop-in replacement for keytar, used because the native keytar
// module no longer compiles on modern Node.js. Credentials are stored as
// JSON in the user's home directory instead of the OS credential vault.
const fs = require("fs");
const os = require("os");
const path = require("path");

const storeFile = path.join(os.homedir(), ".fitbit-sdk-cli-credentials.json");

function readStore() {
  try {
    return JSON.parse(fs.readFileSync(storeFile, "utf8"));
  } catch (ex) {
    return {};
  }
}

function writeStore(store) {
  fs.writeFileSync(storeFile, JSON.stringify(store, null, 2), { mode: 0o600 });
}

function entryKey(service, account) {
  return service + ":" + account;
}

async function getPassword(service, account) {
  const value = readStore()[entryKey(service, account)];
  return value === undefined ? null : value;
}

async function setPassword(service, account, password) {
  const store = readStore();
  store[entryKey(service, account)] = password;
  writeStore(store);
}

async function deletePassword(service, account) {
  const store = readStore();
  const key = entryKey(service, account);
  const existed = Object.prototype.hasOwnProperty.call(store, key);
  delete store[key];
  writeStore(store);
  return existed;
}

async function findPassword(service) {
  const prefix = service + ":";
  const store = readStore();
  for (const key of Object.keys(store)) {
    if (key.startsWith(prefix)) return store[key];
  }
  return null;
}

async function findCredentials(service) {
  const prefix = service + ":";
  const store = readStore();
  return Object.keys(store)
    .filter((key) => key.startsWith(prefix))
    .map((key) => ({ account: key.slice(prefix.length), password: store[key] }));
}

module.exports = {
  getPassword,
  setPassword,
  deletePassword,
  findPassword,
  findCredentials,
};
