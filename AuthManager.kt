package com.exam.app.auth

import android.content.Context
import com.exam.app.data.Session
import com.exam.app.models.Attempt
import com.exam.app.models.CloudResult
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class AuthManager(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** google-services.json ke bina Firebase configure nahi hota -> guest/local mode. */
    val firebaseReady: Boolean
        get() = try { FirebaseApp.getApps(ctx).isNotEmpty() } catch (e: Exception) { false }

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance()
    private fun db(): FirebaseFirestore = FirebaseFirestore.getInstance()

    /** App start par session restore. true = koi user (cloud ya guest) pehle se logged-in hai. */
    fun restoreSession(): Boolean {
        if (firebaseReady) {
            val u = auth().currentUser
            if (u != null) {
                applyUser(u.uid, u.displayName, u.email)
                return true
            }
        }
        if (prefs.getBoolean("guest", false)) {
            applyGuest()
            return true
        }
        return false
    }

    private fun applyUser(uid: String, name: String?, email: String?) {
        Session.uid = uid
        Session.name = if (!name.isNullOrBlank()) name else (email?.substringBefore('@') ?: "User")
        Session.email = email ?: ""
        Session.cloud = true
    }

    private fun applyGuest() {
        Session.uid = "local"
        Session.name = "Guest"
        Session.email = ""
        Session.cloud = false
    }

    fun continueAsGuest() {
        prefs.edit().putBoolean("guest", true).apply()
        applyGuest()
    }

    fun signIn(email: String, password: String, cb: (Throwable?) -> Unit) {
        if (!firebaseReady) { cb(IllegalStateException("Firebase configure nahi hai (google-services.json missing)")); return }
        auth().signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { r ->
                val u = r.user
                if (u != null) {
                    applyUser(u.uid, u.displayName, u.email)
                    prefs.edit().putBoolean("guest", false).apply()
                    saveProfile()
                    cb(null)
                } else cb(IllegalStateException("Login fail"))
            }
            .addOnFailureListener { cb(it) }
    }

    fun signUp(name: String, email: String, password: String, cb: (Throwable?) -> Unit) {
        if (!firebaseReady) { cb(IllegalStateException("Firebase configure nahi hai (google-services.json missing)")); return }
        auth().createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { r ->
                val u = r.user
                if (u == null) { cb(IllegalStateException("Signup fail")); return@addOnSuccessListener }
                val finish = {
                    applyUser(u.uid, if (name.isNotBlank()) name else u.displayName, u.email)
                    prefs.edit().putBoolean("guest", false).apply()
                    saveProfile()
                    cb(null)
                }
                if (name.isNotBlank()) {
                    u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
                        .addOnCompleteListener { finish() }
                } else finish()
            }
            .addOnFailureListener { cb(it) }
    }

    fun signOut() {
        try { if (firebaseReady) auth().signOut() } catch (_: Exception) {}
        prefs.edit().putBoolean("guest", false).apply()
        applyGuest()
    }

    private fun saveProfile() {
        if (!Session.cloud) return
        try {
            val data = hashMapOf<String, Any>(
                "name" to Session.name,
                "email" to Session.email,
                "updatedAt" to System.currentTimeMillis()
            )
            db().collection("users").document(Session.uid).set(data, com.google.firebase.firestore.SetOptions.merge())
        } catch (_: Exception) {}
    }

    /** Result pehle local save hota hai (UserStore). Ye sirf cloud copy hai - offline ho to Firestore khud retry karta hai. */
    fun pushResult(a: Attempt) {
        if (!Session.cloud || !firebaseReady) return
        val an = a.analysis ?: return
        try {
            val subjects = an.subjects.map {
                hashMapOf<String, Any>(
                    "subject" to it.subject, "score" to it.score, "maxScore" to it.maxScore,
                    "correct" to it.correct, "wrong" to it.wrong, "attempted" to it.attempted,
                    "total" to it.total, "accuracy" to it.accuracy, "timeSec" to it.timeSec
                )
            }
            val data = hashMapOf<String, Any>(
                "id" to a.id, "testId" to a.testId, "testName" to a.testName,
                "score" to an.score, "maxScore" to an.maxScore, "attempted" to an.attempted,
                "correct" to an.correct, "wrong" to an.wrong, "unattempted" to an.unattempted,
                "accuracy" to an.accuracy, "percentage" to an.percentage,
                "totalTimeSec" to an.totalTimeSec, "finishedAt" to a.finishedAt,
                "subjects" to subjects
            )
            db().collection("users").document(Session.uid)
                .collection("results").document(a.id).set(data)
        } catch (_: Exception) {}
    }

    fun fetchCloudResults(cb: (List<CloudResult>) -> Unit) {
        if (!Session.cloud || !firebaseReady) { cb(emptyList()); return }
        try {
            db().collection("users").document(Session.uid).collection("results")
                .orderBy("finishedAt", Query.Direction.DESCENDING).limit(200).get()
                .addOnSuccessListener { snap ->
                    cb(snap.documents.mapNotNull { d ->
                        val id = d.getString("id") ?: d.id
                        CloudResult(
                            id = id,
                            testName = d.getString("testName") ?: "Test",
                            score = d.getLong("score") ?: 0L,
                            maxScore = d.getLong("maxScore") ?: 0L,
                            percentage = d.getDouble("percentage") ?: 0.0,
                            accuracy = d.getDouble("accuracy") ?: 0.0,
                            finishedAt = d.getLong("finishedAt") ?: 0L
                        )
                    })
                }
                .addOnFailureListener { cb(emptyList()) }
        } catch (e: Exception) { cb(emptyList()) }
    }

    // ---------- Tracker (web app) data backup per account ----------
    fun pushTrackerBackup(json: String) {
        if (!Session.cloud || !firebaseReady) return
        try {
            val data = hashMapOf<String, Any>("json" to json, "updatedAt" to System.currentTimeMillis())
            db().collection("users").document(Session.uid).collection("tracker").document("state").set(data)
        } catch (_: Exception) {}
    }

    fun fetchTrackerBackup(cb: (String?) -> Unit) {
        if (!Session.cloud || !firebaseReady) { cb(null); return }
        try {
            db().collection("users").document(Session.uid).collection("tracker").document("state").get()
                .addOnSuccessListener { cb(it.getString("json")) }
                .addOnFailureListener { cb(null) }
        } catch (e: Exception) { cb(null) }
    }
}
