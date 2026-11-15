/*
  ONCE THE OUTPUT TERMINAL IS DONE LOADING EVERYTHING (SHOW RST....) PRESS THE THE RST BUTTON SO THAT THE CAM WILL BEGIN TO FIND AND CONNECT TO THE NETWORK YOU HAVE
  CODED IN (UNDER WiFi)
  07 - ESP32-CAM for the prototype (AI-Thinker ESP32-CAM with OV2640)

  Runs on the ESP32-CAM board. Arduino IDE: Tools > Board >
  "AI Thinker ESP32-CAM".

  What it does
    - JOIN_DMS_NET = true (default): joins the sensor board's own Wi-Fi
      "DMS-NET" (06_devkit_wifi.ino with MAKE_OWN_WIFI = true) and always
      uses the address 192.168.4.200. No hotspot or internet needed.
      It keeps trying until the sensor board is on, so the power-on order
      does not matter.
    - JOIN_DMS_NET = false: joins your phone hotspot (WIFI_SSID / WIFI_PASS),
      address printed in the Serial Monitor.
    - Starts with the camera settings that looked good in the
      CameraWebServer menu, so nothing needs adjusting after a restart.
    - Small red LED on the back of the board: ON = joined the Wi-Fi.

  Open from a phone on the same Wi-Fi (DMS-NET: use 192.168.4.200)
    http://192.168.4.200/           page with the live video
    http://192.168.4.200:81/stream  the video by itself (for the app / run_monitor.py)
    http://192.168.4.200/jpg        one still frame
    Only ONE viewer can watch the stream at a time.
    If ".local" does not load, use the address printed in the Serial (The IP Address in the serial monitor)
    Monitor (115200 baud) when the board starts.
*/

#include "esp_camera.h"
#include "esp_http_server.h"
#include <WiFi.h>
#include <ESPmDNS.h>

// ---------- Wi-Fi ----------
const bool JOIN_DMS_NET = true;          // true: the sensor board's "DMS-NET"; false: the hotspot below
const char *DMS_NET_NAME = "DMS-NET";    // same as in 06_devkit_wifi.ino
const char *DMS_NET_PASS = "drivesafe1";
const IPAddress CAM_IP(192, 168, 4, 200);      // fixed camera address on DMS-NET
const IPAddress DMS_GATEWAY(192, 168, 4, 1);   // the sensor board
const IPAddress DMS_SUBNET(255, 255, 255, 0);

const char *WIFI_SSID = "NAME";          // only for JOIN_DMS_NET = false: your hotspot
const char *WIFI_PASS = "PASSWORD";
const char *HOSTNAME  = "dms-cam";      // -> http://dms-cam.local/

// ---------- camera settings (from the CameraWebServer menu) ----------
// If horizontal lines appear, especially in dim light, try 10 here.
const int XCLK_MHZ = 20;
const framesize_t FRAME_SIZE = FRAMESIZE_HVGA;   // 480x320 (RESOLUTION)
const int JPEG_QUALITY = 10;                     // 4 = best, 63 = worst

// AI-Thinker ESP32-CAM pin map
#define PWDN_GPIO_NUM  32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM   0
#define SIOD_GPIO_NUM  26
#define SIOC_GPIO_NUM  27
#define Y9_GPIO_NUM    35
#define Y8_GPIO_NUM    34
#define Y7_GPIO_NUM    39
#define Y6_GPIO_NUM    36
#define Y5_GPIO_NUM    21
#define Y4_GPIO_NUM    19
#define Y3_GPIO_NUM    18
#define Y2_GPIO_NUM     5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM  23
#define PCLK_GPIO_NUM  22

const int STATUS_LED = 33;   // small red LED on the back, ON when LOW
const int FLASH_LED  = 4;    // big white LED on the front, kept OFF

httpd_handle_t pageServer = NULL;
httpd_handle_t streamServer = NULL;
bool wifiWasUp = false;
unsigned long lastRetry = 0;

static const char INDEX_HTML[] =
  "<!doctype html><html><head><meta charset=\"utf-8\">"
  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
  "<title>DMS camera</title>"
  "<style>body{margin:0;background:#111;color:#eee;font-family:sans-serif;text-align:center}"
  "img{width:100%;max-width:640px;display:block;margin:0 auto}p{margin:12px}</style>"
  "</head><body><p>DMS camera</p><img id=\"v\" alt=\"video\">"
  "<script>document.getElementById('v').src="
  "'http://'+location.hostname+':81/stream';</script>"
  "</body></html>";

bool startCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk  = XCLK_GPIO_NUM;
  config.pin_pclk  = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href  = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn  = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = XCLK_MHZ * 1000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size   = FRAME_SIZE;
  config.jpeg_quality = JPEG_QUALITY;
  config.grab_mode    = CAMERA_GRAB_LATEST;

  if (psramFound()) {
    config.fb_count    = 2;
    config.fb_location = CAMERA_FB_IN_PSRAM;
  } else {
    config.fb_count    = 1;
    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed: 0x%x\n", err);
    Serial.println("Check: ribbon cable fully seated and latched? Right board selected?");
    return false;
  }

  // Same values as the menu screenshots
  sensor_t *s = esp_camera_sensor_get();
  s->set_framesize(s, FRAME_SIZE);
  s->set_quality(s, JPEG_QUALITY);
  s->set_brightness(s, 0);
  s->set_contrast(s, 0);
  s->set_saturation(s, 0);
  s->set_special_effect(s, 0);       // no effect
  s->set_whitebal(s, 1);             // AWB on
  s->set_awb_gain(s, 1);             // AWB gain on
  s->set_wb_mode(s, 0);              // WB mode auto
  s->set_exposure_ctrl(s, 1);        // AEC sensor on
  s->set_aec2(s, 0);                 // AEC DSP off
  s->set_ae_level(s, 0);
  s->set_gain_ctrl(s, 1);            // AGC on
  s->set_gainceiling(s, GAINCEILING_2X);
  s->set_bpc(s, 0);
  s->set_wpc(s, 1);
  s->set_raw_gma(s, 1);
  s->set_lenc(s, 0);                 // lens correction off
  s->set_hmirror(s, 0);
  s->set_vflip(s, 0);
  s->set_dcw(s, 1);
  s->set_colorbar(s, 0);
  return true;
}

static esp_err_t indexHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, INDEX_HTML, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t jpgHandler(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

static esp_err_t streamHandler(httpd_req_t *req) {
  static const char BOUNDARY[] = "\r\n--frame\r\n";
  char header[64];

  esp_err_t res = httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=frame");
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");

  Serial.println("Viewer connected");
  unsigned long frames = 0;
  unsigned long t0 = millis();

  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
      res = ESP_FAIL;
      break;
    }
    int hlen = snprintf(header, sizeof(header),
                        "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n",
                        (unsigned)fb->len);
    res = httpd_resp_send_chunk(req, BOUNDARY, strlen(BOUNDARY));
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, header, hlen);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
    esp_camera_fb_return(fb);
    if (res != ESP_OK) break;

    frames++;
    if (millis() - t0 >= 5000) {
      Serial.printf("Streaming at %.1f fps\n", frames * 1000.0f / (millis() - t0));
      frames = 0;
      t0 = millis();
    }
  }
  Serial.println("Viewer disconnected");
  return res;
}

void startServers() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.lru_purge_enable = true;
  config.send_wait_timeout = 5;

  httpd_uri_t indexUri  = { .uri = "/",       .method = HTTP_GET, .handler = indexHandler,  .user_ctx = NULL };
  httpd_uri_t jpgUri    = { .uri = "/jpg",    .method = HTTP_GET, .handler = jpgHandler,    .user_ctx = NULL };
  httpd_uri_t streamUri = { .uri = "/stream", .method = HTTP_GET, .handler = streamHandler, .user_ctx = NULL };

  config.server_port = 80;
  if (httpd_start(&pageServer, &config) == ESP_OK) {
    httpd_register_uri_handler(pageServer, &indexUri);
    httpd_register_uri_handler(pageServer, &jpgUri);
  } else {
    Serial.println("Could not start the page server");
  }

  config.server_port = 81;
  config.ctrl_port += 1;
  if (httpd_start(&streamServer, &config) == ESP_OK) {
    httpd_register_uri_handler(streamServer, &streamUri);
  } else {
    Serial.println("Could not start the stream server");
  }
}

void onWifiUp() {
  digitalWrite(STATUS_LED, LOW);   // LED on
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
  delay(500);
  Serial.println();

  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, HIGH);  // LED off until Wi-Fi joins
  pinMode(FLASH_LED, OUTPUT);
  digitalWrite(FLASH_LED, LOW);

  if (!startCamera()) {
    while (true) delay(1000);
  }
  Serial.printf("Camera OK (PSRAM %s)\n", psramFound() ? "found" : "not found");

  WiFi.mode(WIFI_STA);
  WiFi.setHostname(HOSTNAME);
  WiFi.setAutoReconnect(true);
  WiFi.setSleep(false);
  if (JOIN_DMS_NET) {
    WiFi.config(CAM_IP, DMS_GATEWAY, DMS_SUBNET);   // fixed address, set before joining
    WiFi.begin(DMS_NET_NAME, DMS_NET_PASS);
  } else {
    WiFi.begin(WIFI_SSID, WIFI_PASS);
  }

  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) {
    delay(200);
  }

  startServers();

  if (WiFi.status() == WL_CONNECTED) {
    wifiWasUp = true;
    onWifiUp();
  } else {
    Serial.println("Wi-Fi not found yet, still trying...");
  }
}

void loop() {
  bool up = (WiFi.status() == WL_CONNECTED);
  if (up && !wifiWasUp) onWifiUp();
  if (!up && wifiWasUp) digitalWrite(STATUS_LED, HIGH);
  if (!up && millis() - lastRetry >= 15000) {   // network not there yet (e.g. sensor board still off): try again
    lastRetry = millis();
    WiFi.reconnect();
  }
  wifiWasUp = up;
  delay(1000);
}
