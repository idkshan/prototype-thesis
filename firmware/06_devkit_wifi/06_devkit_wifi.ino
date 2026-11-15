/*

  ONCE THE OUTPUT TERMINAL IS DONE LOADING EVERYTHING (SHOW RST....) PRESS THE THE RST BUTTON SO THAT THE ESP32 WILL BEGIN TO FIND AND CONNECT TO THE NETWORK YOU HAVE
  CODED IN (UNDER WiFi)
  06 - GPS + MPU6050 + buzzer over Wi-Fi (ESP32 DevKit V1)


  Wiring
    Buzzer I/O -> P25
    MPU SDA    -> P21         MPU SCL -> P22
    GPS TX     -> P16         GPS RX  -> P17
    All VCC    -> 3V3         All GND -> GND

  Download the Libraries: TinyGPSPlus. (WiFi, WebServer and ESPmDNS come with the
  ESP32 board package.)

  Wi-Fi: two ways (switch MAKE_OWN_WIFI below)
    MAKE_OWN_WIFI = true  (default): this board CREATES its own Wi-Fi "DMS-NET".
        The camera, the phone and the laptop join it. No hotspot or internet
        needed. This board is always http://192.168.4.1/ and the camera
        (07_esp32cam_prototype.ino with JOIN_DMS_NET = true) is always
        http://192.168.4.200/. The phone uses mobile data for the internet.
    MAKE_OWN_WIFI = false: this board JOINS your phone hotspot (WIFI_SSID /
        WIFI_PASS); the address is printed in the Serial Monitor.

  Buzzer signals at power-up
    1 short beep  = started
    2 short beeps = Wi-Fi ready (DMS-NET created, or joined the hotspot)
    Keep the board still for the first second: the motion sensor
    measures its resting position then.

  Open the page from a phone on the same Wi-Fi:
    http://dms-sensors.local/      (works on iPhone and computers)
    or http://<address>/          (address printed in Serial Monitor)

    jolt = largest acceleration (in g) seen during the last second,
           measured away from the sensor's resting reading.

  The Serial Monitor (115200 baud) still prints the same line once per
  second and still accepts 0-3, for testing on the laptop.

  Changes for the DMS program (run_monitor.py --alert-style tier):
    - Fail-safe: MEDIUM and HIGH switch themselves off when no tier command
      arrives for 3 s. The program re-sends the level every second while an
      alarm sounds, so the buzzer can never be left on if the laptop / phone
      crashes or the Wi-Fi drops. (The page buttons now play 3 s.)
    - Repeating the same level does not restart the pattern (LOW still
      beeps again, so each new LOW alert is heard).
    - /data also reports "motion_g": the jolt WITHOUT the up/down part, so
      road bumps and potholes are not mistaken for harsh braking/swerving.

  The GPS module need around to 10-15 minutes to start sending coordinates and a red LED will begin to blink, if the display still shows false around those
  times run it outside your house or anything that doesn't have a thick roof or structure on top of you. It works under a car roof.

*/

#include <Wire.h>
#include <TinyGPSPlus.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>

// ---------- Wi-Fi ----------
const bool MAKE_OWN_WIFI = true;         // true: create "DMS-NET"; false: join the hotspot below
const char *DMS_NET_NAME = "DMS-NET";    // same name and password in the camera sketch
const char *DMS_NET_PASS = "drivesafe1"; // at least 8 characters
const int   DMS_NET_CHANNEL = 6;
const int   DMS_NET_MAX_CLIENTS = 4;     // camera + phone + laptop (+1 spare)

const char *WIFI_SSID = "NAME"; // only for MAKE_OWN_WIFI = false: your hotspot name
const char *WIFI_PASS = "PASSWORD"; // and password (case sensitive)
const char *HOSTNAME  = "dms-sensors";   // -> http://dms-sensors.local/

// ---------- pins ----------
const int  BUZZER_PIN = 25;
const bool ACTIVE_LOW = false;     // this buzzer sounds when the pin is HIGH
const int  SDA_PIN = 21;
const int  SCL_PIN = 22;
const int  GPS_RX_PIN = 16;
const int  GPS_TX_PIN = 17;

// ---------- GPS ----------
HardwareSerial GPSSerial(2);
TinyGPSPlus gps;

// ---------- IMU ----------
uint8_t mpuAddr = 0x68;
bool    imuOk = false;
float ax, ay, az, gx, gy, gz;
float gxBias = 0, gyBias = 0, gzBias = 0;
float restX = 0, restY = 0, restZ = 1;
const float REST_FOLLOW = 0.002f;
float joltPeak = 0;        // building up during the current second
float joltLast = 0;        // finished value for the previous second
float horizPeak = 0;       // same, horizontal part only (braking, swerving)
float horizLast = 0;
unsigned long lastImu = 0;
const unsigned long IMU_PERIOD_MS = 20;   // 50 Hz

// ---------- buzzer ----------
int tier = 0;
unsigned long tierStart = 0;
unsigned long lastTierCmd = 0;
const unsigned long FAILSAFE_MS = 3000;   // MEDIUM/HIGH stop without a command for 3 s

// ---------- web / reporting ----------
WebServer server(80);
bool wifiWasUp = false;
unsigned long lastReport = 0;
unsigned long lastWifiCheck = 0;

static const char INDEX_HTML[] PROGMEM = R"HTML(<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DMS sensors</title>
<style>
body{font-family:sans-serif;margin:16px;background:#111;color:#eee}
h1{font-size:20px;margin:0 0 12px}
table{border-collapse:collapse;width:100%;max-width:420px}
td{padding:6px 4px;border-bottom:1px solid #333}td:first-child{color:#aaa}
.b{display:flex;gap:8px;margin:16px 0;flex-wrap:wrap}
button{flex:1;min-width:70px;padding:14px 0;font-size:16px;border:0;border-radius:8px;background:#333;color:#eee}
button.on{background:#c33}
#st{color:#aaa;font-size:14px}
</style></head><body>
<h1>DMS sensors</h1>
<div class="b">
<button onclick="setTier(0)" id="t0">Silent</button>
<button onclick="setTier(1)" id="t1">Low</button>
<button onclick="setTier(2)" id="t2">Medium</button>
<button onclick="setTier(3)" id="t3">High</button>
</div>
<table id="tb"></table>
<p id="st">Connecting...</p>
<script>
const rows=[["GPS fix","fix"],["Latitude","lat"],["Longitude","lng"],["Speed (km/h)","kmh"],
["Satellites","sats"],["Devices on DMS-NET","clients"],["Jolt (g)","jolt"],["Accel x/y/z (g)","acc"],["Gyro x/y/z (deg/s)","gyr"]];
function show(d){
 d.acc=d.ax+" / "+d.ay+" / "+d.az; d.gyr=d.gx+" / "+d.gy+" / "+d.gz;
 document.getElementById("tb").innerHTML=rows.map(r=>"<tr><td>"+r[0]+"</td><td>"+d[r[1]]+"</td></tr>").join("");
 for(let i=0;i<4;i++)document.getElementById("t"+i).className=(d.tier==i?"on":"");
 document.getElementById("st").textContent="Updated "+new Date().toLocaleTimeString();
}
async function poll(){
 try{const r=await fetch("/data",{cache:"no-store"});show(await r.json());}
 catch(e){document.getElementById("st").textContent="No connection to device";}
}
async function setTier(n){try{await fetch("/tier?level="+n);poll();}catch(e){}}
poll();setInterval(poll,1000);
</script></body></html>)HTML";

// ---------- buzzer ----------
void buzzerWrite(bool on) {
  digitalWrite(BUZZER_PIN, (on != ACTIVE_LOW) ? HIGH : LOW);
}

void setTier(int t) {
  if (t < 0 || t > 3) return;
  lastTierCmd = millis();
  if (t == tier && t != 1) return;   // keepalive: keep the pattern running
  tier = t;
  tierStart = millis();
}

void updateBuzzer() {
  unsigned long t = millis() - tierStart;
  bool on = false;
  switch (tier) {
    case 1: on = t < 150;            break;
    case 2: on = (t % 1000) < 200;   break;
    case 3: on = (t % 300) < 150;    break;
    default: on = false;
  }
  buzzerWrite(on);
}

void beeps(int n) {
  for (int i = 0; i < n; i++) {
    buzzerWrite(true);
    delay(80);
    buzzerWrite(false);
    delay(120);
  }
}

// ---------- IMU ----------
void mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(mpuAddr);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

bool mpuBegin() {
  Wire.beginTransmission(0x68);
  if (Wire.endTransmission() == 0) {
    mpuAddr = 0x68;
  } else {
    Wire.beginTransmission(0x69);
    if (Wire.endTransmission() != 0) return false;
    mpuAddr = 0x69;
  }
  mpuWrite(0x6B, 0x00);   // wake
  delay(100);
  mpuWrite(0x1A, 0x03);   // low-pass ~44 Hz
  mpuWrite(0x1B, 0x08);   // gyro +/-500 deg/s
  mpuWrite(0x1C, 0x08);   // accel +/-4 g
  return true;
}

bool mpuRead() {
  Wire.beginTransmission(mpuAddr);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  Wire.requestFrom((int)mpuAddr, 14);
  if (Wire.available() < 14) return false;

  int16_t raw[7];
  for (int i = 0; i < 7; i++) {
    uint8_t hi = Wire.read();
    uint8_t lo = Wire.read();
    raw[i] = (int16_t)((hi << 8) | lo);
  }
  ax = raw[0] / 8192.0f;
  ay = raw[1] / 8192.0f;
  az = raw[2] / 8192.0f;
  gx = raw[4] / 65.5f - gxBias;
  gy = raw[5] / 65.5f - gyBias;
  gz = raw[6] / 65.5f - gzBias;
  return true;
}

void calibrateImu() {
  float sx = 0, sy = 0, sz = 0, tx = 0, ty = 0, tz = 0;
  int n = 0;
  for (int i = 0; i < 200; i++) {
    if (mpuRead()) {
      sx += gx; sy += gy; sz += gz;
      tx += ax; ty += ay; tz += az;
      n++;
    }
    delay(5);
  }
  if (n > 0) {
    gxBias = sx / n; gyBias = sy / n; gzBias = sz / n;
    restX = tx / n;  restY = ty / n;  restZ = tz / n;
  }
}

// ---------- data ----------
String dataJson() {
  bool fix = gps.location.isValid() && gps.location.age() < 3000;
  char buf[384];
  snprintf(buf, sizeof(buf),
           "{\"fix\":%s,\"lat\":%.6f,\"lng\":%.6f,\"kmh\":%.1f,"
           "\"sats\":%lu,\"hdop\":%.1f,"
           "\"ax\":%.2f,\"ay\":%.2f,\"az\":%.2f,"
           "\"gx\":%.1f,\"gy\":%.1f,\"gz\":%.1f,"
           "\"jolt\":%.2f,\"motion_g\":%.2f,\"tier\":%d,\"clients\":%d}",
           fix ? "true" : "false",
           fix ? gps.location.lat() : 0.0,
           fix ? gps.location.lng() : 0.0,
           fix ? gps.speed.kmph() : 0.0,
           (unsigned long)gps.satellites.value(),
           gps.hdop.hdop(),
           ax, ay, az, gx, gy, gz,
           joltLast, horizLast, tier,
           MAKE_OWN_WIFI ? (int)WiFi.softAPgetStationNum() : 0);
  return String(buf);
}

// ---------- web handlers ----------
void handleIndex() {
  server.send_P(200, "text/html", INDEX_HTML);
}

void handleData() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json", dataJson());
}

void handleTier() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  if (!server.hasArg("level")) {
    server.send(400, "text/plain", "missing level");
    return;
  }
  int n = server.arg("level").toInt();
  if (n < 0 || n > 3) {
    server.send(400, "text/plain", "level must be 0-3");
    return;
  }
  setTier(n);
  server.send(200, "application/json", dataJson());
}

// ---------- Wi-Fi ----------
void onWifiUp() {
  if (MAKE_OWN_WIFI) {
    Serial.printf("Wi-Fi \"%s\" is on (password %s). Join it, then open http://", DMS_NET_NAME, DMS_NET_PASS);
    Serial.print(WiFi.softAPIP());
    Serial.println("/   (camera: http://192.168.4.200/)");
    MDNS.end();
    if (MDNS.begin(HOSTNAME)) MDNS.addService("http", "tcp", 80);
    return;
  }
  Serial.print("Wi-Fi connected. Open http://");
  Serial.print(WiFi.localIP());
  Serial.print("/  or  http://");
  Serial.print(HOSTNAME);
  Serial.println(".local/");
  MDNS.end();
  if (MDNS.begin(HOSTNAME)) MDNS.addService("http", "tcp", 80);
}

void setup() {
  Serial.begin(115200);

  pinMode(BUZZER_PIN, OUTPUT);
  buzzerWrite(false);
  beeps(1);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000);
  imuOk = mpuBegin();
  if (imuOk) calibrateImu();
  else Serial.println("{\"error\":\"MPU6050 not found\"}");

  GPSSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  if (MAKE_OWN_WIFI) {
    // create DMS-NET; this board is 192.168.4.1 and hands out addresses
    WiFi.mode(WIFI_AP);
    if (!WiFi.softAP(DMS_NET_NAME, DMS_NET_PASS, DMS_NET_CHANNEL, 0, DMS_NET_MAX_CLIENTS)) {
      Serial.println("Could not create the Wi-Fi network (password must be 8+ characters)");
    }
    WiFi.setSleep(false);
  } else {
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(HOSTNAME);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASS);

    // wait up to 15 s; if the network isn't there yet, carry on and
    // keep trying in the background
    unsigned long t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) {
      while (GPSSerial.available()) gps.encode(GPSSerial.read());
      delay(100);
    }
  }

  server.on("/", handleIndex);
  server.on("/data", handleData);
  server.on("/tier", handleTier);
  server.begin();

  if (MAKE_OWN_WIFI || WiFi.status() == WL_CONNECTED) {
    wifiWasUp = true;
    onWifiUp();
    beeps(2);
  } else {
    Serial.println("Wi-Fi not found yet, still trying...");
  }
}

void loop() {
  unsigned long now = millis();

  // 1. GPS
  while (GPSSerial.available()) gps.encode(GPSSerial.read());

  // 2. IMU at 50 Hz
  if (imuOk && now - lastImu >= IMU_PERIOD_MS) {
    lastImu = now;
    if (mpuRead()) {
      float dx = ax - restX, dy = ay - restY, dz = az - restZ;
      float jolt = sqrtf(dx * dx + dy * dy + dz * dz);
      if (jolt > joltPeak) joltPeak = jolt;
      // horizontal part: remove the component along gravity (the resting vector)
      float gn = sqrtf(restX * restX + restY * restY + restZ * restZ);
      float up = (gn > 0.1f) ? (dx * restX + dy * restY + dz * restZ) / gn : 0.0f;
      float horiz = sqrtf(fmaxf(jolt * jolt - up * up, 0.0f));
      if (horiz > horizPeak) horizPeak = horiz;
      restX += REST_FOLLOW * dx;
      restY += REST_FOLLOW * dy;
      restZ += REST_FOLLOW * dz;
    }
  }

  // 3. Web requests and serial commands
  server.handleClient();
  if (Serial.available()) {
    char c = Serial.read();
    if (c >= '0' && c <= '3') setTier(c - '0');
  }
  if (tier >= 2 && millis() - lastTierCmd > FAILSAFE_MS) setTier(0);   // fail-safe (millis(): a command may have arrived after "now")
  updateBuzzer();

  // 4. Wi-Fi came back after a drop or a late start (only when joining a hotspot)
  if (!MAKE_OWN_WIFI && now - lastWifiCheck >= 1000) {
    lastWifiCheck = now;
    bool up = (WiFi.status() == WL_CONNECTED);
    if (up && !wifiWasUp) {
      onWifiUp();
      if (tier == 0) beeps(2);
    }
    wifiWasUp = up;
  }

  // 5. Once per second: finish this second's jolt, print the line
  if (now - lastReport >= 1000) {
    lastReport = now;
    joltLast = joltPeak;
    joltPeak = 0;
    horizLast = horizPeak;
    horizPeak = 0;
    Serial.println(dataJson());
  }
}
