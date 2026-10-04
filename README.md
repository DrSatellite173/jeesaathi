# JEE Saathi - Android (Kotlin) : Tracker + NTA Test Series + 3D Studio

## Kya-kya hai
- **Tracker**: aapka web app (JEE Saathi) `app/src/main/assets/web` me bundled hai, offline chalta hai. Har user ka tracker data alag backup hota hai.
- **Tests**: HTML / PDF / ZIP import -> NTA format test (timer, palette, mark for review, clear, save&next, submit).
- **Analysis (Mathongo style)**: score, accuracy, subject-wise, time analysis (perfect / overtime / wasted), question-wise review. Local save + Firebase cloud sync.
- **3D Studio**: photo / video -> OpenGL depth-parallax. Test ke question image par tap = seedha 3D.
- **Multi-user**: Firebase email login (optional). Bina login "Guest" mode.

## Build
1. Android Studio (Hedgehog+) me folder open karo -> Gradle sync -> Run.
2. JDK 17 chahiye (Android Studio ke saath aata hai).

## Multi-user / cloud (optional)
1. Firebase Console -> project banao -> Android app add karo, package: `com.exam.app`
2. `google-services.json` download karke `app/` me rakho (jahan build.gradle.kts hai).
3. Authentication -> Sign-in method -> Email/Password ON.
4. Firestore Database banao, Rules:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```
`google-services.json` nahi hai to app guest mode me chalta hai (build fail nahi hota).

## Test import tips
- HTML ke saath uski images ek saath select karo, ya poora ZIP do (html + images folder).
- Format jo parse hota hai: `.question-container` / `.question-block`, ya options ki list (ol/ul/div), ya "Q1. ... (A) (B) (C) (D)" / "1. ... A) B) C) D)" text.
- Subject heading ("Physics", "Chemistry", "Mathematics", "Section A - Physics") se subjects auto-detect hote hain.
- Answer key: HTML me `Answer: B` / `data-correct` / `.correct` class ho to auto. Warna test card -> "Answer key daalo" -> `1-A 2-C 3-4.5 ...`
- PDF: text wali (selectable) PDF chahiye. Har question ka crop image ban jata hai (formulas/diagrams exact), options A-D / 1-4 buttons. PDF ke end me "Answer Key" ho to auto padh leta hai. Scanned image-PDF support nahi.
- Marking: +4 / -1 (MCQ), numerical me -0. Badalna ho to `TestMeta` defaults (`models/AppModels.kt`).

## 3D depth (AI optional)
- Default: built-in depth estimator (approximate).
- Zyada realistic: MiDaS small TFLite model ko `app/src/main/assets/models/midas_small.tflite` naam se rakho (input [1,H,W,3] float, ImageNet normalisation assume hai - `DepthEstimator.kt` me). Model hone par automatic use hota hai.

## Tracker web code badalna ho
Web project me `pnpm --filter @workspace/jee-tracker run build`, phir `dist/public/*` ko `app/src/main/assets/web/` me copy karo.

## Existing app update karna ho
`applicationId` same rakho aur wahi signing key use karo, tabhi purane install par update hoga.
