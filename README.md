# GateMemory 📍

### Visual Memory for Real-World Places

> **Maps remember coordinates. GateMemory remembers what the place looks like.**

GateMemory is an **offline-first Android application** that helps users remember and recognize physical entrances such as classrooms, laboratories, hostels, offices, apartments, parking areas, and other hard-to-identify locations.

Instead of relying only on GPS coordinates or written addresses, GateMemory creates a **visual memory of a physical place** using the smartphone camera and retrieves that memory when the user encounters the same scene again.

---

## 🎯 The Problem

Reaching the correct location does not always mean finding the correct entrance.

In campuses, hospitals, apartments, offices, hostels, parking areas, and large buildings, users may encounter:

- Multiple similar-looking doors or gates
- Poorly labelled entrances
- Addresses that identify a building but not the exact entrance
- Locations that are difficult to describe using text alone
- Situations where internet connectivity is unavailable or unreliable

Traditional maps are excellent at answering:

> **"Where is this place?"**

But they are not always designed to answer:

> **"Which exact entrance am I looking at?"**

### GateMemory addresses this gap through visual memory.

---

# 💡 Our Solution

GateMemory allows a user to:

1. Point the phone camera at an entrance.
2. Give the location a name.
3. Add a text note.
4. Record a voice memory.
5. Save the visual fingerprint of the scene.
6. Return to the location later.
7. Point the camera at the entrance again.
8. Let GateMemory recognize the scene.
9. Retrieve the saved location, notes, and voice memory.

The core recognition experience works **directly on the device**, without requiring a backend or internet connection.

---

# ✨ Key Features

### 📷 Visual Location Tagging

Capture and save the visual appearance of a physical entrance instead of relying only on coordinates.

### 🔍 Visual Recognition

GateMemory compares a live camera scene with previously saved visual fingerprints to identify known entrances.

### 🎙️ Voice Memory

Attach a voice note to a saved location.

Example:

> "TY Classroom, second floor, beside the staircase."

### 🤖 Offline AI Transcription

Recorded voice notes can be transcribed using a bundled **Whisper Tiny English model**, allowing the memories to become searchable.

### 🔎 Memory Search

Search saved locations using:

- Location name
- Written notes
- Transcribed voice notes

### 📴 Offline-First

The core recognition workflow is designed to operate without internet connectivity.

### 🔐 Privacy-Focused

Saved images, descriptors, notes, audio, and transcripts remain within the app's local storage for the core experience.

### 📊 Confidence Feedback

The recognition interface provides a confidence score based on the visual matching process.

### 💡 Scene Quality Check

Low-feature or unsuitable scenes can be rejected before saving.

### 🌙 Low-Light Warning

The app provides feedback when lighting conditions may affect visual recognition.

### 📳 Haptic & Audio Feedback

The app provides feedback when a saved visual memory is successfully recognized.

---

# 🧠 How It Works

GateMemory uses two complementary technologies:

### 1. Computer Vision — Entrance Recognition

The visual recognition pipeline uses **OpenCV ORB feature extraction**.

The process is approximately:

```text
Camera Frame
     ↓
Grayscale Conversion
     ↓
ORB Keypoint Detection
     ↓
ORB Descriptor Extraction
     ↓
KNN Matching (k = 2)
     ↓
Lowe-style Ratio Test
     ↓
Good Match Evaluation
     ↓
Confidence Calculation
     ↓
Known Gate / No Known Gate
