const { onValueCreated } = require("firebase-functions/v2/database")
const admin = require("firebase-admin")
admin.initializeApp({
    databaseURL: "https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app"
})
exports.ThesisDemoHighRisk = onValueCreated(
    {
        ref: "/demo/drivers/{driverId}/events/{eventId}",
        instance: "esca-3-2526-c3-testing-default-rtdb",
        region: "asia-southeast1"
    },
async (event) => {
    const data = event.data.val()
    if (!data || data.simulated !== true || data.source !== "manual_test") return null
    if (data.severity !== "High" || data.livePushEligible !== true) return null
    const age = Date.now() - Number(data.timestampMs || 0)
    if (age < 0 || age > 30000) return null
    const snap = await admin.database().ref("/supervisorTokens").get()
    if (!snap.exists()) return null
    const tokens = Object.values(snap.val()).filter((v) => typeof v === "string" && v.length > 0)
    if (tokens.length === 0) return null
    return admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        demo: "true",
        behavior: String(data.behavior || "Unknown behavior"),
        eventId: String(data.eventId || event.params.eventId),
        driverId: String(event.params.driverId)
      },
      android: { priority: "high" }
    })
  }
)
