package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/** One entry point for both roles. The role is checked in Firebase, never selected by the user. */
class LauncherActivity : Activity() {
    private lateinit var content: LinearLayout
    private var signingUp = false
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        Ui.nightMode = getSharedPreferences("driver_display", MODE_PRIVATE).getBoolean("night", false)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@LauncherActivity, 16), Ui.dp(this@LauncherActivity, 20),
                Ui.dp(this@LauncherActivity, 16), Ui.dp(this@LauncherActivity, 24))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (FirebaseApp.getApps(this).isEmpty()) showMessage("Firebase is not configured on this device.")
        else if (FirebaseAuth.getInstance().currentUser != null) checkRole()
        else showLogin()
    }

    private fun showMessage(message: String) {
        content.removeAllViews()
        content.addView(Ui.title(this, "ESCA-3-2526-C3"))
        val card = Ui.card(this)
        card.addView(Ui.note(this, message))
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            card.addView(button("Check account role") { checkRole() })
            card.addView(button("Use another account") {
                FirebaseAuth.getInstance().signOut()
                showLogin()
            })
        }
        content.addView(card, Ui.blockParams(this))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun field(hintText: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or if (password)
            InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setSingleLine(true)
        if (password) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        textSize = 14f
        if (Ui.nightMode) {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
    }

    private fun showLogin() {
        content.removeAllViews()
        content.addView(Ui.title(this, "ESCA-3-2526-C3"))

        val card = Ui.card(this)
        card.addView(Ui.title(this, if (signingUp) "Create account" else "Sign in"))

        val email = field("Email address")
        val password = field("Password", true)
        card.addView(email)
        card.addView(password)

        val confirmation = if (signingUp) field("Confirm password", true) else null
        confirmation?.let { card.addView(it) }

        val feedback = Ui.note(this, "")
        card.addView(feedback)

        val submit = button(if (signingUp) "Create account" else "Sign in") {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            when {
                FirebaseApp.getApps(this).isEmpty() -> feedback.text = "Firebase is unavailable."
                !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() -> feedback.text = "Enter a valid email."
                secret.length < 6 -> feedback.text = "Use at least 6 password characters."
                signingUp && confirmation?.text.toString() != secret -> feedback.text = "Passwords do not match."
                else -> {
                    feedback.text = "Checking account…"
                    val auth = FirebaseAuth.getInstance()
                    val task = if (signingUp) auth.createUserWithEmailAndPassword(address, secret)
                    else auth.signInWithEmailAndPassword(address, secret)
                    task.addOnCompleteListener(this) { result ->
                        if (result.isSuccessful) checkRole()
                        else feedback.text = result.exception?.localizedMessage ?: "Sign-in failed."
                    }
                }
            }
        }
        card.addView(submit)
        card.addView(button(if (signingUp) "Already have an account? Sign in" else "Create an account") {
            signingUp = !signingUp
            showLogin()
        })
        if (!signingUp) card.addView(button("Forgot password?") {
            val address = email.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                feedback.text = "Enter your email first."
            } else FirebaseAuth.getInstance().sendPasswordResetEmail(address)
                .addOnSuccessListener { feedback.text = "Check your email for reset instructions." }
                .addOnFailureListener { feedback.text = it.localizedMessage ?: "Could not send reset email." }
        })
        content.addView(card, Ui.blockParams(this))
    }

    private fun checkRole() {
        val user = FirebaseAuth.getInstance().currentUser ?: run { showLogin(); return }
        if (checking) return
        if (user.email.isNullOrBlank()) { showMessage("Sign in with an email account."); return }
        checking = true
        showMessage("Checking access for ${user.email ?: "your account"}…")
        FirebaseDatabase.getInstance(
            "https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).getReference("roles").child(user.uid).get()
            .addOnSuccessListener { snapshot ->
                checking = false
                if (isFinishing || isDestroyed || FirebaseAuth.getInstance().currentUser?.uid != user.uid) return@addOnSuccessListener
                val destination = when (snapshot.getValue(String::class.java)) {
                    "driver" -> MainActivity::class.java
                    "supervisor" -> SupervisorActivity::class.java
                    else -> null
                }
                if (destination == null) showMessage("This account has no assigned driver or supervisor role")
                else {
                    startActivity(Intent(this, destination))
                    finish()
                }
            }
            .addOnFailureListener { error ->
                checking = false
                if (!isFinishing) showMessage("Could not verify account role. Check internet access and retry. ${error.localizedMessage}")
            }
    }
}
