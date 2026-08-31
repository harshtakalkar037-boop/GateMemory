import cv2
import numpy as np
import argparse
import json
import os
import time

TAGS_DIR = "gate_tags"
INDEX_FILE = os.path.join(TAGS_DIR, "index.json")

orb = cv2.ORB_create(nfeatures=1000)
bf = cv2.BFMatcher(cv2.NORM_HAMMING)


def load_index():
    if not os.path.exists(INDEX_FILE):
        return []
    with open(INDEX_FILE) as f:
        return json.load(f)


def save_index(index):
    os.makedirs(TAGS_DIR, exist_ok=True)
    with open(INDEX_FILE, "w") as f:
        json.dump(index, f, indent=2)


def extract_features(frame):
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if len(frame.shape) == 3 else frame
    kp, des = orb.detectAndCompute(gray, None)
    return kp, des


def score_against_index(des, index, ratio=0.6):
    """returns (best_entry, best_pct) - best_pct is the fraction of the live
    frame's own keypoints that found a confident match in a stored tag.
    using a percentage instead of a raw count because raw counts scale with
    image size/detail and aren't comparable across different tags - tried
    that first and it let unrelated scenes clear a fixed threshold."""
    best = None
    best_pct = 0.0
    if des is None or des.shape[0] == 0:
        return None, 0.0
    for entry in index:
        stored_des = np.array(entry["descriptors"], dtype=np.uint8)
        if stored_des.shape[0] == 0:
            continue
        matches = bf.knnMatch(des, stored_des, k=2)
        good = []
        for pair in matches:
            if len(pair) != 2:
                continue
            m, n = pair
            if m.distance < ratio * n.distance:
                good.append(m)
        pct = len(good) / des.shape[0]
        if pct > best_pct:
            best_pct = pct
            best = entry
    return best, best_pct


def grab_frame(source):
    try:
        source = int(source)
    except ValueError:
        pass
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        raise RuntimeError(f"couldn't open source {source}")
    frame = None
    # macOS webcams especially can take close to a second to wake up and
    # adjust exposure after being opened cold - 5 quick reads wasn't enough,
    # this gives it real time before we start trusting what comes back
    for _ in range(25):
        ret, frame = cap.read()
        if not ret:
            cap.release()
            raise RuntimeError("camera not returning frames")
        time.sleep(0.06)
    cap.release()
    return frame


def record_voice_note(path, seconds=4):
    try:
        import sounddevice as sd
        import soundfile as sf
    except ImportError:
        print("(sounddevice/soundfile not installed - run: pip install sounddevice soundfile)")
        return None
    print(f"recording {seconds}s voice note... speak now")
    fs = 44100
    audio = sd.rec(int(seconds * fs), samplerate=fs, channels=1)
    sd.wait()
    sf.write(path, audio, fs)
    print("saved voice note:", path)
    return path


def play_voice_note(path):
    try:
        import sounddevice as sd
        import soundfile as sf
    except ImportError:
        return
    data, fs = sf.read(path)
    sd.play(data, fs)
    sd.wait()


def tag_location(args):
    frame = grab_frame(args.source)
    kp, des = extract_features(frame)
    if des is None or len(kp) < 20:
        print(f"not enough visual detail in this frame to tag reliably ({len(kp) if kp else 0} keypoints found, need 20+)")
        print("point it at something with real texture/pattern - a keyboard, a textured wall, a printed page - not a blank surface")
        return

    os.makedirs(TAGS_DIR, exist_ok=True)
    tag_id = f"tag_{int(time.time())}"
    img_path = os.path.join(TAGS_DIR, tag_id + ".jpg")
    cv2.imwrite(img_path, frame)

    audio_path = None
    if args.record_audio:
        audio_path = os.path.join(TAGS_DIR, tag_id + ".wav")
        if record_voice_note(audio_path, seconds=args.audio_seconds) is None:
            audio_path = None

    index = load_index()
    index.append({
        "id": tag_id,
        "name": args.name,
        "note_text": args.note,
        "audio_path": audio_path,
        "image_path": img_path,
        "descriptors": des.tolist(),
    })
    save_index(index)
    print(f"tagged '{args.name}' as {tag_id} ({len(kp)} keypoints)")


def match_location(args):
    index = load_index()
    if not index:
        print("no tags saved yet - tag something first")
        return

    frame = grab_frame(args.source)
    kp, des = extract_features(frame)
    if des is None:
        print("couldn't find enough visual features in the live view")
        return

    best, best_pct = score_against_index(des, index)

    if best and best_pct >= args.threshold:
        print(f"MATCH: {best['name']}  (confidence: {best_pct:.0%})")
        if best.get("note_text"):
            print("note:", best["note_text"])
        if best.get("audio_path"):
            play_voice_note(best["audio_path"])
    else:
        print(f"no match found (best confidence was {best_pct:.0%}, needed {args.threshold:.0%}+)")
        print("this looks like a new location - tag it?")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    t = sub.add_parser("tag", help="tag the current camera view as a known location")
    t.add_argument("--source", default=0)
    t.add_argument("--name", required=True, help="human label, e.g. 'blue building gate'")
    t.add_argument("--note", default="", help="text note - stand-in for a transcribed voice note")
    t.add_argument("--record-audio", action="store_true", help="also record a real voice note from the mic")
    t.add_argument("--audio-seconds", type=int, default=4)
    t.set_defaults(func=tag_location)

    m = sub.add_parser("match", help="check if the current camera view matches a known tag")
    m.add_argument("--source", default=0)
    m.add_argument("--threshold", type=float, default=0.10, help="min fraction of matched keypoints to count as a hit (0-1)")
    m.set_defaults(func=match_location)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
