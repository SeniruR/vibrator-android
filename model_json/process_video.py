import cv2
import librosa
import numpy as np
import json

import argparse
import os
import time

# --- CONFIGURATION ---
VIDEO_PATH = "./videoplayback.mp4"
# Ensure output JSON is written inside this script's folder (model_json/)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_JSON_PATH = os.path.join(SCRIPT_DIR, "output_haptic_map.json")
WINDOW_SIZE_MS = 40  # 40ms, corresponds to 25 frames per second

# Threshold defaults (tweakable via CLI args)
LIGHTNING_BRIGHTNESS_SCALE_MAX = 50.0
LIGHTNING_THRESHOLD_AUDIO = 100
LIGHTNING_THRESHOLD_VISUAL_LOW = 140
LIGHTNING_THRESHOLD_VISUAL_HIGH = 160
RAIN_DIFF_THRESHOLD = 20
RAIN_RATIO_MAX = 0.12
RAIN_INTENSITY_MAX = 200
RAIN_INTENSITY_THRESHOLD = 30
RAIN_AUDIO_FRACTION = 0.4
AUDIO_RMS_MAX = 0.5
AUDIO_THUNDER_INTENSITY_THRESHOLD = 100
MIN_INTENSITY = 16

# --- 1. INITIALIZE MODELS & GET VIDEO PROPERTIES ---
print("Loading video properties...")

# --- CLI ARGUMENTS ---
parser = argparse.ArgumentParser(description="Process a video into a haptic intensity map with thunder/rain detectors.")
parser.add_argument('--video', '-v', default=VIDEO_PATH, help='Input video path')
parser.add_argument('--out', '-o', default=os.path.basename(OUTPUT_JSON_PATH), help='Output JSON filename (always written into model_json/)')
parser.add_argument('--lightning-brightness-max', type=float, default=LIGHTNING_BRIGHTNESS_SCALE_MAX)
parser.add_argument('--lightning-threshold-audio', type=float, default=LIGHTNING_THRESHOLD_AUDIO)
parser.add_argument('--lightning-threshold-visual-low', type=float, default=LIGHTNING_THRESHOLD_VISUAL_LOW)
parser.add_argument('--lightning-threshold-visual-high', type=float, default=LIGHTNING_THRESHOLD_VISUAL_HIGH)
parser.add_argument('--rain-diff-threshold', type=int, default=RAIN_DIFF_THRESHOLD)
parser.add_argument('--rain-ratio-max', type=float, default=RAIN_RATIO_MAX)
parser.add_argument('--rain-intensity-max', type=float, default=RAIN_INTENSITY_MAX)
parser.add_argument('--rain-intensity-threshold', type=float, default=RAIN_INTENSITY_THRESHOLD)
parser.add_argument('--rain-audio-fraction', type=float, default=RAIN_AUDIO_FRACTION)
parser.add_argument('--audio-rms-max', type=float, default=AUDIO_RMS_MAX)
parser.add_argument('--audio-thunder-threshold', type=float, default=AUDIO_THUNDER_INTENSITY_THRESHOLD)
parser.add_argument('--min-intensity', type=int, default=MIN_INTENSITY)
parser.add_argument('--verbose', action='store_true', help='Print timing and progress information')
args = parser.parse_args()

# Apply CLI overrides
VIDEO_PATH = args.video
# Always write output into the model_json folder next to this script.
OUTPUT_JSON_PATH = os.path.join(SCRIPT_DIR, os.path.basename(args.out))
LIGHTNING_BRIGHTNESS_SCALE_MAX = args.lightning_brightness_max
LIGHTNING_THRESHOLD_AUDIO = args.lightning_threshold_audio
LIGHTNING_THRESHOLD_VISUAL_LOW = args.lightning_threshold_visual_low
LIGHTNING_THRESHOLD_VISUAL_HIGH = args.lightning_threshold_visual_high
RAIN_DIFF_THRESHOLD = args.rain_diff_threshold
RAIN_RATIO_MAX = args.rain_ratio_max
RAIN_INTENSITY_MAX = args.rain_intensity_max
RAIN_INTENSITY_THRESHOLD = args.rain_intensity_threshold
RAIN_AUDIO_FRACTION = args.rain_audio_fraction
AUDIO_RMS_MAX = args.audio_rms_max
AUDIO_THUNDER_INTENSITY_THRESHOLD = args.audio_thunder_threshold
MIN_INTENSITY = args.min_intensity

# Now open the video file using the (possibly overridden) VIDEO_PATH
if not os.path.exists(VIDEO_PATH):
    print(f"Error: video file not found at '{VIDEO_PATH}'. Please check the path.")
    print("- Use an absolute path or ensure the working directory is correct.")
    print("- Example: python model_json\\process_video.py --video D:\\Videos\\myclip.mp4")
    exit(1)

cap = cv2.VideoCapture(VIDEO_PATH)
fps = cap.get(cv2.CAP_PROP_FPS)
frame_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
frame_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

# Calculate how many frames are in each 40ms window
frames_per_window = int(fps * (WINDOW_SIZE_MS / 1000.0))

if not cap.isOpened() or total_frames <= 0 or fps == 0:
    print(f"Error: OpenCV could not open the video or detected 0 frames (fps={fps}, total_frames={total_frames}).")
    print("Possible causes:")
    print("  - The file exists but OpenCV lacks codec support for this MP4 (common when ffmpeg isn't available to OpenCV).")
    print("  - The path is a placeholder or inaccessible to this process.")
    print("Checks you can run:")
    print("  - Verify the file exists: `python -c \"import os; print(os.path.exists('SOME_PATH'))\"`")
    print("  - Inspect file with ffprobe/ffmpeg: `ffmpeg -i YOUR_VIDEO.mp4` (if ffmpeg installed)")
    print("Workarounds:")
    print("  - Install ffmpeg and/or use a video file with a codec OpenCV supports.")
    print("  - Convert the file using ffmpeg: `ffmpeg -i in.mp4 -c:v libx264 -preset fast -crf 23 out.mp4`")
    exit(1)

import subprocess
# --- 2. AUDIO ANALYSIS ---
print("Analyzing audio track...")

# --- 2. AUDIO ANALYSIS ---
print("Analyzing audio track...")
import os
import subprocess

# Extract audio using ffmpeg
audio_path = "temp_audio.wav"
command = f"ffmpeg -i {VIDEO_PATH} -ab 160k -ac 2 -ar 44100 -vn {audio_path} -y"
try:
    subprocess.run(command, check=True, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
except (subprocess.CalledProcessError, FileNotFoundError):
    print("ffmpeg is not installed or not in your PATH. Falling back to visual-only analysis (audio will be ignored).")
    # Create a silent/empty audio_rms array so processing continues without audio
    sample_rate = 44100
    audio_waveform = np.zeros(1, dtype=np.float32)
    audio_rms = np.zeros(total_frames)
else:
    # Load the audio track using librosa
    try:
        audio_waveform, sample_rate = librosa.load(audio_path, sr=None)
    except Exception:
        print("Failed to load extracted audio; proceeding with visual-only analysis.")
        audio_waveform = np.zeros(1, dtype=np.float32)
        audio_rms = np.zeros(total_frames)
    finally:
        # Clean up the temporary audio file if it exists
        try:
            os.remove(audio_path)
        except Exception:
            pass
# If audio_rms wasn't computed above (successful load path), compute it now
if 'audio_rms' not in locals() or audio_rms is None or len(audio_rms) == 0:
    try:
        frame_length_audio = int(sample_rate / fps) if fps > 0 else 1024
        audio_rms = librosa.feature.rms(y=audio_waveform, frame_length=frame_length_audio, hop_length=frame_length_audio)[0]
    except Exception:
        # best-effort fallback
        audio_rms = np.zeros(total_frames)

# --- 3. FRAME-BY-FRAME VIDEO ANALYSIS ---
print(f"Analyzing {total_frames} video frames...")

start_time = time.time()

# Containers for debug
frames_metrics = []

last_brightness = 0

for frame_idx in range(total_frames):
    ret, frame = cap.read()
    if not ret:
        break

    if args.verbose and frame_idx % 500 == 0 and frame_idx > 0:
        elapsed = time.time() - start_time
        print(f"  processed {frame_idx}/{total_frames} frames (elapsed {elapsed:.2f}s)")

    # --- Visual analysis: lightning (brightness spike) and rain detection ---
    # Create a downsampled grayscale for fast analysis
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    down = cv2.resize(gray, (frame_width // 4, frame_height // 4))

    # Prepare mask for rain detection (difference from previous downsampled frame)
    if 'prev_down' not in locals():
        prev_down = down.copy()
        rain_motion = 0
    diff = cv2.absdiff(down, prev_down)
    prev_down = down.copy()

    # Small fast moving streaks increase diff values; threshold and count
    _, diff_th = cv2.threshold(diff, RAIN_DIFF_THRESHOLD, 255, cv2.THRESH_BINARY)
    rain_motion = int(np.count_nonzero(diff_th))

    # --- Lightning analysis ---
    # Use full-resolution grayscale to measure brightness change (lightning)
    gray_frame = gray
    avg_brightness = float(np.mean(gray_frame))
    brightness_spike = max(0.0, avg_brightness - last_brightness)
    last_brightness = avg_brightness

    # --- Task 4: The ERM Hardware Mixer ---
    # This runs for every frame to determine the dominant event
    
    # Normalize audio RMS (0-1 range) and scale to 0-255
    audio_val = audio_rms[frame_idx] if frame_idx < len(audio_rms) else 0
    audio_intensity = np.interp(audio_val, [0, AUDIO_RMS_MAX], [0, 255])

    # Normalize brightness spike (lightning candidate)
    lightning_intensity = np.interp(brightness_spike, [0.0, LIGHTNING_BRIGHTNESS_SCALE_MAX], [0, 255])

    # Normalize rain motion (count of changed pixels in downsampled frame)
    rain_ratio = rain_motion / (down.shape[0] * down.shape[1])
    rain_intensity = np.interp(rain_ratio, [0.0, RAIN_RATIO_MAX], [0, RAIN_INTENSITY_MAX])

    # Save per-frame debug metrics
    timestamp_ms = int((frame_idx / fps) * 1000) if fps > 0 else int(frame_idx * WINDOW_SIZE_MS)
    frames_metrics.append({
        "frame": frame_idx,
        "timestamp_ms": timestamp_ms,
        "brightness": avg_brightness,
        "brightness_spike": brightness_spike,
        "lightning_intensity": float(lightning_intensity),
        "rain_motion": int(rain_motion),
        "rain_ratio": float(rain_ratio),
        "rain_intensity": float(rain_intensity),
        "audio_rms": float(audio_val),
        "audio_intensity": float(audio_intensity),
    })

# Aggregate per-window events from frame metrics
haptic_data = {}
events = []
if frames_per_window <= 0:
    frames_per_window = 1
num_windows = int(np.ceil(total_frames / frames_per_window)) if total_frames > 0 else 0
for w in range(num_windows):
    start_frame = w * frames_per_window
    end_frame = min((w + 1) * frames_per_window, len(frames_metrics))
    window_start_ms = int(w * WINDOW_SIZE_MS)
    window_frames = frames_metrics[start_frame:end_frame]
    if not window_frames:
        # empty window
        events.append({
            "start_ms": window_start_ms,
            "type": "none",
            "intensity": 0,
        })
        haptic_data[str(window_start_ms)] = 0
        continue

    max_lightning = max(f["lightning_intensity"] for f in window_frames)
    max_brightness = max(f["brightness"] for f in window_frames)
    max_rain = max(f["rain_intensity"] for f in window_frames)
    avg_audio = float(np.mean([f["audio_intensity"] for f in window_frames]))

    # Decide event type and intensity
    event_type = "none"
    intensity = 0

    # Thunder detection (visual + audio)
    if max_lightning > LIGHTNING_THRESHOLD_VISUAL_LOW and avg_audio > AUDIO_THUNDER_INTENSITY_THRESHOLD:
        event_type = "thunder_both"
        intensity = 255
    elif max_lightning > LIGHTNING_THRESHOLD_VISUAL_HIGH:
        event_type = "thunder_visual"
        intensity = 255
    elif avg_audio > AUDIO_THUNDER_INTENSITY_THRESHOLD and max_lightning > LIGHTNING_THRESHOLD_VISUAL_LOW:
        event_type = "thunder_audio"
        intensity = int(min(255, avg_audio * 1.5))
    elif max_rain > RAIN_INTENSITY_THRESHOLD:
        event_type = "rain"
        intensity = int(max_rain)
    elif avg_audio > MIN_INTENSITY:
        event_type = "audio"
        intensity = int(avg_audio)
    else:
        event_type = "none"
        intensity = 0

    # enforce min intensity
    if intensity < MIN_INTENSITY:
        intensity = 0

    events.append({
        "start_ms": window_start_ms,
        "type": event_type,
        "intensity": int(intensity),
        "max_brightness": float(max_brightness),
        "max_lightning": float(max_lightning),
        "max_rain": float(max_rain),
        "avg_audio": float(avg_audio),
    })

    haptic_data[str(window_start_ms)] = int(intensity)

cap.release()

total_elapsed = time.time() - start_time
if args.verbose:
    fps_proc = (len(frames_metrics) / total_elapsed) if total_elapsed > 0 else 0
    print(f"Processed {len(frames_metrics)} frames in {total_elapsed:.2f}s ({fps_proc:.1f} fps)")

# --- 5. OUTPUT JSON MAP ---
print(f"Saving haptic map to {OUTPUT_JSON_PATH}...")
def ms_to_timestamp(ms: int) -> str:
    s = ms // 1000
    ms_rem = ms % 1000
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    return f"{h:02d}:{m:02d}:{sec:02d}.{ms_rem:03d}"

# Add readable timestamps to frames and events
for f in frames_metrics:
    f["timestamp"] = ms_to_timestamp(int(f["timestamp_ms"]))

for e in events:
    e["timestamp"] = ms_to_timestamp(int(e["start_ms"]))

output_data = {
    "window_size_ms": WINDOW_SIZE_MS,
    "track": haptic_data,
    "events": events,
    "frames": frames_metrics,
}

with open(OUTPUT_JSON_PATH, 'w') as f:
    json.dump(output_data, f, indent=2)

print("Processing complete.")
