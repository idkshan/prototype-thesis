const { onValueCreated } = require("firebase-functions/v2/database")
const admin = require("firebase-admin")
admin.initializeApp()

// Supervisor push for Driver 1. Two kinds of events are pushed:
//  - real alerts from the driver's phone (source "device"): the alert state machine marks
//    push = true once per HIGH alert, at most once per minute;
//  - simulated High test events (source "manual_test", long-press on the driver screen).
// Old events (queued while offline and synced later) are not pushed.
exports.ThesisDemoHighRisk = onValueCreated(
  "/demo/drivers/driver1/events/{eventId}",
  async (event) => {
    const data = event.data.val()
    if (!data || data.livePushEligible !== true) return null
    const live = data.simulated === false && data.source === "device" && data.push === true
    const test = data.simulated === true && data.source === "manual_test" && data.severity === "High"
    if (!live && !test) return null
    const age = Date.now() - Number(data.timestampMs || 0)
    if (age < 0 || age > (live ? 60000 : 30000)) return null
    const snap = await admin.database().ref("/supervisorTokens").get()
    if (!snap.exists()) return null
    const tokens = Object.values(snap.val()).filter((v) => typeof v === "string" && v.length > 0)
    if (tokens.length === 0) return null
    return admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        demo: test ? "true" : "false",
        source: String(data.source),
        behavior: String(data.behavior || "Unknown behavior"),
        eventId: String(data.eventId || event.params.eventId)
      },
      android: { priority: "high" }
    })
  }
)
