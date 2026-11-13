import document from "document";
import { Accelerometer } from "accelerometer";
import { battery } from "power";
import { display } from "display";

// Hit detection tuning. The T-Watch version used a delta of 650 raw BMA423
// counts (~0.63 g) between consecutive ~50 Hz samples; 6 m/s^2 is the
// equivalent for the Fitbit accelerometer, which reports in m/s^2.
const HIT_DELTA_THRESHOLD = 6.0;
const HIT_REFRACTORY_MS = 1000;
const SET_GAP_MS = 5000;
const ACCEL_FREQUENCY = 30;

const setHitsText = document.getElementById("setHits");
const totalHitsText = document.getElementById("totalHits");
const maxSetHitsText = document.getElementById("maxSetHits");
const elapsedText = document.getElementById("elapsed");
const startButton = document.getElementById("startButton");
const startButtonText = document.getElementById("startButtonText");
const batteryFill = document.getElementById("batteryFill");

let countingEnabled = false;
let setHitCount = 0;
let totalHitCount = 0;
let maxSetHitCount = 0;
let prevX = null;
let prevY = null;
let prevZ = null;
let nextHitTime = 0;
let lastHitAt = 0;
let hasHitInSet = false;
let timerStartedAt = 0;

display.autoOff = false;

const accel = new Accelerometer({ frequency: ACCEL_FREQUENCY, batch: 1 });
accel.addEventListener("reading", onAccelReading);

function onAccelReading() {
  const x = accel.readings.x[0];
  const y = accel.readings.y[0];
  const z = accel.readings.z[0];
  const now = accel.readings.timestamp[0];

  if (prevX !== null) {
    const delta = Math.abs(x - prevX) + Math.abs(y - prevY) + Math.abs(z - prevZ);
    if (delta > HIT_DELTA_THRESHOLD && now >= nextHitTime) {
      if (!hasHitInSet || now - lastHitAt >= SET_GAP_MS) {
        setHitCount = 0;
      }
      setHitCount += 1;
      totalHitCount += 1;
      maxSetHitCount = Math.max(maxSetHitCount, setHitCount);
      lastHitAt = now;
      hasHitInSet = true;
      nextHitTime = now + HIT_REFRACTORY_MS;
      setHitsText.text = String(setHitCount);
      totalHitsText.text = String(totalHitCount);
      maxSetHitsText.text = String(maxSetHitCount);
    }
  }
  prevX = x;
  prevY = y;
  prevZ = z;
}

function setCounting(enabled) {
  countingEnabled = enabled;
  startButtonText.text = enabled ? "STOP" : "START";
  if (enabled) {
    timerStartedAt = Date.now();
    setHitCount = 0;
    totalHitCount = 0;
    maxSetHitCount = 0;
    lastHitAt = 0;
    hasHitInSet = false;
    prevX = null;
    nextHitTime = 0;
    elapsedText.text = "Elapsed: 00h:00m:00s";
    setHitsText.text = "0";
    totalHitsText.text = "0";
    maxSetHitsText.text = "0";
    accel.start();
  } else {
    accel.stop();
  }
}

startButton.addEventListener("click", () => setCounting(!countingEnabled));
startButtonText.addEventListener("click", () => setCounting(!countingEnabled));

function pad2(n) {
  return (n < 10 ? "0" : "") + n;
}

setInterval(() => {
  if (!countingEnabled) {
    return;
  }
  const elapsedSeconds = Math.floor((Date.now() - timerStartedAt) / 1000);
  const hours = Math.floor(elapsedSeconds / 3600);
  const minutes = Math.floor((elapsedSeconds % 3600) / 60);
  const seconds = elapsedSeconds % 60;
  elapsedText.text = `Elapsed: ${pad2(hours)}h:${pad2(minutes)}m:${pad2(seconds)}s`;
}, 250);

function updateBattery() {
  const level = Math.max(0, Math.min(100, Math.floor(battery.chargeLevel)));
  batteryFill.width = Math.max(1, Math.round((20 * level + 99) / 100));
}

battery.onchange = updateBattery;
updateBattery();
setInterval(updateBattery, 5000);
