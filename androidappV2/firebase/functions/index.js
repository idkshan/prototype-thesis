const { onValueCreated } = require("firebase-functions/v2/database")
const admin = require("firebase-admin")
admin.initializeApp({
    databaseURL: "https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app"
})

// Supervisor push notification for a new alert at alerts/<driver>/<alert>. Pushed:
//  - real alerts from the driver's phone (source "device") that the alert state machine marked
//    push = true (once per HIGH alert, at most once per minute);
//  - HIGH test alerts (source "test", developer mode on the driver screen).
// Old alerts (kept on the phone while offline and sent later) are not pushed.
exports.highRiskAlert = onValueCreated(
    {
        ref: "/alerts/{driverId}/{alertId}",
        instance: "esca-3-2526-c3-testing-default-rtdb",
        region: "asia-southeast1"
    },
async (event) => {
    const data = event.data.val()
    if (!data) return null
    const live = data.source === "device" && data.push === true
    const test = data.source === "test" && data.level === "HIGH"
    if (!live && !test) return null
    const age = Date.now() - Number(data.timestampMs || 0)
    if (age < 0 || age > (live ? 60000 : 30000)) return null
    const snap = await admin.database().ref("/supervisorTokens").get()
    if (!snap.exists()) return null
    const tokens = Object.values(snap.val()).filter((v) => typeof v === "string" && v.length > 0)
    if (tokens.length === 0) return null
    const nameSnap = await admin.database().ref(`/drivers/${event.params.driverId}/name`).get()
    return admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        test: test ? "true" : "false",
        source: String(data.source),
        behavior: String(data.behavior || "Alert"),
        alertId: String(event.params.alertId),
        driverId: String(event.params.driverId),
        driverName: String(nameSnap.val() || "driver")
      },
      android: { priority: "high" }
    })
  }
)
