package edu.dlsu.esca_3_2526_c3

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class ThesisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.initializeApp(this) != null) {
            // Set once and before any database references are opened.
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        }
    }
}
